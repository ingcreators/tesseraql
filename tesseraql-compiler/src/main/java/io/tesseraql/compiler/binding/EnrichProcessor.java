package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.camel.tenant.TenantRouting;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.rows.JoinKeys;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.yaml.model.EnrichSpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Folds a keyed reference into a result set's rows (docs/lookups.md, decision 3).
 *
 * <p>The rows of the target result carry a key; this collects the <em>distinct</em> keys,
 * fetches the reference in batches, and composes each match back into the row it belongs to —
 * one statement per {@code batchSize} keys rather than one per row. A hundred-row page over
 * sixty distinct partners costs one round trip, not sixty.
 *
 * <p>Splitting is required rather than merely economical: Oracle refuses an {@code IN} list
 * past 1000 expressions and SQL Server a statement past 2100 parameters, so a key set larger
 * than the batch becomes several statements. Their results merge by key, so their order does
 * not matter. {@code maxKeys} bounds the whole fan-out and fails loudly, because an
 * enrichment that quietly issues ten thousand round trips looks exactly like one that issues
 * one.
 */
public final class EnrichProcessor implements Processor {

    private static final System.Logger LOG = System.getLogger(EnrichProcessor.class.getName());

    /** TQL-SQL-2114: an enrichment's distinct key set exceeded maxKeys. */
    static final TqlErrorCode TOO_MANY_KEYS = new TqlErrorCode(TqlDomain.SQL, 2114);

    /** TQL-CAMEL-3114: an enrichment's target is not a result set with rows. */
    static final TqlErrorCode NO_TARGET = new TqlErrorCode(TqlDomain.CAMEL, 3114);

    /** TQL-CAMEL-3113: a merge matched more than one reference row for one key. */
    static final TqlErrorCode AMBIGUOUS_MERGE = new TqlErrorCode(TqlDomain.CAMEL, 3113);

    /** TQL-LD-0001: the reference returned more rows than the app's materialization cap. */
    private static final TqlErrorCode MATERIALIZATION_OVERFLOW = new TqlErrorCode(TqlDomain.LD, 1);

    /** Statement parameter ceilings; SQL Server's is the one a real key set reaches. */
    private static final int SQLSERVER_PARAMETERS = 2100;
    private static final int PARAMETERS = 65535;
    /** Headroom for the binding's own declared params beside the key list. */
    private static final int RESERVED_PARAMETERS = 32;
    /** The batch no dialect-derived ceiling raises: small enough to keep plans and logs sane. */
    private static final int DEFAULT_BATCH_SIZE = 500;
    /** The default fan-out ceiling: twenty round trips at the default batch. */
    private static final int DEFAULT_MAX_KEYS = 10_000;

    private final String name;
    private final EnrichSpec spec;
    private final List<SqlNode> nodes;
    private final String sourcePath;
    private final String datasource;
    private final String dialect;
    private final TransactionalCommandProcessor.Bounds bounds;
    private final int batchSize;
    private final int maxKeys;

    public EnrichProcessor(String name, EnrichSpec spec, List<SqlNode> nodes,
            String sourcePath, String datasource, String dialect,
            TransactionalCommandProcessor.Bounds bounds) {
        this.name = name;
        this.spec = spec;
        this.nodes = List.copyOf(nodes);
        this.sourcePath = sourcePath;
        this.datasource = datasource;
        this.dialect = dialect;
        this.bounds = bounds;
        this.batchSize = spec.batchSize() != null && spec.batchSize() > 0
                ? spec.batchSize()
                : defaultBatchSize(dialect, spec.keyColumns().size());
        this.maxKeys = spec.maxKeys() != null && spec.maxKeys() > 0
                ? spec.maxKeys()
                : DEFAULT_MAX_KEYS;
    }

    /**
     * How many keys ride one statement when the author does not say: the dialect's parameter
     * ceiling divided by the key's arity, capped at a batch that keeps statement shapes and
     * logs readable. An author does not have to know Oracle's number to stay under it.
     */
    static int defaultBatchSize(String dialect, int arity) {
        int ceiling = "sqlserver".equals(dialect) ? SQLSERVER_PARAMETERS : PARAMETERS;
        return Math.max(1, Math.min(DEFAULT_BATCH_SIZE,
                (ceiling - RESERVED_PARAMETERS) / Math.max(1, arity)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.of(),
                Map.class);
        String into = spec.effectiveInto();
        Object targetRaw = context.get(into);
        if (!(targetRaw instanceof Map<?, ?> target)
                || !(((Map<String, Object>) target).get("rows") instanceof List<?> rowsRaw)) {
            throw new TqlException(NO_TARGET, "enrich '" + name + "': into: '" + into
                    + "' is not a result set with rows");
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rowsRaw;
        if (rows.isEmpty()) {
            return;
        }
        List<String> keyColumns = spec.keyColumns();
        List<String> matchColumns = spec.matchColumns();

        // The canonical key identifies a distinct lookup; the raw values are what binds, so a
        // driver still sees the column's own type rather than its text.
        Map<Object, List<Object>> distinct = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = JoinKeys.of(row, keyColumns);
            distinct.computeIfAbsent(key, ignored -> {
                List<Object> values = new ArrayList<>(keyColumns.size());
                keyColumns.forEach(column -> values.add(row.get(column)));
                return values;
            });
        }
        // A key set that is entirely null has nothing to look up.
        distinct.keySet().removeIf(java.util.Objects::isNull);
        if (distinct.isEmpty()) {
            compose(context, into, (Map<String, Object>) target, rows, Map.of());
            return;
        }
        if (distinct.size() > maxKeys) {
            throw TqlException.builder(TOO_MANY_KEYS)
                    .message("enrich '" + name + "': " + distinct.size()
                            + " distinct keys exceeds maxKeys=" + maxKeys
                            + " (narrow the rows being enriched, or raise maxKeys)")
                    .source(sourcePath)
                    .build();
        }

        List<List<Object>> keys = List.copyOf(distinct.values());
        Map<Object, List<Map<String, Object>>> reference;
        try {
            reference = spec.sql() != null
                    ? fetchSql(exchange, context, keys, matchColumns)
                    : fetchHttp(exchange, context, keys, matchColumns);
        } catch (RuntimeException ex) {
            // Degrading means no key is merged, never the batches that happened to succeed: a
            // list where some rows carry a name and some do not reads as a data problem and
            // gets reported as one (docs/lookups.md, decision 6).
            if (!degradesToEmpty()) {
                throw ex;
            }
            LOG.log(System.Logger.Level.WARNING, "enrich ''{0}'' failed; degrading to no"
                    + " enrichment (onError: empty)", name, ex);
            meter(exchange).counter("tesseraql.enrich.degraded").increment(Map.of("enrich", name));
            reference = Map.of();
        }
        compose(context, into, (Map<String, Object>) target, rows, reference);
    }

    /** Whether the reference declares that a failure degrades instead of failing the request. */
    private boolean degradesToEmpty() {
        return spec.http() != null && spec.http().degradesToEmpty();
    }

    /** The SQL reference: batches of distinct keys on one connection, grouped by the match. */
    private Map<Object, List<Map<String, Object>>> fetchSql(Exchange exchange,
            Map<String, Object> context, List<List<Object>> keys, List<String> matchColumns)
            throws SQLException {
        Map<Object, List<Map<String, Object>>> reference = new LinkedHashMap<>();
        DataSource dataSource = TenantRouting.dataSource(exchange, datasource);
        try (Connection connection = dataSource.getConnection()) {
            for (int from = 0; from < keys.size(); from += batchSize) {
                List<List<Object>> batch = keys.subList(from,
                        Math.min(from + batchSize, keys.size()));
                for (Map<String, Object> row : fetch(exchange, connection, context, batch,
                        matchColumns)) {
                    reference.computeIfAbsent(JoinKeys.of(row, matchColumns),
                            ignored -> new ArrayList<>()).add(row);
                }
            }
        }
        return reference;
    }

    /**
     * The HTTP reference. A {@code batch} call carries the whole key set as {@code keys} and
     * its rows are matched like a SQL reference's, so they must carry the match columns. A
     * {@code perRow} call is made once per distinct key and its rows need no key at all: the
     * answer belongs to the key that was asked for, so the match is implicit.
     */
    private Map<Object, List<Map<String, Object>>> fetchHttp(Exchange exchange,
            Map<String, Object> context, List<List<Object>> keys, List<String> matchColumns) {
        Map<Object, List<Map<String, Object>>> reference = new LinkedHashMap<>();
        if (spec.batches()) {
            for (int from = 0; from < keys.size(); from += batchSize) {
                List<List<Object>> batch = keys.subList(from,
                        Math.min(from + batchSize, keys.size()));
                Map<String, Object> scoped = new LinkedHashMap<>(context);
                scoped.put("keys", keysBind(batch, matchColumns));
                for (Map<String, Object> row : call(exchange, scoped, spec.http().call().url())) {
                    reference.computeIfAbsent(JoinKeys.of(row, matchColumns),
                            ignored -> new ArrayList<>()).add(row);
                }
            }
            return reference;
        }
        for (List<Object> key : keys) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < matchColumns.size(); i++) {
                values.put(matchColumns.get(i), key.get(i));
            }
            Map<String, Object> scoped = new LinkedHashMap<>(context);
            scoped.put("key", values);
            List<Map<String, Object>> rows = call(exchange, scoped,
                    KeyedUrls.fill(spec.http().call().url(), values));
            if (!rows.isEmpty()) {
                reference.put(canonical(key), new ArrayList<>(rows));
            }
        }
        return reference;
    }

    /** The canonical form of one raw key, so it lands under the same key the rows join on. */
    private static Object canonical(List<Object> key) {
        if (key.size() == 1) {
            return JoinKeys.value(key.get(0));
        }
        List<Object> canonical = new ArrayList<>(key.size());
        key.forEach(value -> canonical.add(JoinKeys.value(value)));
        return java.util.Collections.unmodifiableList(canonical);
    }

    /** One outbound call through the gateway, shaped into rows the way an http: source is. */
    private List<Map<String, Object>> call(Exchange exchange, Map<String, Object> context,
            String url) {
        io.tesseraql.yaml.http.HttpSourceGateway gateway = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.HTTP_SOURCE_GATEWAY_BEAN,
                        io.tesseraql.yaml.http.HttpSourceGateway.class);
        if (gateway == null) {
            throw new IllegalStateException("enrich '" + name
                    + "' declares an http: reference but no outbound gateway is bound");
        }
        io.tesseraql.yaml.model.HttpCallSpec declared = spec.http().call();
        io.tesseraql.yaml.model.HttpCallSpec resolved = url.equals(declared.url())
                ? declared
                : new io.tesseraql.yaml.model.HttpCallSpec(declared.method(), url,
                        declared.headers(), declared.query(), declared.credential(),
                        declared.body(), declared.expectStatus(), declared.connectTimeout(),
                        declared.requestTimeout());
        Map<String, Object> result = gateway.call(resolved, context);
        return HttpSourceProcessor.rowsOf(result.get("body"), spec.http().select());
    }

    private static io.tesseraql.core.telemetry.Meter meter(Exchange exchange) {
        io.tesseraql.core.telemetry.Meter meter = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.METER_BEAN,
                        io.tesseraql.core.telemetry.Meter.class);
        return meter != null ? meter : io.tesseraql.core.telemetry.NoopMeter.INSTANCE;
    }

    /** One batch: the distinct keys bind as {@code keys}, beside the binding's own params. */
    private List<Map<String, Object>> fetch(Exchange exchange, Connection connection,
            Map<String, Object> context, List<List<Object>> batch, List<String> matchColumns)
            throws SQLException {
        EvaluationContext evaluation = new EvaluationContext(context);
        Map<String, Object> params = new LinkedHashMap<>();
        spec.sql().params().forEach((bindName, sourceExpr) -> params.put(bindName,
                evaluation.resolve(Arrays.asList(sourceExpr.split("\\.")))));
        io.tesseraql.core.sql.AmbientBinds.seed(params, evaluation);
        params.put("keys", keysBind(batch, matchColumns));
        BoundSql bound = SqlRenderer.render(nodes, params, scopeResolver(exchange), context);
        try (PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            if (bounds != null && bounds.timeoutSeconds() > 0) {
                statement.setQueryTimeout(bounds.timeoutSeconds());
            }
            for (int i = 0; i < bound.parameters().size(); i++) {
                statement.setObject(i + 1, bound.parameters().get(i).value());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                List<Map<String, Object>> rows = new ArrayList<>();
                int maxRows = bounds == null ? -1 : bounds.maxRows();
                while (resultSet.next()) {
                    // The reference is materialized like any other result, so a reference
                    // wider than the app's row cap fails here rather than filling the heap.
                    if (maxRows >= 0 && rows.size() >= maxRows) {
                        throw TqlException.builder(MATERIALIZATION_OVERFLOW)
                                .message("enrich '" + name + "': the reference returned more"
                                        + " than maxRows=" + maxRows + " rows for one batch"
                                        + " (narrow the reference query, or raise"
                                        + " materialize.maxRows)")
                                .source(sourcePath)
                                .build();
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int col = 1; col <= metaData.getColumnCount(); col++) {
                        row.put(io.tesseraql.core.dialect.ResultRows.label(dialect,
                                metaData.getColumnLabel(col)),
                                io.tesseraql.core.dialect.ResultRows
                                        .value(resultSet.getObject(col)));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    /**
     * The {@code keys} bind: a list of values for a single-column key, so an IN-list bind
     * expands it, and a list of row maps keyed by the <em>child</em> column names for a
     * composite one, so a {@code %for} loop over it reads in the reference table's vocabulary.
     */
    private static Object keysBind(List<List<Object>> batch, List<String> matchColumns) {
        if (matchColumns.size() == 1) {
            List<Object> values = new ArrayList<>(batch.size());
            batch.forEach(key -> values.add(key.get(0)));
            return values;
        }
        List<Map<String, Object>> keys = new ArrayList<>(batch.size());
        for (List<Object> key : batch) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < matchColumns.size(); i++) {
                row.put(matchColumns.get(i), key.get(i));
            }
            keys.add(row);
        }
        return keys;
    }

    /**
     * Writes the enriched rows back under the target's key. The rows are copied rather than
     * mutated, so a result shared with another consumer is never changed underneath it, and
     * the whole result is replaced so {@code rowCount} and anything else it carries survive.
     */
    private void compose(Map<String, Object> context, String into, Map<String, Object> target,
            List<Map<String, Object>> rows, Map<Object, List<Map<String, Object>>> reference) {
        List<String> keyColumns = spec.keyColumns();
        List<Map<String, Object>> enriched = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            List<Map<String, Object>> matched = reference.getOrDefault(
                    JoinKeys.of(row, keyColumns), List.of());
            if (spec.merges()) {
                if (matched.size() > 1) {
                    throw new TqlException(AMBIGUOUS_MERGE, "enrich '" + name + "': "
                            + matched.size() + " reference rows matched one key; a merge is"
                            + " many-to-one - use as: to attach them as a list, or narrow the"
                            + " reference query");
                }
                Map<String, Object> match = matched.isEmpty() ? Map.of() : matched.get(0);
                spec.merge().forEach(column -> copy.put(column, match.get(column)));
            } else {
                copy.put(spec.as(), matched);
            }
            enriched.add(copy);
        }
        Map<String, Object> result = new LinkedHashMap<>(target);
        result.put("rows", enriched);
        context.put(into, result);
    }

    /** The data-scope resolver the runtime bound, or the reject-any default. */
    private static io.tesseraql.core.sql.ScopeResolver scopeResolver(Exchange exchange) {
        io.tesseraql.core.sql.ScopeResolver resolver = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.SCOPE_RESOLVER_BEAN,
                        io.tesseraql.core.sql.ScopeResolver.class);
        return resolver != null ? resolver : io.tesseraql.core.sql.ScopeResolver.UNSUPPORTED;
    }

}
