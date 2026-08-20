package io.tesseraql.yaml.app;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;

/**
 * The namespace fence for an application's own permission codes
 * (docs/stack-shells.md structural decision 1).
 *
 * <p>Every permission code an application's policies reference must begin with the application's
 * own name as its first dotted segment — {@code orders.approve}, not {@code approve} — so two
 * applications both inventing {@code approve} cannot silently share one grant in the identity
 * store. Disjointness from the framework is the mark's job: the framework's atoms all live under
 * {@code tql.} ({@code tql.ops.view.<name>}, {@code tql.app.use.<name>}, …), exactly as its
 * addresses live under {@code /_tesseraql/}, so a code under the mark is refused too. The prefix
 * is explicit, not auto-applied: silently rewriting codes would make the store show strings the
 * author never wrote and break every grep.
 *
 * <p>A concept two applications share (one approval authority across interlocking apps) is
 * expressed where sharing lives — a <em>role</em> bundling {@code orders.approve} and
 * {@code billing.approve} — which is declarations-never-share applied to authorization.
 */
public final class PolicyCodes {

    /**
     * TQL-YAML-1406: a policy rule references a permission code outside the application's own
     * namespace — it does not begin with the application's name, or it sits under the framework's
     * {@code tql.} mark — or the configuration declares a policy <em>id</em> under the mark,
     * which would shadow the framework's synthesized atom check (a route may reference a
     * {@code tql.*} policy id; a configuration may not re-declare one).
     *
     * <p>Reported at lint and refused at boot, like the name rule itself, so the fence holds for
     * a configuration that never ran through the linter. It binds the codes an application's
     * declared policies check; grants are untouched — a deployment may hand a service client or a
     * role any code, framework atoms included.
     */
    public static final TqlErrorCode OUTSIDE_NAMESPACE = new TqlErrorCode(TqlDomain.YAML, 1406);

    /**
     * TQL-YAML-1409: a route's {@code policy:} resolves an atom from the request, but not from
     * this route's own path — it interpolates something other than {@code path.<name>}, names a
     * path parameter the route does not declare, or sits outside the framework's {@code tql.}
     * mark where nothing is synthesized to resolve into.
     *
     * <p>Reported at lint and refused at boot, like the namespace fence above, because a policy
     * that cannot resolve is a route with no working gate — and the failure would otherwise
     * surface as a puzzling 403 at request time rather than as a refusal at the source.
     */
    public static final TqlErrorCode TEMPLATE_UNRESOLVABLE = new TqlErrorCode(TqlDomain.YAML, 1409);

    /** {@code {path.name}} inside a policy id — the one interpolation a policy may carry. */
    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern
            .compile("\\{([^{}]*)}");

    private PolicyCodes() {
    }

    /**
     * Why {@code policy} cannot resolve on a route declaring {@code pathParams}, or {@code null}
     * when it can — shared by the boot refusal and the lint rule, so the two read identically.
     *
     * <p>A policy id may interpolate a path parameter so a surface addressed to one application
     * checks that application's atom ({@code tql.iam.write.{path.name}},
     * docs/access-governance.md structural decision 7). Three things are refused. Anything but
     * {@code path.<name>}, because a policy resolves from the addressed resource and never from
     * a query string or a body the caller shapes freely. A name the route's own path does not
     * declare, because it would resolve to nothing on every request. And a template outside the
     * {@code tql.} mark, because only an atom id is synthesized from the granted code — a
     * declared policy is a fixed name, so there is nothing for an interpolated one to find.
     */
    public static String templateViolation(String policy, java.util.List<String> pathParams) {
        if (policy == null) {
            return null;
        }
        java.util.regex.Matcher matcher = PLACEHOLDER.matcher(policy);
        boolean templated = false;
        while (matcher.find()) {
            templated = true;
            String reference = matcher.group(1);
            if (!reference.startsWith("path.")) {
                return "policy '" + policy + "' interpolates '" + reference + "' — a policy"
                        + " resolves from the route's own path and nothing else, so only"
                        + " {path.<name>} is available. A query or body value is the caller's"
                        + " to shape, which is not what a gate may be built from.";
            }
            String name = reference.substring("path.".length());
            if (!pathParams.contains(name)) {
                return "policy '" + policy + "' interpolates {path." + name + "}, but this"
                        + " route's path declares " + pathParams + " — the reference would"
                        + " resolve to nothing on every request.";
            }
        }
        if (templated && !policy.startsWith("tql.")) {
            return "policy '" + policy + "' interpolates a path parameter, but only a framework"
                    + " atom under tql. is synthesized from the granted code itself. A declared"
                    + " policy is a fixed id, so an interpolated one names no policy at all.";
        }
        return null;
    }

    /**
     * Why {@code id} cannot be one of the application's own policy <em>ids</em>, or {@code null}
     * when it can. Policy ids stay free with one exception: an id under the framework's
     * {@code tql.} mark resolves to the synthesized atom policy — {@code policy:
     * tql.iam.admin.view} checks that granted atom with no declaration behind it
     * (docs/stack-shells.md structural decision 1) — so a user-declared policy under the mark
     * would shadow the framework's meaning and is refused instead.
     */
    public static String idViolation(String id) {
        if (id.equals("tql") || id.startsWith("tql.")) {
            return "policy id '" + id + "' sits under the framework's own mark — a tql.* policy"
                    + " id is the framework's atom check, synthesized from the granted atom"
                    + " itself (a route may reference it; a configuration may not re-declare"
                    + " it). Declare your own policies outside tql.";
        }
        return null;
    }

    /**
     * Why {@code code} cannot be one of the application's own permission codes, or {@code null}
     * when it can — shared by the boot refusal and the lint rule, so the two read identically.
     */
    public static String violation(String appName, String code) {
        if (code.equals("tql") || code.startsWith("tql.")) {
            return "permission code '" + code + "' sits under the framework's own mark — tql.* is"
                    + " the framework's atom vocabulary (docs/stack-shells.md), granted to"
                    + " principals but never re-declared as an application's policy code. An"
                    + " application's own codes begin with its own name: '" + appName
                    + ".<what>'.";
        }
        if (!code.startsWith(appName + ".") || code.length() == appName.length() + 1) {
            return "permission code '" + code + "' does not begin with this application's own"
                    + " name — codes are '" + appName + ".<what>' (e.g. '" + appName
                    + ".approve'), so two applications cannot silently share one grant. An"
                    + " authority genuinely shared across applications is a role bundling each"
                    + " application's own code.";
        }
        return null;
    }
}
