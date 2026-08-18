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
 *                            context so the portal can list them (docs/root-portal.md);
 *                            {@code null} for every member runtime — an application cannot see
 *                            its siblings, which keeps docs/stack-architecture.md decision 26's
 *                            no-shared-declarations rule untouched
 * @param extraModules        the development loop's {@code --modules} directory, composed onto
 *                            every member runtime's own module loader (docs/module-scope.md) —
 *                            an override, not a declaration, which is why it is the one
 *                            deliberately stack-wide module input; {@code null} in production
 *                            and on the surface runtime
 */
public record HostContext(String basePath, String cookiePath, String externalOrigin,
        javax.sql.DataSource frameworkDataSource,
        DataSources.MainDatasourceOverride mainDataSourceOverride,
        java.util.List<io.tesseraql.operations.app.InstalledApp> stackMembers,
        java.io.File extraModules) {

    /**
     * The stack's answers, before any one application's prefix is stamped onto them: one sign-in
     * across one origin.
     *
     * <p>Every field here is the host's. {@link #basePath} is simply the one that differs per
     * application, so it is left unset until {@link #forApplication} supplies the address the
     * catalogue declared for the runtime being started.
     */
    public static HostContext stack() {
        return new HostContext(null, "/", null, null, null, null, null);
    }

    /** These settings, for the application the catalogue addresses at {@code basePath}. */
    public HostContext forApplication(String basePath) {
        return forApplication(basePath, null);
    }

    /** As {@link #forApplication(String)}, with the development loop's main-pool coordinate. */
    HostContext forApplication(String basePath,
            DataSources.MainDatasourceOverride mainDataSourceOverride) {
        return new HostContext(basePath, cookiePath, externalOrigin, frameworkDataSource,
                mainDataSourceOverride, null, extraModules);
    }

    /**
     * These settings, for the stack surface runtime: the origin root, the framework coordinate
     * as its {@code main}, and the member list the portal exists to show (docs/root-portal.md).
     * The surface carries no {@code --modules} override — it serves the framework's own
     * declarations, not the stack's.
     */
    HostContext forSurface(DataSources.MainDatasourceOverride mainDataSourceOverride,
            java.util.List<io.tesseraql.operations.app.InstalledApp> stackMembers) {
        return new HostContext("", cookiePath, externalOrigin, frameworkDataSource,
                mainDataSourceOverride, java.util.List.copyOf(stackMembers), null);
    }

    /** These settings, carrying what the stack's own file declared (decision 22). */
    HostContext withStackSettings(String externalOrigin,
            javax.sql.DataSource frameworkDataSource) {
        return new HostContext(basePath, cookiePath, externalOrigin, frameworkDataSource,
                mainDataSourceOverride, stackMembers, extraModules);
    }

    /** These settings, carrying the development loop's {@code --modules} override. */
    HostContext withExtraModules(java.io.File extraModules) {
        return new HostContext(basePath, cookiePath, externalOrigin, frameworkDataSource,
                mainDataSourceOverride, stackMembers, extraModules);
    }
}
