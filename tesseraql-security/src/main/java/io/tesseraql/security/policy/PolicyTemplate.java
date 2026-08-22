package io.tesseraql.security.policy;

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
 * <p><b>The value comes off the URL, not off a header</b> ({@code PathTemplate}): the transport
 * publishes path parameters, query parameters and form-body fields all as message headers and
 * all under their own names, so a gate reading one would resolve its atom from input the caller
 * steers. What this class adds on top is the check that the resolved value is one atom segment
 * and nothing else.
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
     * @param policyId   the compiled template, e.g. {@code tql.iam.write.{name}}
     * @param pathValues the request's path parameters as the router matched them — the URL's
     *                   values by construction, which is what this class used to re-derive from
     *                   the URI string when the transport also published them as spoofable
     *                   headers (docs/vertx-native.md decision 2)
     */
    public static String resolve(String policyId, Map<String, String> pathValues) {
        if (policyId == null) {
            return null;
        }
        Map<String, String> values = pathValues;
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

}
