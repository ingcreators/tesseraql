package io.tesseraql.yaml.config;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.OrderedCopies;
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
        // Declaration order: the javadoc below promises it, the lint findings this map
        // feeds are emitted in it, and every bundled app declares a four-header security
        // block whose authored order was unreachable (docs/deterministic-output.md).
        this.headers = OrderedCopies.map(headers);
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
            String name = String.valueOf(entry.getKey());
            String value = config.resolve(String.valueOf(entry.getValue()));
            // Judged here, where it is declared, and nowhere else (docs/edge-hygiene.md E3):
            // the compiled routes' edge refuses a control character per request, but the
            // asset, SSE and MCP surfaces write these values straight to the transport, where
            // a control character hangs the connection. The lint calls this same method, so
            // the author learns it at build time with the header named, and boot refuses it.
            int control = controlAt(value);
            if (control >= 0) {
                throw new TqlException(INVALID, "Default response header '" + name
                        + "' carries the control character " + unicodeName(value.charAt(control))
                        + " — a header value is one line of printable text");
            }
            headers.put(name, value);
        }
        return new ResponseHeaderDefaults(headers);
    }

    /** The character as the U+XXXX name a message reads. */
    public static String unicodeName(char c) {
        return String.format("U+%04X", (int) c);
    }

    /** The index of the first C0 control other than HTAB, or DEL, in a value; -1 if none. */
    public static int controlAt(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7F) {
                return i;
            }
        }
        return -1;
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
