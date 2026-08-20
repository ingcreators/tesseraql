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
 *
 * <p><b>A {@link #PROCESSOR_ENFORCED} entry is probed, not believed</b> (docs/audit-hardening.md
 * slice 2). It was believed until an audit found three entries attesting a gate the runtime never
 * wired: the MCP endpoints claimed "McpHttpHandler calls McpAuthenticator with the Authorization
 * header" while {@code TesseraqlRuntime} constructed that handler with a null authenticator, which
 * makes the handler's whole 401 path dead code. {@link #exempt} is pure map membership, so no guard
 * reading this class could have caught it — the registry asserted something about the framework
 * that was not true, and the assertion was the only evidence for it. The guard now drives an
 * unauthenticated request at every processor-enforced route and requires a refusal, so the claim
 * costs a passing probe rather than a sentence.
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
            Map.entry("tql.studio.docs.share.route",
                    "a shareable documentation page; declared auth: public in the Studio app"),
            Map.entry("tql.studio.docs.share.table",
                    "a shareable documentation page; declared auth: public in the Studio app"),
            Map.entry("tql.studio.docs.share.coverage",
                    "a shareable documentation page; declared auth: public in the Studio app"),
            Map.entry("studio.workshop.public",
                    "the token-authorized share providers' delegation endpoint"
                            + " (docs/studio-shell.md structural decision 2): public by design,"
                            + " like the share pages it answers for — the op must be one of the"
                            + " enumerated PUBLIC rows, and the provider verifies the signed,"
                            + " expiring link itself"),
            // Moved from PROCESSOR_ENFORCED, where they attested a gate that does not run
            // (docs/audit-hardening.md Decision 8). The wording is McpRouteBuilder's own, which
            // has always described the surface accurately; only this registry disagreed.
            Map.entry("mcp.endpoint.post",
                    "each MCP primitive runs its own route security, so there is no transport-level"
                            + " gate: discovery is open and a primitive that declares a policy"
                            + " enforces it on call"),
            Map.entry("mcp.endpoint.get",
                    "the MCP session stream; open for the same reason as the POST endpoint"),
            Map.entry("mcp.endpoint.delete",
                    "ends an MCP session the caller already holds; open for the same reason as the"
                            + " POST endpoint"));

    /**
     * Routes whose gate lives in their processor rather than in a route step.
     *
     * <p>The value names what enforces it, so the entry can be checked rather than believed. These
     * are the ones a step-based guard would report as unprotected — the false positives that make
     * a guard's failures get waved through.
     *
     * <p>Membership here is a claim the guard falsifies: it calls each route with no credentials
     * and requires the refusal the reason promises. An entry whose processor stops enforcing —
     * or never started — fails the build.
     */
    public static final Map<String, String> PROCESSOR_ENFORCED = Map.ofEntries(
            Map.entry("system.logout",
                    "LoginRouteBuilder#logout validates the CSRF token against the session"
                            + " resolved from the cookie, which refuses when there is none"),
            Map.entry("system.logout.others",
                    "LoginRouteBuilder#logoutOthers requires a session and validates the CSRF"
                            + " token before invalidating anything"),
            Map.entry("system.logout.device",
                    "LoginRouteBuilder#logoutDevice requires a session and validates the CSRF"
                            + " token; the handle is scoped to the caller's own subject"),
            Map.entry("system.account.elevate",
                    "LoginRouteBuilder#elevate requires a session and validates the CSRF"
                            + " token; the subject elevated is the session's own, so the"
                            + " request names no target to validate"));

    /** Whether this route is allowed to answer without an {@code authenticate} step. */
    public static boolean exempt(String routeId) {
        return PUBLIC_BY_DESIGN.containsKey(routeId) || PROCESSOR_ENFORCED.containsKey(routeId);
    }
}
