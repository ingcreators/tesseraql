package io.tesseraql.yaml.enrich;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.rows.JoinKeys;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.ScopeResolver;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.yaml.http.OutboundGateway;
import io.tesseraql.yaml.model.EnrichSpec;
import io.tesseraql.yaml.model.HttpCallSpec;
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

/**
 * A reference keyed by the rows being enriched (docs/lookups.md, decision 3): the distinct keys
 * are collected, the reference is fetched in batches, and each match is composed back into the
 * row it belongs to — one statement per {@code batchSize} keys rather than one per row.
 *
 * <p>This lives here, in the module that owns {@link EnrichSpec}, because three surfaces enrich
 * and they sit in modules that cannot see each other: a route's result set (the compiler), an
 * export's row window (the compiler again, through the file writers), and a chunk step's window
 * between reader and writer (the batch executor, in operations). A second copy of the key
 * collection, the batching, the degrade rule and the many-to-one refusal is how those surfaces
 * would start disagreeing about what a merge means.
 *
 * <p>What differs per surface is only where a connection and a gateway come from, which is
 * {@link Environment}. Everything else — including every refusal — is here.
 */
public final class KeyedReference {

    private static final System.Logger LOG = System.getLogger(KeyedReference.class.getName());

    /** TQL-SQL-2114: the distinct key set exceeded maxKeys. */
    public static final TqlErrorCode TOO_MANY_KEYS = new TqlErrorCode(TqlDomain.SQL, 2114);
    /** TQL-ROUTE-3113: a merge matched more than one reference row for one key. */
    public static final TqlErrorCode AMBIGUOUS_MERGE = new TqlErrorCode(TqlDomain.ROUTE, 3113);
    /** TQL-LD-1: the reference returned more rows than the app's materialization cap. */
    public static final TqlErrorCode MATERIALIZATION_OVERFLOW = new TqlErrorCode(TqlDomain.LD, 1);
    /** TQL-ROUTE-3114: an enrichment's source: names no result set. */
    public static final TqlErrorCode NO_SIBLING = new TqlErrorCode(TqlDomain.ROUTE, 3114);

    /** SQL Server refuses a statement past this many parameters. */
    private static final int SQLSERVER_PARAMETERS = 2100;
    private static final int PARAMETERS = 65535;
    /** Room for the reference query's own binds beside the key set. */
    private static final int RESERVED_PARAMETERS = 32;
    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final int DEFAULT_MAX_KEYS = 10_000;

    /**
     * What a surface supplies: a connection for a SQL reference, the scope predicate its SQL
     * renders under, the gateway an HTTP reference calls through, and somewhere to count a
     * degraded enrichment. A surface that cannot do one of these says so by throwing.
     */
    public interface Environment {

        /** A connection on the reference's datasource; the caller owns tenant routing. */
        Connection connection(String datasource) throws SQLException;

        /** The data-scope resolver the reference's SQL renders under. */
        ScopeResolver scopeResolver();

        /** The one outbound gateway, or {@code null} when this surface binds none. */
        OutboundGateway gateway();

        /** Records that an enrichment degraded to empty, for the operator who has to see it. */
        void degraded(String enrichment);
    }

    /**
     * How a response body becomes rows, which is the {@code http:} source's rule and lives with
     * the surface that owns it — passed in rather than reimplemented, so an enrichment's rows
     * and a source's rows are shaped by one piece of code.
     */
    @FunctionalInterface
    public interface RowShaper {
        List<Map<String, Object>> apply(Object body, String select);
    }

    /** The statement bounds a surface applies to a reference query. */
    public record Bounds(int timeoutSeconds, int maxRows) {

        /** Unbounded, for a surface that applies none. */
        public static Bounds none() {
            return new Bounds(0, -1);
        }
    }

    private final String name;
    private final EnrichSpec spec;
    private final List<SqlNode> nodes;
    private final String sourcePath;
    private final String datasource;
    private final Bounds bounds;
    private final int batchSize;
    private final int maxKeys;
    private final RowShaper shaper;

    public KeyedReference(String name, EnrichSpec spec, List<SqlNode> nodes, String sourcePath,
            String datasource, String dialect, Bounds bounds, RowShaper shaper) {
        this.shaper = shaper;
        this.name = name;
        this.spec = spec;
        this.nodes = List.copyOf(nodes);
        this.sourcePath = sourcePath;
        this.datasource = datasource;
        this.bounds = bounds == null ? Bounds.none() : bounds;
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
    public static int defaultBatchSize(String dialect, int arity) {
        int ceiling = "sqlserver".equals(dialect) ? SQLSERVER_PARAMETERS : PARAMETERS;
        return Math.max(1, Math.min(DEFAULT_BATCH_SIZE,
                (ceiling - RESERVED_PARAMETERS) / Math.max(1, arity)));
    }

    /** Keys per statement — also the window a streaming surface enriches at a time. */
    public int batchSize() {
        return batchSize;
    }

    /**
     * The window several enrichments share: the smallest {@code batchSize} any of them declared,
     * or {@code whenNone} when there are none at all.
     *
     * <p>The smallest, because a window larger than an enrichment's batch would make that one
     * fetch twice per window — the batching is the thing that keeps the reference query count
     * proportional to keys rather than to rows.
     *
     * <p>Stated once, beside the {@code batchSize} it reads, because every surface that reads
     * rows a window at a time asks it: an export writing a cursor through its codec, and a batch
     * chunk step feeding its writer. They hold their enrichments in different types — hence the
     * mapper — and disagree only on what "none" means, which is why that is the caller's word.
     */
    public static <T> int window(java.util.Collection<T> enrichments,
            java.util.function.ToIntFunction<T> batchSize, int whenNone) {
        int smallest = Integer.MAX_VALUE;
        for (T enrichment : enrichments) {
            smallest = Math.min(smallest, batchSize.applyAsInt(enrichment));
        }
        return smallest == Integer.MAX_VALUE ? whenNone : smallest;
    }

    /** The enrichment's declared name, for a message that has to say which one. */
    public String name() {
        return name;
    }

    /**
     * The rows with the reference composed onto each of them.
     *
     * <p>The whole algorithm, so every surface gets the same answer: the distinct keys, the
     * {@code maxKeys} bound, the batched fetch, the degrade rule, and the many-to-one refusal.
     */
    public List<Map<String, Object>> enrich(Environment environment, Map<String, Object> context,
            List<Map<String, Object>> rows) throws SQLException {
        if (rows.isEmpty()) {
            return rows;
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
            return compose(rows, Map.of());
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
            if (spec.composesSource()) {
                reference = fromSibling(context, matchColumns);
            } else if (spec.sql() != null) {
                reference = fetchSql(environment, context, keys, matchColumns);
            } else {
                reference = fetchHttp(environment, context, keys, matchColumns);
            }
        } catch (RuntimeException ex) {
            // Degrading means no key is merged, never the batches that happened to succeed: a
            // list where some rows carry a name and some do not reads as a data problem and
            // gets reported as one (docs/lookups.md, decision 6).
            if (!degradesToEmpty()) {
                throw ex;
            }
            LOG.log(System.Logger.Level.WARNING, "enrich ''{0}'' failed; degrading to no"
                    + " enrichment (onError: empty)", name, ex);
            environment.degraded(name);
            reference = Map.of();
        }
        return compose(rows, reference);
    }

    /**
     * A sibling source, already in the context: its rows group by the match columns exactly as a
     * fetched reference's do, so everything downstream — the composition, the many-to-one
     * refusal, the null handling — is the one implementation.
     *
     * <p>There is no key set to send anywhere, which is the whole point: {@code nest:} existed
     * because joining two results the document already had needed no fetch, and it was a second
     * vocabulary for that reason alone.
     *
     * <p>The name is a <em>context path</em>, not a root key. A route's sources sit at the root,
     * so {@code source: rates} reads the same either way; a job's results sit under
     * {@code steps}, and a root lookup could never reach one — which made the arm unaddressable
     * on the whole job side rather than unsupported there.
     */
    @SuppressWarnings("unchecked")
    private Map<Object, List<Map<String, Object>>> fromSibling(Map<String, Object> context,
            List<String> matchColumns) {
        Object sibling = new io.tesseraql.core.expr.EvaluationContext(context)
                .resolve(java.util.Arrays.asList(spec.source().split("\\.")));
        if (!(sibling instanceof Map<?, ?> result)
                || !(((Map<String, Object>) result).get("rows") instanceof List<?> siblingRows)) {
            throw TqlException.builder(NO_SIBLING)
                    .message("enrich '" + name + "': source: '" + spec.source()
                            + "' is not a result set with rows"
                            + (sibling instanceof Map<?, ?> spooled
                                    && ((Map<String, Object>) spooled).containsKey("spool")
                                            ? " - it spooled its rows instead of holding them,"
                                                    + " so load the spool into a table with a"
                                                    + " chunk: step and reference that"
                                            : ""))
                    .source(sourcePath)
                    .build();
        }
        Map<Object, List<Map<String, Object>>> reference = new LinkedHashMap<>();
        for (Object raw : siblingRows) {
            Map<String, Object> row = (Map<String, Object>) raw;
            reference.computeIfAbsent(JoinKeys.of(row, matchColumns),
                    ignored -> new ArrayList<>()).add(row);
        }
        return reference;
    }

    /** Whether the reference declares that a failure degrades instead of failing the request. */
    private boolean degradesToEmpty() {
        return spec.http() != null && spec.http().degradesToEmpty();
    }

    /** The SQL reference: batches of distinct keys on one connection, grouped by the match. */
    private Map<Object, List<Map<String, Object>>> fetchSql(Environment environment,
            Map<String, Object> context, List<List<Object>> keys, List<String> matchColumns)
            throws SQLException {
        Map<Object, List<Map<String, Object>>> reference = new LinkedHashMap<>();
        try (Connection connection = environment.connection(datasource)) {
            for (int from = 0; from < keys.size(); from += batchSize) {
                List<List<Object>> batch = keys.subList(from,
                        Math.min(from + batchSize, keys.size()));
                for (Map<String, Object> row : fetch(environment, connection, context, batch,
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
    private Map<Object, List<Map<String, Object>>> fetchHttp(Environment environment,
            Map<String, Object> context, List<List<Object>> keys, List<String> matchColumns) {
        Map<Object, List<Map<String, Object>>> reference = new LinkedHashMap<>();
        if (spec.batches()) {
            for (int from = 0; from < keys.size(); from += batchSize) {
                List<List<Object>> batch = keys.subList(from,
                        Math.min(from + batchSize, keys.size()));
                Map<String, Object> scoped = new LinkedHashMap<>(context);
                scoped.put("keys", keysBind(batch, matchColumns));
                for (Map<String, Object> row : call(environment, scoped,
                        spec.http().call().url())) {
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
            List<Map<String, Object>> rows = call(environment, scoped,
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
    private List<Map<String, Object>> call(Environment environment, Map<String, Object> context,
            String url) {
        OutboundGateway gateway = environment.gateway();
        if (gateway == null) {
            throw new IllegalStateException("enrich '" + name
                    + "' declares an http: reference but no outbound gateway is bound");
        }
        HttpCallSpec declared = spec.http().call();
        HttpCallSpec resolved = url.equals(declared.url())
                ? declared
                : new HttpCallSpec(declared.method(), url, declared.headers(), declared.query(),
                        declared.credential(), declared.body(), declared.expectStatus(),
                        declared.connectTimeout(), declared.requestTimeout());
        return shaper.apply(gateway.call(resolved, context).get("body"),
                spec.http().select());
    }

    /** The reference query for one batch, rendered and run on the caller's connection. */
    private List<Map<String, Object>> fetch(Environment environment, Connection connection,
            Map<String, Object> context, List<List<Object>> batch, List<String> matchColumns)
            throws SQLException {
        EvaluationContext evaluation = new EvaluationContext(context);
        Map<String, Object> params = new LinkedHashMap<>();
        spec.sql().params().forEach((bindName, sourceExpr) -> params.put(bindName,
                evaluation.resolve(Arrays.asList(sourceExpr.split("\\.")))));
        io.tesseraql.core.sql.AmbientBinds.seed(params, evaluation);
        params.put("keys", keysBind(batch, matchColumns));
        BoundSql bound = SqlRenderer.render(nodes, params, environment.scopeResolver(), context);
        try (PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            if (bounds.timeoutSeconds() > 0) {
                statement.setQueryTimeout(bounds.timeoutSeconds());
            }
            for (int i = 0; i < bound.parameters().size(); i++) {
                statement.setObject(i + 1, bound.parameters().get(i).value());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                List<Map<String, Object>> rows = new ArrayList<>();
                int maxRows = bounds.maxRows();
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
                        String label = metaData.getColumnLabel(col);
                        Object value = resultSet.getObject(col);
                        row.put(label, value);
                        // Oracle answers uppercase labels; binds are written lowercase.
                        row.putIfAbsent(label.toLowerCase(java.util.Locale.ROOT), value);
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    /** The key set as it binds: a list of values for one column, a list of rows for several. */
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
     * Each row with its match composed on. Rows are copied rather than mutated, so a row a
     * codec or a writer has already handled can never change underneath it.
     */
    private List<Map<String, Object>> compose(List<Map<String, Object>> rows,
            Map<Object, List<Map<String, Object>>> reference) {
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
        return enriched;
    }
}
