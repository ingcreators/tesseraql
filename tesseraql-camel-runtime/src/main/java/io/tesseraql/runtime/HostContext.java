package io.tesseraql.runtime;

/**
 * The settings a host decides on behalf of the runtimes it starts, because only a host can decide
 * them correctly (docs/stack-architecture.md decision 16).
 *
 * <p>The rule that selects what belongs here: <b>a setting belongs to the host when only the host
 * can know it, or when divergence between applications fails silently.</b> {@link
 * io.tesseraql.camel.CookiePath} is the case that established it — a stack shares one sign-in, so
 * its cookie has to reach every application, and an operator setting that per application gets
 * either a silently unshared stack or a session offered to every neighbour. Neither announces
 * itself.
 *
 * <p>A record rather than more positional arguments, for the reason decision 16 gives: a value in
 * position four says nothing at the call site about what it is. The fields decision 16 named as
 * arriving once a host had somewhere to read its own settings from arrive here from
 * {@code tesseraql-stack.yml} (decision 22): the framework datasource — one pool the host builds
 * and every runtime rides, so signing in carries — and the external origin. The token issuer's
 * key set joins them with the authorization server's slice.
 *
 * @param basePath            the prefix the application is served under, from its catalogue entry
 *                            (docs/base-path.md decision 5). {@code ""} is the origin root, and is
 *                            a host answer like any other; {@code null} means no host is speaking,
 *                            so the application's own {@code tesseraql.http.basePath} stands
 * @param cookiePath          the {@code Path} session cookies are issued with (docs/base-path.md
 *                            decision 4). {@code null} means the standalone answer: the
 *                            application's own base path, so its cookie is not offered to whatever
 *                            else lives on that origin
 * @param externalOrigin      the origin the stack is reached at from outside, from
 *                            {@code tesseraql-stack.yml}; {@code null} until the stack declares
 *                            it — it is required only when something reads it (MCP resource
 *                            metadata, the token issuer), never defaulted by a host that cannot
 *                            know it
 * @param frameworkDataSource the one pool the stack's framework state rides, built by the host
 *                            from the stack file's coordinate and owned by the host; {@code null}
 *                            means the stack supplies none and each runtime resolves its own
 *                            {@code tesseraql.framework.datasource}
 * @param mainDataSourceOverride the coordinate this application's {@code main} pool is built
 *                            from instead of its configuration — the development loop's
 *                            {@code --embedded-db}, carrying the application's own declared
 *                            query string so its {@code currentSchema} isolation survives
 *                            (docs/cli-surface.md decision 4b); {@code null} for the ordinary
 *                            case, the application's own declaration
 * @param stackMembers        the stack's member list, set only on the stack surface runtime's
 *                            context so the portal and the ops shell can list them
 *                            (docs/root-portal.md, docs/stack-shells.md); {@code null} for every
 *                            member runtime — an application cannot see its siblings, which keeps
 *                            docs/stack-architecture.md decision 26's no-shared-declarations rule
 *                            untouched
 * @param memberOrigins       the live member-origin lookup (name and slot &rarr; internal port),
 *                            set beside {@link #stackMembers} only on the surface runtime's
 *                            context so the ops shell's delegated calls resolve ports through the
 *                            host's live slots — which stays correct across replaces, exactly as
 *                            the relay's per-request resolution does (docs/stack-shells.md
 *                            structural decision 2); {@code null} wherever {@code stackMembers}
 *                            is
 * @param extraModules        the development loop's {@code --modules} directory, composed onto
 *                            every member runtime's own module loader (docs/module-scope.md) —
 *                            an override, not a declaration, which is why it is the one
 *                            deliberately stack-wide module input; {@code null} in production
 *                            and on the surface runtime
 * @param surfaceSecurity     the stack file's {@code security:} subtree, set only on the surface
 *                            runtime's context and grafted onto its configuration as
 *                            {@code tesseraql.security.*} — the origin's token issuing and the
 *                            deploy endpoint's bearer validation ride it (docs/stack-shells.md,
 *                            the deploy surface); {@code null} everywhere else, and members keep
 *                            their own declared JWT configuration
 * @param deployPen           the host's narrow deploy pen, set only on the surface runtime's
 *                            context so the authenticated deploy endpoint can write the install
 *                            root's intent through the host that owns it; {@code null} everywhere
 *                            else — no pen, no endpoint
 * @param workshop            whether the workshop may exist in this stack (docs/studio-shell.md
 *                            structural decision 1): the development loop is running AND the
 *                            stack is source trees, not an install root. Only the host can know
 *                            either fact; the runtime binds it as a topology bean, and the
 *                            workshop extension keys its faces on it. Always {@code false}
 *                            under {@code host} — no configuration turns Studio on there
 * @param vertx               the one Vert.x instance every runtime in this host shares
 *                            (docs/http-threading.md decision 4), built and owned by the host.
 *                            Only the host can size it: a runtime that built its own got a worker
 *                            pool and an event-loop pool per application, so the process thread
 *                            count was a function of how many applications were installed rather
 *                            than of anything an operator chose — the divergence-fails-silently
 *                            case this record exists for. {@code null} standalone, where the
 *                            runtime builds its own from its own configuration
 */
public record HostContext(String basePath, String cookiePath, String externalOrigin,
        javax.sql.DataSource frameworkDataSource,
        DataSources.MainDatasourceOverride mainDataSourceOverride,
        java.util.List<io.tesseraql.operations.app.InstalledApp> stackMembers,
        MemberOrigins memberOrigins,
        java.io.File extraModules,
        java.util.Map<String, Object> surfaceSecurity,
        DeployPen deployPen,
        java.util.Map<String, Object> stackIssuerJwt,
        boolean workshop,
        io.vertx.core.Vertx vertx) {

    /**
     * The host's live member-origin lookup: which internal port answers for a member's stable or
     * canary slot right now. Reads the host's live slot state per call, so a shell that resolved
     * a port yesterday cannot delegate to a retired runtime today.
     */
    public interface MemberOrigins {

        /**
         * The internal port serving {@code member}'s stable slot — or its staged canary when
         * {@code canary} — throwing the host's TQL-APP-4040 for a member or slot that does not
         * exist.
         */
        int port(String member, boolean canary);

        /** Whether {@code member} has a staged canary slot right now. */
        boolean hasCanary(String member);

        /**
         * The version {@code member}'s stable slot serves right now, or {@code null} when
         * unknown. Live like {@link #port}, because the boot-time member list goes stale the
         * moment a deploy replaces a runtime — the deploy page's table reads this.
         */
        default String version(String member) {
            return null;
        }
    }

    /**
     * The host's deploy pen: what the surface runtime's authenticated deploy endpoint may do to
     * the install root, and nothing else (docs/stack-shells.md, the deploy surface). One method,
     * because deploying IS writing intent — the reconciler stays the one mechanism that moves a
     * runtime, and every refusal the CLI's local mode meets refuses here identically, before
     * anything is written.
     */
    public interface DeployPen {

        /**
         * Verifies, preflights and writes the intent for one package — {@code AppUpgrader}'s
         * whole lifecycle, on the install root the host serves. {@code weightPercent} applies
         * only with {@code canary}; {@code sha256} is verified before the preflight when given.
         */
        io.tesseraql.operations.app.AppUpgrader.UpgradeResult deploy(java.nio.file.Path tqlapp,
                boolean canary, Integer weightPercent, String sha256);
    }

    /**
     * The stack's answers, before any one application's prefix is stamped onto them: one sign-in
     * across one origin.
     *
     * <p>Every field here is the host's. {@link #basePath} is simply the one that differs per
     * application, so it is left unset until {@link #forApplication} supplies the address the
     * catalogue declared for the runtime being started.
     */
    public static HostContext stack() {
        return new HostContext(null, "/", null, null, null, null, null, null, null, null,
                null, false, null);
    }

    /** These settings, for the application the catalogue addresses at {@code basePath}. */
    public HostContext forApplication(String basePath) {
        return forApplication(basePath, null);
    }

    /** As {@link #forApplication(String)}, with the development loop's main-pool coordinate. */
    HostContext forApplication(String basePath,
            DataSources.MainDatasourceOverride mainDataSourceOverride) {
        return new HostContext(basePath, cookiePath, externalOrigin, frameworkDataSource,
                mainDataSourceOverride, null, null, extraModules, null, null, stackIssuerJwt,
                workshop, vertx);
    }

    /**
     * These settings, for the stack surface runtime: the origin root, the framework coordinate
     * as its {@code main}, the member list the portal exists to show (docs/root-portal.md), and
     * the live member-origin lookup the ops shell delegates through (docs/stack-shells.md).
     * The surface carries no {@code --modules} override — it serves the framework's own
     * declarations, not the stack's.
     */
    HostContext forSurface(DataSources.MainDatasourceOverride mainDataSourceOverride,
            java.util.List<io.tesseraql.operations.app.InstalledApp> stackMembers,
            MemberOrigins memberOrigins,
            java.util.Map<String, Object> surfaceSecurity,
            DeployPen deployPen) {
        return new HostContext("", cookiePath, externalOrigin, frameworkDataSource,
                mainDataSourceOverride, java.util.List.copyOf(stackMembers), memberOrigins, null,
                surfaceSecurity, deployPen, stackIssuerJwt, workshop, vertx);
    }

    /** These settings, carrying what the stack's own file declared (decision 22). */
    HostContext withStackSettings(String externalOrigin,
            javax.sql.DataSource frameworkDataSource) {
        return new HostContext(basePath, cookiePath, externalOrigin, frameworkDataSource,
                mainDataSourceOverride, stackMembers, memberOrigins, extraModules,
                surfaceSecurity, deployPen, stackIssuerJwt, workshop, vertx);
    }

    /**
     * These settings, carrying the host's workshop verdict (docs/studio-shell.md structural
     * decision 1): {@code dev} over source trees and nothing else.
     */
    HostContext withWorkshop(boolean workshop) {
        return new HostContext(basePath, cookiePath, externalOrigin, frameworkDataSource,
                mainDataSourceOverride, stackMembers, memberOrigins, extraModules,
                surfaceSecurity, deployPen, stackIssuerJwt, workshop, vertx);
    }

    /** These settings, carrying the development loop's {@code --modules} override. */
    HostContext withExtraModules(java.io.File extraModules) {
        return new HostContext(basePath, cookiePath, externalOrigin, frameworkDataSource,
                mainDataSourceOverride, stackMembers, memberOrigins, extraModules,
                surfaceSecurity, deployPen, stackIssuerJwt, workshop, vertx);
    }

    /**
     * These settings, carrying the host's one Vert.x instance (docs/http-threading.md decision 4).
     *
     * <p>Every runtime the host starts rides it, so the process has one worker pool and one
     * event-loop pool however many applications are installed. The host owns it: a runtime closes
     * only an instance it built itself, which is what lets one application be stopped or replaced
     * without taking the transport out from under its neighbours.
     */
    HostContext withVertx(io.vertx.core.Vertx vertx) {
        return new HostContext(basePath, cookiePath, externalOrigin, frameworkDataSource,
                mainDataSourceOverride, stackMembers, memberOrigins, extraModules,
                surfaceSecurity, deployPen, stackIssuerJwt, workshop, vertx);
    }

    /**
     * These settings, carrying the derived stack-issuer validation block (decision 9 of
     * docs/token-issuance.md) every runtime in the stack applies.
     */
    HostContext withStackIssuer(java.util.Map<String, Object> stackIssuerJwt) {
        return new HostContext(basePath, cookiePath, externalOrigin, frameworkDataSource,
                mainDataSourceOverride, stackMembers, memberOrigins, extraModules,
                surfaceSecurity, deployPen, stackIssuerJwt, workshop, vertx);
    }
}
