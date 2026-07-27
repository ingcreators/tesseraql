package io.tesseraql.runtime;

import java.util.Map;

/**
 * Every framework-mounted HTTP route that does <em>not</em> carry an authentication step, and why
 * (docs/framework-surface-parity.md).
 *
 * <p>The audit that produced that document found two framework routes shipped without the gate
 * their siblings had — the Studio reload endpoint and the reload stub — and the reason neither was
 * caught is that an absence looks like nothing. Nobody reviews a route that isn't there. So the
 * guard inverts it: {@code FrameworkSurfaceGuardTest} walks the started context and requires every
 * framework HTTP route to either carry an {@code authenticate} step or appear below with a reason.
 * A new unauthenticated surface fails the build until someone writes down why.
 *
 * <p><b>Three states, because there are three.</b> Measuring the running context first is what
 * showed that: a step-based check alone would have called {@code system.logout.others} unprotected,
 * and it is not — it validates the session and the CSRF token inside its processor. Recording that
 * as "public by design" would have been a lie in the registry meant to prevent lies. So a route is
 * gated by a step, gated inside its processor (named here, so the claim is checkable), or public on
 * purpose.
 */
public final class FrameworkSurfaces {

    private FrameworkSurfaces() {
    }

    /**
     * Routes that answer without an authenticated caller, on purpose.
     *
     * <p>The Studio entries are declared {@code auth: public} in the bundled Studio app's own YAML
     * rather than here; they are listed because the guard reads the mounted route, which cannot
     * tell a declared public from a forgotten one.
     */
    public static final Map<String, String> PUBLIC_BY_DESIGN = Map.ofEntries(
            Map.entry("ops.health",
                    "load balancers and deploy tooling; answers a status word, details stay behind"
                            + " the authorized ops API"),
            Map.entry("ops.health.live", "pure liveness; never touches a dependency"),
            Map.entry("ops.health.ready", "readiness roll-up for traffic shedding"),
            Map.entry("tql.assets", "static asset bytes, served before any session exists"),
            Map.entry("system.login", "the endpoint that establishes a session"),
            Map.entry("tql.auth.login", "the login form; pre-authentication by definition"),
            Map.entry("tql.auth.reset", "the password-reset request form; pre-authentication"),
            Map.entry("tql.auth.reset.confirm",
                    "the reset confirmation form; authorized by the emailed token, not a session"),
            Map.entry("tql.auth.invite",
                    "the invitation acceptance form; authorized by the emailed token"),
            Map.entry("tql.studio.home", "declared auth: public in the bundled Studio app"),
            Map.entry("tql.studio.docs.share.route",
                    "a shareable documentation page; declared auth: public in the Studio app"),
            Map.entry("tql.studio.docs.share.table",
                    "a shareable documentation page; declared auth: public in the Studio app"),
            Map.entry("tql.studio.docs.share.coverage",
                    "a shareable documentation page; declared auth: public in the Studio app"));

    /**
     * Routes whose gate lives in their processor rather than in a route step.
     *
     * <p>The value names what enforces it, so the entry can be checked rather than believed. These
     * are the ones a step-based guard would report as unprotected — the false positives that make
     * a guard's failures get waved through.
     */
    public static final Map<String, String> PROCESSOR_ENFORCED = Map.ofEntries(
            Map.entry("system.logout",
                    "LoginRouteBuilder#logout resolves the session from the cookie"),
            Map.entry("system.logout.others",
                    "LoginRouteBuilder#logoutOthers requires a session and validates the CSRF"
                            + " token before invalidating anything"),
            Map.entry("system.logout.device",
                    "LoginRouteBuilder#logoutDevice requires a session and validates the CSRF"
                            + " token; the handle is scoped to the caller's own subject"),
            Map.entry("mcp.endpoint.post",
                    "McpHttpHandler calls McpAuthenticator with the Authorization header"),
            Map.entry("mcp.endpoint.get",
                    "McpHttpHandler calls McpAuthenticator with the Authorization header"),
            Map.entry("mcp.endpoint.delete",
                    "McpHttpHandler calls McpAuthenticator with the Authorization header"));

    /** Whether this route is allowed to answer without an {@code authenticate} step. */
    public static boolean exempt(String routeId) {
        return PUBLIC_BY_DESIGN.containsKey(routeId) || PROCESSOR_ENFORCED.containsKey(routeId);
    }
}
