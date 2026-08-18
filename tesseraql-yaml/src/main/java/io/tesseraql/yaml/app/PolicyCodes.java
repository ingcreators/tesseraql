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
     * {@code tql.} mark.
     *
     * <p>Reported at lint and refused at boot, like the name rule itself, so the fence holds for
     * a configuration that never ran through the linter. It binds the codes an application's
     * declared policies check; grants are untouched — a deployment may hand a service client or a
     * role any code, framework atoms included.
     */
    public static final TqlErrorCode OUTSIDE_NAMESPACE = new TqlErrorCode(TqlDomain.YAML, 1406);

    private PolicyCodes() {
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
