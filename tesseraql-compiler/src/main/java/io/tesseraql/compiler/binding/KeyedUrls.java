package io.tesseraql.compiler.binding;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts one key's values into a reference URL (docs/lookups.md, decision 21).
 *
 * <p>A per-key HTTP reference is usually keyed in the path — {@code GET /partners/{key.code}}
 * — which no other declaration in the framework needs, because every other call is made once
 * with a URL known at build time. The substitution happens here rather than in the outbound
 * client, so the client keeps taking a finished URL and the host still faces the same
 * allow-list check it always did.
 *
 * <p>Values are percent-encoded for a path segment, not with {@code URLEncoder}: that class
 * encodes for a query string, where a space is {@code +} and {@code /} passes through. A key
 * carrying either would otherwise reach a different resource than the one asked for.
 */
final class KeyedUrls {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{key\\.([^}]+)}");

    /** RFC 3986 unreserved characters, which never need escaping. */
    private static final String UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

    private KeyedUrls() {
    }

    /** Whether {@code url} carries at least one {@code {key.…}} placeholder. */
    static boolean isKeyed(String url) {
        return url != null && PLACEHOLDER.matcher(url).find();
    }

    /**
     * {@code url} with every {@code {key.<name>}} replaced by that key column's encoded value.
     * A placeholder naming a column the key does not carry resolves to the empty string, which
     * a keyed URL's own lint (the reference must use its keys) is what prevents.
     */
    static String fill(String url, Map<String, Object> key) {
        Matcher matcher = PLACEHOLDER.matcher(url);
        StringBuilder filled = new StringBuilder();
        while (matcher.find()) {
            Object value = key.get(matcher.group(1));
            matcher.appendReplacement(filled,
                    Matcher.quoteReplacement(encode(value == null ? "" : String.valueOf(value))));
        }
        matcher.appendTail(filled);
        return filled.toString();
    }

    /** Percent-encodes one path segment; every byte outside the unreserved set is escaped. */
    static String encode(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (raw & 0xFF);
            if (UNRESERVED.indexOf(c) >= 0) {
                encoded.append(c);
            } else {
                encoded.append('%').append(String.format("%02X", raw & 0xFF));
            }
        }
        return encoded.toString();
    }
}
