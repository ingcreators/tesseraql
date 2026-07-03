package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Emits the automatic pagination response headers (roadmap Phase 41) from the {@code page}
 * context entry the SQL producer published: {@code X-Total-Count} when the route counts, and
 * RFC 8288 {@code Link} {@code rel="next"}/{@code rel="prev"} built from the request URI with
 * the {@code page}/{@code after} parameter rewritten.
 */
public final class PageHeaders implements Processor {

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT,
                Map.of(), Map.class);
        Object raw = context.get("page");
        if (!(raw instanceof Map)) {
            return;
        }
        Map<String, Object> page = (Map<String, Object>) raw;
        if (page.get("totalRows") instanceof Number total) {
            exchange.getMessage().setHeader("X-Total-Count", String.valueOf(total.longValue()));
        }
        String uri = exchange.getMessage().getHeader(Exchange.HTTP_URI, String.class);
        if (uri == null) {
            return;
        }
        String path = uri.indexOf('?') < 0 ? uri : uri.substring(0, uri.indexOf('?'));
        String query = exchange.getMessage().getHeader(Exchange.HTTP_QUERY, String.class);
        StringBuilder link = new StringBuilder();
        Object next = page.get("next");
        boolean hasNext = Boolean.TRUE.equals(page.get("hasNext"));
        long number = page.get("number") instanceof Number n ? n.longValue() : 1;
        if (hasNext) {
            String target = next != null
                    ? path + "?" + rewrite(query, "after", String.valueOf(next))
                    : path + "?" + rewrite(query, "page", String.valueOf(number + 1));
            link.append('<').append(target).append(">; rel=\"next\"");
        }
        if (next == null && number > 1) {
            if (link.length() > 0) {
                link.append(", ");
            }
            link.append('<').append(path).append('?')
                    .append(rewrite(query, "page", String.valueOf(number - 1)))
                    .append(">; rel=\"prev\"");
        }
        if (link.length() > 0) {
            exchange.getMessage().setHeader("Link", link.toString());
        }
    }

    /** The query string with {@code name} set to {@code value} (other parameters preserved). */
    static String rewrite(String query, String name, String value) {
        StringBuilder out = new StringBuilder();
        if (query != null && !query.isBlank()) {
            for (String pair : query.split("&")) {
                if (!pair.startsWith(name + "=") && !pair.isBlank()) {
                    if (out.length() > 0) {
                        out.append('&');
                    }
                    out.append(pair);
                }
            }
        }
        if (out.length() > 0) {
            out.append('&');
        }
        return out.append(name).append('=')
                .append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }
}
