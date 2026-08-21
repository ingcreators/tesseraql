package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.RowEnricher;
import io.tesseraql.pipeline.Exchange;
import java.util.List;
import java.util.Map;

/**
 * Builds an export's {@link RowEnricher} from its {@code enrich:} declarations
 * (docs/lookups.md, slice 13b).
 *
 * <p>The enrichment logic is {@link EnrichProcessor}'s — the same key collection, batching,
 * degrade rule and many-to-one refusal a query route gets. What differs is only where the rows
 * come from: a route enriches a result set held in the execution context, an export enriches a
 * window of a cursor it is still reading.
 *
 * <p>Several {@code enrich:} entries compose in authored order over the same window, so one may
 * fold in a reference keyed by a column an earlier one merged.
 */
final class ExportEnrichment {

    /** TQL-LD-2859: an export's enrichment failed while its rows were being written. */
    private static final TqlErrorCode ENRICH_FAILED = new TqlErrorCode(TqlDomain.LD, 2859);

    private ExportEnrichment() {
    }

    /** Publishes the enricher and its window, or nothing when the route declares no enrichment. */
    static void bind(Exchange exchange, List<EnrichProcessor> enrichments) {
        if (enrichments.isEmpty()) {
            return;
        }
        exchange.setProperty(TesseraqlProperties.EXPORT_ENRICHER, enricher(exchange, enrichments));
        exchange.setProperty(TesseraqlProperties.EXPORT_ENRICH_WINDOW, window(enrichments));
    }

    /**
     * The window every enrichment shares: the smallest {@code batchSize} declared, and zero
     * when the route declares no enrichment at all.
     *
     * <p>The rule is {@link io.tesseraql.yaml.enrich.KeyedReference#window}'s, because a batch
     * chunk step windows its reader by exactly the same reasoning; it used to be written out
     * here and again in the executor, one rule in two modules.
     */
    static int window(List<EnrichProcessor> enrichments) {
        return io.tesseraql.yaml.enrich.KeyedReference.window(enrichments,
                EnrichProcessor::window, 0);
    }

    /** The composed enrichment, or {@code null} when the route declares none. */
    @SuppressWarnings("unchecked")
    static RowEnricher enricher(Exchange exchange, List<EnrichProcessor> enrichments) {
        if (enrichments.isEmpty()) {
            return null;
        }
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.of(),
                Map.class);
        return window -> {
            List<Map<String, Object>> rows = window;
            for (EnrichProcessor enrichment : enrichments) {
                try {
                    rows = enrichment.enrich(exchange, context, rows);
                } catch (java.sql.SQLException ex) {
                    // The write is already in flight, so this cannot be reported as a request
                    // failure the way a route's enrichment is — it fails the transfer, and the
                    // code says which half of the export broke.
                    throw new TqlException(ENRICH_FAILED,
                            "An export enrichment failed while writing rows: " + ex.getMessage(),
                            ex);
                }
            }
            return rows;
        };
    }
}
