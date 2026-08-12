package io.tesseraql.yaml.http;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rows a response body yields under an optional {@code select:} path.
 *
 * <p>One rule for every surface that turns a call into rows — an {@code http:} source, an
 * enrichment's HTTP reference on a route or an export, and a chunk step's reference in the batch
 * executor. It lives here, beside {@link OutboundGateway}, because those callers sit in modules
 * that cannot see each other and a second copy would mean a body shaped one way on a route and
 * another way in a job.
 */
public final class HttpRows {

    private HttpRows() {
    }

    /** The rows the body yields, or none when the path misses or the shape is not row-like. */
    public static List<Map<String, Object>> of(Object body, String select) {
        return rows(select(body, select));
    }

    /**
     * Walks the optional dotted {@code select:} path into the parsed JSON; null on a miss.
     *
     * <p>Public because an {@code http:} source publishes the selected body beside its rows —
     * it needs the two halves separately, and reimplementing the walk is how they drift.
     */
    public static Object select(Object body, String select) {
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

    /** The row form of an already-selected body. */
    public static List<Map<String, Object>> rows(Object body) {
        if (body instanceof List<?> list) {
            return list.stream().map(HttpRows::row).toList();
        }
        if (body instanceof Map<?, ?> map) {
            return List.of(row(map));
        }
        return List.of();
    }

    /** A scalar element becomes a one-column row, so a list of ids is still a result set. */
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
