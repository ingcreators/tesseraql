package io.tesseraql.yaml.config;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * App-wide default response headers (docs/route-defaults.md), declared once under
 * {@code tesseraql.security.responseHeaders} and merged under every HTML response's
 * {@code headers:} map — the declare-once home for the security header block
 * ({@code Content-Security-Policy}, {@code X-Content-Type-Options}, …) that would otherwise be
 * restated per route.
 *
 * <pre>{@code
 * security:
 *   responseHeaders:
 *     Content-Security-Policy: "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'"
 *     X-Content-Type-Options: nosniff
 *     X-Frame-Options: DENY
 *     Referrer-Policy: no-referrer
 * }</pre>
 *
 * <p>The merge is per header name and route-local always wins. A route suppresses a default it
 * must not send by declaring the header with the literal value {@code unset} (YAML {@code null}
 * cannot reach the model — response header maps reject null values), and {@code unset} never
 * reaches the wire. The linter flags identical restatements and weakened overrides
 * ({@code TQL-SEC-4133}/{@code TQL-SEC-4134}).
 */
public final class ResponseHeaderDefaults {

    private static final TqlErrorCode INVALID = new TqlErrorCode(TqlDomain.SEC, 4135);

    /** The route-local value that suppresses a default header. */
    public static final String UNSET = "unset";

    private final Map<String, String> headers;

    private ResponseHeaderDefaults(Map<String, String> headers) {
        this.headers = Map.copyOf(headers);
    }

    /** Parses {@code tesseraql.security.responseHeaders}; absent config yields no defaults. */
    public static ResponseHeaderDefaults from(AppConfig config) {
        Object node = config.navigate("tesseraql.security.responseHeaders");
        if (node == null) {
            return new ResponseHeaderDefaults(Map.of());
        }
        if (!(node instanceof Map<?, ?> map)) {
            throw new TqlException(INVALID,
                    "tesseraql.security.responseHeaders must be a map of header name to value");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getValue() == null) {
                throw new TqlException(INVALID, "Default response header '" + entry.getKey()
                        + "' has no value — a default exists to be sent");
            }
            headers.put(String.valueOf(entry.getKey()),
                    config.resolve(String.valueOf(entry.getValue())));
        }
        return new ResponseHeaderDefaults(headers);
    }

    /** The declared default headers, in declaration order. */
    public Map<String, String> headers() {
        return headers;
    }

    public boolean isEmpty() {
        return headers.isEmpty();
    }

    /**
     * The effective header map for one response: defaults first (stable order), then the route's
     * own entries — a route entry overrides its default by name, and a route entry valued
     * {@link #UNSET} removes the header entirely.
     */
    public Map<String, Object> mergeUnder(Map<String, Object> routeHeaders) {
        if (headers.isEmpty()) {
            return withoutUnset(routeHeaders);
        }
        Map<String, Object> merged = new LinkedHashMap<>(headers);
        merged.putAll(routeHeaders);
        return withoutUnset(merged);
    }

    private static Map<String, Object> withoutUnset(Map<String, Object> headers) {
        if (!headers.containsValue(UNSET)) {
            return headers;
        }
        Map<String, Object> kept = new LinkedHashMap<>(headers);
        kept.values().removeIf(UNSET::equals);
        return kept;
    }
}
