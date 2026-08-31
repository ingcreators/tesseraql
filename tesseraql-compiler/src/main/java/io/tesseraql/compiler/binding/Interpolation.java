package io.tesseraql.compiler.binding;

import io.tesseraql.core.expr.EvaluationContext;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code {expression}} placeholders against the execution context — the grammar shared
 * by response header values (such as {@code HX-Trigger}), redirect locations and view links.
 * Shared by {@link HtmlResponseRenderer} and {@link ViewBinding} — interpolation is not a
 * renderer concern, so it lives outside the renderers.
 */
final class Interpolation {

    /** A {@code {expression}} placeholder in a header value, resolved like the redirect location. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    private Interpolation() {
    }

    /** Resolves {@code {expression}} placeholders in a header value (recursively into maps/lists). */
    @SuppressWarnings("unchecked")
    static Object interpolate(Object value, EvaluationContext evaluation) {
        if (value instanceof String string) {
            return interpolateString(string, evaluation);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> out.put(k, interpolate(v, evaluation)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(element -> interpolate(element, evaluation)).toList();
        }
        return value;
    }

    /** Resolves {@code {expression}} placeholders in a single string; unresolved reads as empty. */
    static String interpolateString(String template, EvaluationContext evaluation) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object resolved = evaluation.resolve(Arrays.asList(matcher.group(1).split("\\.")));
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(resolved == null ? "" : String.valueOf(resolved)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Resolves placeholders like {@link #interpolateString}, URL-encoding each resolved value —
     * the variant for templates that are URLs (a view column's {@code link:},
     * docs/list-surface.md decision 3). Only the values are encoded; the template's own
     * separators stay what the author wrote. A value containing {@code /}, {@code ?} or
     * {@code #} used to break the href it was substituted into.
     */
    static String interpolateUrl(String template, EvaluationContext evaluation) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object resolved = evaluation.resolve(Arrays.asList(matcher.group(1).split("\\.")));
            String text = resolved == null ? "" : String.valueOf(resolved);
            String encoded = java.net.URLEncoder
                    .encode(text, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
            matcher.appendReplacement(out, Matcher.quoteReplacement(encoded));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
