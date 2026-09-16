package io.tesseraql.core.http;

import java.util.Locale;
import java.util.Set;

/**
 * Response-header names a route may never put on the wire.
 *
 * <p>Two families. The transport-owned names — framing and connection control — are computed by
 * the server from the body it actually writes: a declared {@code Content-Length} that disagrees
 * with the bytes truncates the response or hangs the keep-alive connection waiting for bytes that
 * never come, and a declared {@code Connection} or {@code Transfer-Encoding} is a
 * smuggling-shaped protocol violation. The {@code tql.} prefix is this framework's internal
 * namespace (docs/camel-removal.md decision 3), which exists precisely because it never leaves.
 *
 * <p>One list, shared by the linter that refuses a declared reserved name at build time and the
 * HTTP edge that drops one at the wire — the outbound {@code HeaderFilter} used to hold this
 * knowledge and left with the request-echo problem it mainly existed for
 * (docs/vertx-native.md decision 1); this keeps only the half whose reason survives.
 */
public final class ReservedHeaders {

    private static final Set<String> TRANSPORT_OWNED = Set.of(
            "content-length",
            "transfer-encoding",
            "connection",
            "keep-alive",
            "upgrade",
            "te",
            "trailer",
            "host",
            "date");

    private ReservedHeaders() {
    }

    /** Whether {@code name} is one no route response may carry onto the wire. */
    public static boolean neverDeclared(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return TRANSPORT_OWNED.contains(lower) || lower.startsWith("tql.");
    }

    /**
     * Why {@code name} is not a header name the wire can carry, or null when it is one: an
     * RFC 9110 token — non-empty, every character a letter, a digit or one of
     * {@code !#$%&'*+-.^_`|~}. A space, a colon, a slash or a non-ASCII letter is not a name
     * the transport writes; Vert.x refuses it as the header is added, inside the transport,
     * where the refusal hangs the response instead of failing it. Every writer judges the
     * name here first — the lint and the boot for a declared header, the edge for one written
     * by code — where a refusal still has a status (docs/audit-low-leads.md, DN-02a).
     */
    public static String notAToken(String name) {
        if (name == null || name.isEmpty()) {
            return "is empty - a header name is a token: letters, digits and !#$%&'*+-.^_`|~";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!isTchar(c)) {
                return "carries " + String.format("U+%04X", (int) c) + " at index " + i
                        + " - a header name is a token: letters, digits and !#$%&'*+-.^_`|~,"
                        + " with no space, colon or non-ASCII character";
            }
        }
        return null;
    }

    private static boolean isTchar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }
}
