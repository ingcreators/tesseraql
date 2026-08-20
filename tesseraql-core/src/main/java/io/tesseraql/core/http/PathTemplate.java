package io.tesseraql.core.http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A route's URL template read against a request's path: {@code /users/{id}} over
 * {@code /users/u1} answers {@code id=u1}.
 *
 * <p>It exists because a path parameter's value must come from the path. The HTTP transport
 * publishes path parameters as message headers, but it publishes query parameters and
 * form-body fields there too, and under the same names: a query parameter sharing a path
 * parameter's name arrives joined with it, and a body field of that name replaces it. Reading
 * the header gives a value the caller can steer or spoil, and a route that says {@code path.id}
 * means the URL rather than whatever else the request happened to carry.
 *
 * <p>The match aligns from the <em>end</em> of both paths, so a deployment served under a base
 * path resolves the same parameters as one served at the root (docs/base-path.md). A literal
 * segment that does not match answers nothing at all rather than a partial map — the request
 * did not come through this template, so nothing it carries is that template's parameter.
 */
public final class PathTemplate {

    /** {@code {name}} — one whole segment of the template. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    private PathTemplate() {
    }

    /** Whether {@code template} declares any {@code {name}} parameter. */
    public static boolean parameterized(String template) {
        return template != null && PLACEHOLDER.matcher(template).find();
    }

    /**
     * The request's value for each {@code {name}} the template declares, percent-decoded.
     *
     * @param template    the route's URL template, e.g. {@code /users/{id}/roles}
     * @param requestPath the request's path; a query string, if present, is ignored
     * @return the values, or an empty map when the request did not come through this template
     */
    public static Map<String, String> values(String template, String requestPath) {
        Map<String, String> values = new LinkedHashMap<>();
        if (template == null || requestPath == null) {
            return values;
        }
        String[] declared = segments(template);
        String[] actual = segments(stripQuery(requestPath));
        if (declared.length == 0 || actual.length < declared.length) {
            return values;
        }
        int offset = actual.length - declared.length;
        for (int i = 0; i < declared.length; i++) {
            Matcher placeholder = PLACEHOLDER.matcher(declared[i]);
            if (placeholder.matches()) {
                values.put(placeholder.group(1), decode(actual[offset + i]));
            } else if (!declared[i].equals(actual[offset + i])) {
                return Map.of();
            }
        }
        return values;
    }

    private static String[] segments(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        return trimmed.isEmpty() ? new String[0] : trimmed.split("/", -1);
    }

    private static String stripQuery(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    /**
     * A percent-triplet is decoded once. The router already decoded the path it matched
     * against, so this reads the same characters the route saw rather than a second round.
     */
    private static String decode(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;
        }
        try {
            return java.net.URLDecoder.decode(segment, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return segment;
        }
    }
}
