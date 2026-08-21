package io.tesseraql.runtime;

import io.tesseraql.pipeline.RuntimeContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Request-path decoding for Unicode route templates (docs/unicode-identifiers.md): route
 * paths register in decoded form ({@code /受注/{受注番号}} — the endpoint URI parser decodes
 * them), but the router matches the request path as the browser sent it — percent-encoded —
 * so {@code GET /%E5%8F%97%E6%B3%A8} never matched its route. A first-in-line handler
 * decodes exactly the non-ASCII percent-triplets and reroutes.
 *
 * <p>ASCII triplets ({@code %2F}, {@code %2E}, …) deliberately stay encoded: decoding them
 * before matching would let an encoded slash cross a segment boundary — the classic
 * path-smuggling bug. Non-ASCII bytes carry no routing metacharacters, so decoding them is
 * matching-neutral for ASCII routes and exact for Unicode ones.
 */
final class UnicodePaths {

    private UnicodePaths() {
    }

    /** Installs the decoder as the first route on the started platform router. */
    static void install(RuntimeContext runtimeContext, int port) {
        io.vertx.ext.web.Router router = HttpEdgeBeans.router(runtimeContext);
        router.route().order(Integer.MIN_VALUE).handler(ctx -> {
            String path = ctx.request().path();
            String decoded = decodeNonAscii(path);
            if (decoded.equals(path)) {
                ctx.next();
            } else {
                // The reroute target carries the original query string — the request wrapper
                // re-parses it from the URI, and a bare path would drop every query parameter.
                String query = ctx.request().query();
                ctx.reroute(query == null || query.isEmpty()
                        ? decoded
                        : decoded + "?" + query);
            }
        });
    }

    /**
     * Decodes percent-triplets whose byte is ≥ 0x80 (UTF-8 continuation and lead bytes are
     * all in that range); everything else — literal characters, ASCII triplets, malformed
     * sequences — passes through byte-for-byte.
     */
    static String decodeNonAscii(String path) {
        if (path.indexOf('%') < 0) {
            return path;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(path.length());
        byte[] raw = path.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < raw.length; i++) {
            int decoded = raw[i] == '%' && i + 2 < raw.length
                    ? hexByte(raw[i + 1], raw[i + 2])
                    : -1;
            if (decoded >= 0x80) {
                out.write(decoded);
                i += 2;
            } else {
                out.write(raw[i]);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hexByte(byte high, byte low) {
        int h = Character.digit(high, 16);
        int l = Character.digit(low, 16);
        return h < 0 || l < 0 ? -1 : (h << 4) | l;
    }
}
