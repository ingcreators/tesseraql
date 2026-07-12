package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.yaml.http.HttpSourceGateway;
import io.tesseraql.yaml.model.HttpSourceSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Executes one named {@code http:} source of a query route (docs/connectors.md, "HTTP
 * sources") through the runtime's outbound gateway and lands the result in the execution
 * context under the source's name — shaped like a SQL result, so the response and the view
 * patterns compose it exactly like a named query:
 *
 * <ul>
 * <li>{@code <name>.rows} — the selected JSON as rows: an array becomes one row per element
 * (a non-object element becomes {@code {value: …}}), an object becomes a single row, and a
 * missing/empty body is zero rows;</li>
 * <li>{@code <name>.body} — the selected JSON as-is, for object-shaped shaping expressions;</li>
 * <li>{@code <name>.status} — the upstream status; with {@code onError: empty} a failed call
 * degrades to zero rows plus {@code <name>.error}, and the page still renders.</li>
 * </ul>
 */
public final class HttpSourceProcessor implements Processor {

    private final String name;
    private final HttpSourceSpec spec;

    public HttpSourceProcessor(String name, HttpSourceSpec spec) {
        this.name = name;
        this.spec = spec;
    }

    @Override
    public void process(Exchange exchange) {
        @SuppressWarnings("unchecked")
        Map<String, Object> context = exchange.getProperty(
                TesseraqlProperties.CONTEXT, Map.of(), Map.class);
        HttpSourceGateway gateway = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.HTTP_SOURCE_GATEWAY_BEAN,
                        HttpSourceGateway.class);
        if (gateway == null) {
            throw new IllegalStateException("http: source '" + name
                    + "' declared but no outbound gateway is bound");
        }
        try {
            Map<String, Object> result = gateway.call(spec.toCall(), context);
            context.put(name, shape(result));
        } catch (RuntimeException ex) {
            if (!spec.degradesToEmpty()) {
                throw ex;
            }
            Map<String, Object> degraded = new LinkedHashMap<>();
            degraded.put("rows", List.of());
            degraded.put("body", null);
            degraded.put("status", 0);
            degraded.put("error", ex.getMessage());
            context.put(name, degraded);
        }
    }

    /** The context entry: the gateway's {status} plus the selected body and its row form. */
    private Map<String, Object> shape(Map<String, Object> result) {
        Object body = select(result.get("body"), spec.select());
        Map<String, Object> shaped = new LinkedHashMap<>();
        shaped.put("rows", rows(body));
        shaped.put("body", body);
        shaped.put("status", result.get("status"));
        return shaped;
    }

    /** Walks the optional dotted {@code select:} path into the parsed JSON; null on a miss. */
    private static Object select(Object body, String select) {
        if (select == null || select.isBlank()) {
            return body;
        }
        Object current = body;
        for (String segment : select.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    private static List<Map<String, Object>> rows(Object body) {
        if (body instanceof List<?> list) {
            return list.stream().map(HttpSourceProcessor::row).toList();
        }
        if (body instanceof Map<?, ?> map) {
            return List.of(row(map));
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Object element) {
        if (element instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("value", element);
        return wrapped;
    }
}
