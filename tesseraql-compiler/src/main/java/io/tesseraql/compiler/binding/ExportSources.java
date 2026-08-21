package io.tesseraql.compiler.binding;

import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Harvests an export's {@code http:} source results out of the execution context
 * (docs/export-pipeline.md, decision 2).
 *
 * <p>An export's HTTP sources run <em>before</em> its extraction, which reverses the read-route
 * order, and the reason is the connection rather than composition: the export takes its connection
 * and turns auto-commit off at the top and then holds a server-side cursor for the whole write, so
 * a network call inside that window pins a pooled connection and an open transaction for however
 * long the partner takes. Running first means no database resource is held while waiting.
 *
 * <p>On the asynchronous path this also decides <em>when</em>: the call happens here, in the
 * requesting exchange, and its result travels in the handoff record. A background transfer has no
 * request whose deadline could bound a partner that hangs, and it has no gateway or registry
 * either — {@code onError: empty} degrades failures but not hangs.
 */
final class ExportSources {

    private ExportSources() {
    }

    /** The declared sources' results, in declaration order, from the execution context. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> values(Exchange exchange, Set<String> names) {
        if (names.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.of(),
                Map.class);
        Map<String, Object> values = new LinkedHashMap<>();
        for (String name : names) {
            Object value = context.get(name);
            if (value != null) {
                values.put(name, value);
            }
        }
        return Map.copyOf(values);
    }
}
