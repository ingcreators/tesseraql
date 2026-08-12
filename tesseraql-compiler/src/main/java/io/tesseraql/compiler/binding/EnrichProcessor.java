package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.camel.tenant.TenantRouting;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.yaml.enrich.KeyedReference;
import io.tesseraql.yaml.model.EnrichSpec;
import java.sql.Connection;
import java.sql.SQLException;
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

    /** TQL-CAMEL-3114: the target of an enrich: is not a result set with rows. */
    static final TqlErrorCode NO_TARGET = new TqlErrorCode(TqlDomain.CAMEL, 3114);

    private final String name;
    private final EnrichSpec spec;
    private final KeyedReference reference;

    public EnrichProcessor(String name, EnrichSpec spec, List<SqlNode> nodes,
            String sourcePath, String datasource, String dialect,
            TransactionalCommandProcessor.Bounds bounds) {
        this.name = name;
        this.spec = spec;
        this.reference = new KeyedReference(name, spec, nodes, sourcePath, datasource, dialect,
                bounds == null
                        ? KeyedReference.Bounds.none()
                        : new KeyedReference.Bounds(bounds.timeoutSeconds(), bounds.maxRows()),
                HttpSourceProcessor::rowsOf);
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
        Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) target);
        result.put("rows", enrich(exchange, context, rows));
        context.put(into, result);
    }

    /** The window an export enriches at a time: the keys one reference statement already binds. */
    public int window() {
        return reference.batchSize();
    }

    /**
     * The enrichment itself, over a list of rows.
     *
     * <p>Separate from {@link #process} because an export enriches a window at a time rather
     * than a result set held in the execution context (docs/lookups.md, slice 13b). The
     * algorithm is {@link KeyedReference}'s — shared with the batch executor's chunk step,
     * which cannot see this module.
     */
    public List<Map<String, Object>> enrich(Exchange exchange, Map<String, Object> context,
            List<Map<String, Object>> rows) throws SQLException {
        return reference.enrich(environment(exchange), context, rows);
    }

    /** This surface's answers to what a reference needs: a connection, a scope, a gateway. */
    private KeyedReference.Environment environment(Exchange exchange) {
        return new KeyedReference.Environment() {
            @Override
            public Connection connection(String datasource) throws SQLException {
                DataSource dataSource = TenantRouting.dataSource(exchange, datasource);
                return dataSource.getConnection();
            }

            @Override
            public io.tesseraql.core.sql.ScopeResolver scopeResolver() {
                io.tesseraql.core.sql.ScopeResolver resolver = exchange.getContext().getRegistry()
                        .lookupByNameAndType(TesseraqlProperties.SCOPE_RESOLVER_BEAN,
                                io.tesseraql.core.sql.ScopeResolver.class);
                return resolver != null
                        ? resolver
                        : io.tesseraql.core.sql.ScopeResolver.UNSUPPORTED;
            }

            @Override
            public io.tesseraql.yaml.http.OutboundGateway gateway() {
                return exchange.getContext().getRegistry().lookupByNameAndType(
                        TesseraqlProperties.OUTBOUND_GATEWAY_BEAN,
                        io.tesseraql.yaml.http.OutboundGateway.class);
            }

            @Override
            public void degraded(String enrichment) {
                io.tesseraql.core.telemetry.Meter meter = exchange.getContext().getRegistry()
                        .lookupByNameAndType(TesseraqlProperties.METER_BEAN,
                                io.tesseraql.core.telemetry.Meter.class);
                if (meter != null) {
                    meter.counter("tesseraql.enrich.degraded")
                            .increment(Map.of("enrich", enrichment));
                }
            }
        };
    }
}
