package io.tesseraql.compiler.binding;

import io.tesseraql.core.util.Durations;
import io.tesseraql.yaml.model.CacheSpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Declarative HTTP caching for a query route's rendered response (docs/response-shaping.md,
 * "HTTP caching"): stamps {@code Cache-Control} from the route's {@code cache:} block and, with
 * ETags on (the default), hashes the rendered body into a strong {@code ETag} and answers a
 * matching {@code If-None-Match} with {@code 304} and no body — the render already happened,
 * so a 304 saves transfer, not compute; it is deliberately stateless (no server-side cache to
 * invalidate, nothing to coordinate across nodes). Only 200 responses are stamped.
 */
public final class HttpCacheProcessor implements Processor {

    private final String cacheControl;
    private final boolean etag;

    public HttpCacheProcessor(CacheSpec spec) {
        StringBuilder header = new StringBuilder(spec.effectiveVisibility());
        if (spec.maxAge() != null && !spec.maxAge().isBlank()) {
            header.append(", max-age=").append(Durations.toMillis(spec.maxAge()) / 1000);
        }
        if (spec.staleWhileRevalidate() != null && !spec.staleWhileRevalidate().isBlank()) {
            header.append(", stale-while-revalidate=")
                    .append(Durations.toMillis(spec.staleWhileRevalidate()) / 1000);
        }
        this.cacheControl = header.toString();
        this.etag = spec.etagEnabled();
    }

    @Override
    public void process(Exchange exchange) {
        Integer status = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE,
                Integer.class);
        if (status != null && status != 200) {
            return;
        }
        exchange.getMessage().setHeader("Cache-Control", cacheControl);
        if (!etag) {
            return;
        }
        Object body = exchange.getMessage().getBody();
        byte[] bytes = body instanceof byte[] raw
                ? raw
                : body instanceof String text ? text.getBytes(StandardCharsets.UTF_8) : null;
        if (bytes == null) {
            return; // streaming bodies (exports) are not hashed
        }
        String tag = "\"" + hex(bytes) + "\"";
        exchange.getMessage().setHeader("ETag", tag);
        String ifNoneMatch = exchange.getMessage().getHeader("If-None-Match", String.class);
        if (tag.equals(ifNoneMatch)) {
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 304);
            exchange.getMessage().setBody("");
        }
    }

    private static String hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
