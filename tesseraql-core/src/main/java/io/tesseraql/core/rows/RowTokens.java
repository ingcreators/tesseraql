package io.tesseraql.core.rows;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * One opaque token per row over a declared key (docs/list-surface.md decision 2): the machine
 * identity a list row carries — its anchor, its selection value, its snapshot membership key —
 * whatever the key's arity. Values are canonicalized the way {@link JoinKeys} canonicalizes
 * (text), each value is base64url-encoded without padding, and a composite key joins its parts
 * with {@code .} — a character outside the base64url alphabet, so the encoding needs no escaping
 * rules and the token stays legal in an HTML id, a URL fragment and a query value.
 *
 * <p>Tokens are deliberately not signed and not secrets: per the upstream datagrid contract they
 * prove nothing, and every consumer re-authorizes what it fetches. A null or absent key
 * component is refused — a row without its declared identity is a data defect, never a silent
 * skip. Callers translate the {@link IllegalArgumentException}s into their own surface's error
 * codes.
 */
public final class RowTokens {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private RowTokens() {
    }

    /**
     * The token of {@code row} over the declared {@code columns}, in the order given.
     *
     * @throws IllegalArgumentException when a key component is null, absent or blank — a row
     *         without its declared identity has no token
     */
    public static String encode(Map<String, Object> row, List<String> columns) {
        StringBuilder token = new StringBuilder();
        for (String column : columns) {
            Object value = JoinKeys.value(row.get(column));
            if (value == null || String.valueOf(value).isEmpty()) {
                throw new IllegalArgumentException("key column '" + column
                        + "' is null, absent or blank in a result row");
            }
            if (!token.isEmpty()) {
                token.append('.');
            }
            token.append(ENCODER.encodeToString(
                    String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        }
        return token.toString();
    }

    /**
     * The canonical key values a token carries, in declaration order, validated against the
     * declared arity. The inverse of {@link #encode}.
     *
     * @throws IllegalArgumentException when the token is malformed or its arity differs from
     *         {@code columns}
     */
    public static List<String> decode(String token, List<String> columns) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("empty row token");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != columns.size()) {
            throw new IllegalArgumentException("row token carries " + parts.length
                    + " value(s), the declared key " + columns + " has " + columns.size());
        }
        List<String> values = new ArrayList<>(parts.length);
        for (String part : parts) {
            try {
                values.add(new String(DECODER.decode(part), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("malformed row token part", ex);
            }
        }
        return values;
    }
}
