package io.tesseraql.compiler.binding;

import io.tesseraql.core.sql.SqlIdentifiers;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wire-safe path-parameter names (docs/unicode-identifiers.md): the declared name is the
 * contract — it is what the route YAML, the SQL binds, and the OpenAPI document say — but
 * the HTTP router (Vert.x, via Java regex named groups) only accepts
 * {@code [A-Za-z][A-Za-z0-9]*} as a parameter name, which excludes {@code {受注番号}} and
 * even {@code {order_id}}. So the mount registers a positional stand-in
 * ({@code {p0}}, {@code {p1}}, …) for any declared name the router cannot carry, and the
 * request binder maps the stand-in back. Wire-safe names pass through untouched, so
 * existing routes see identical headers.
 */
public final class WireNames {

    private static final Pattern WIRE_SAFE = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    private WireNames() {
    }

    /** The name the HTTP router carries for the declared parameter at {@code position}. */
    public static String wireName(String declared, int position) {
        return WIRE_SAFE.matcher(declared).matches() ? declared : "p" + position;
    }

    /**
     * The URL template with every non-wire-safe {@code {name}} replaced by its stand-in.
     * Literal segments stay as declared — the runtime decodes a request's non-ASCII
     * percent-triplets before matching (UnicodePaths), so the decoded template is exactly
     * what the router compares against.
     */
    public static String wirePath(String urlPath) {
        Matcher matcher = SqlIdentifiers.PLACEHOLDER.matcher(urlPath);
        StringBuilder out = new StringBuilder();
        int position = 0;
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    "{" + wireName(matcher.group(1), position++) + "}"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Declared name → wire name, for the declared path parameters in template order. */
    public static Map<String, String> of(List<String> declaredParams) {
        Map<String, String> byName = new LinkedHashMap<>();
        for (int i = 0; i < declaredParams.size(); i++) {
            byName.put(declaredParams.get(i), wireName(declaredParams.get(i), i));
        }
        return byName;
    }
}
