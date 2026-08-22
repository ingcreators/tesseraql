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
}
