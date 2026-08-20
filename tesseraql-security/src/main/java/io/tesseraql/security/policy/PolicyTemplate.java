package io.tesseraql.security.policy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A route policy id that resolves an atom from the request's own path
 * (docs/access-governance.md structural decision 7).
 *
 * <p>A framework surface addressed to one application checks that application's atom:
 * {@code policy: tql.iam.write.{path.name}} on {@code /admin/applications/{name}/roles/assign}
 * refuses anyone who does not administer the application the URL names. Without it a route's
 * policy is one fixed atom, so a per-application grant can never be the thing a route checks —
 * the delegated administrator is refused at the route before any containment runs.
 *
 * <p><b>The value comes off the URL, not off a header.</b> The router does publish its path
 * parameters as message headers, and reading them would be the shorter path — but a form body
 * publishes its fields the same way, so a field named after the path parameter overwrites it. A
 * gate built on that header would resolve its atom from the caller's own body, which is
 * precisely the input this design refuses to build a gate from. So the route's URL template
 * travels with the policy and the segments are matched positionally against the request path.
 *
 * <p>The match aligns from the <em>end</em>, so a deployment served under a base path resolves
 * the same atoms as one served at the root.
 */
public final class PolicyTemplate {

    /** {@code {name}} — a placeholder in the policy id or in the URL template. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    /**
     * What a resolved value may be: one atom segment. The exclusions matter more than the
     * inclusions — an asterisk in the path would resolve to the terminal wildcard, the grant
     * that delegates every application, and a dot would let a request forge a neighbouring
     * atom out of the segment it was given.
     */
    private static final Pattern SEGMENT = Pattern.compile("[^.*/%\\s\\p{Cntrl}]+");

    private PolicyTemplate() {
    }

    /** Whether {@code policyId} resolves from the request rather than naming one fixed atom. */
    public static boolean isTemplate(String policyId) {
        return policyId != null && PLACEHOLDER.matcher(policyId).find();
    }

    /**
     * The atom this request checks, or {@code null} when the request cannot name one.
     *
     * <p>A null answer is the caller's cue to refuse with the ordinary forbidden code rather
     * than to fail: a path that names no usable segment is a request that named no application,
     * which is a denial, not a server fault.
     *
     * @param policyId    the compiled template, e.g. {@code tql.iam.write.{name}}
     * @param pathTemplate the route's own URL template, e.g.
     *                     {@code /_tesseraql/admin/applications/{name}/roles/assign}
     * @param requestPath the path of the request being authorized
     */
    public static String resolve(String policyId, String pathTemplate, String requestPath) {
        if (policyId == null) {
            return null;
        }
        Map<String, String> values = pathValues(pathTemplate, requestPath);
        Matcher matcher = PLACEHOLDER.matcher(policyId);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String resolved = values.get(matcher.group(1));
            if (resolved == null || !SEGMENT.matcher(resolved).matches()) {
                return null;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * The request's value for each {@code {name}} in the template, matched positionally from
     * the end of both paths so a base-path prefix on the request is simply ignored.
     *
     * <p>A literal segment that does not match is an empty answer rather than a partial one:
     * the request did not come through this template, so nothing it carries is that template's
     * parameter.
     */
    private static Map<String, String> pathValues(String pathTemplate, String requestPath) {
        Map<String, String> values = new LinkedHashMap<>();
        if (pathTemplate == null || requestPath == null) {
            return values;
        }
        String[] template = segments(pathTemplate);
        String[] actual = segments(stripQuery(requestPath));
        if (template.length == 0 || actual.length < template.length) {
            return values;
        }
        int offset = actual.length - template.length;
        for (int i = 0; i < template.length; i++) {
            Matcher placeholder = PLACEHOLDER.matcher(template[i]);
            if (placeholder.matches()) {
                values.put(placeholder.group(1), decode(actual[offset + i]));
            } else if (!template[i].equals(actual[offset + i])) {
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
     * A percent-triplet in the path is decoded before it is checked, so an escaped separator is
     * caught by {@link #SEGMENT} rather than smuggled through as an opaque string.
     */
    private static String decode(String segment) {
        try {
            return java.net.URLDecoder.decode(segment,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return segment;
        }
    }
}
