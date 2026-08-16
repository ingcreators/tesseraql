package io.tesseraql.runtime;

/**
 * The settings a host decides on behalf of the runtimes it starts, because only a host can decide
 * them correctly (docs/suite-architecture.md decision 16).
 *
 * <p>The rule that selects what belongs here: <b>a setting belongs to the host when only the host
 * can know it, or when divergence between applications fails silently.</b> {@link
 * io.tesseraql.camel.CookiePath} is the case that established it — a suite shares one sign-in, so
 * its cookie has to reach every application, and an operator setting that per application gets
 * either a silently unshared suite or a session offered to every neighbour. Neither announces
 * itself.
 *
 * <p>A record rather than more positional arguments, for the reason decision 16 gives: the list was
 * already four and reaches eight as the framework datasource, the external origin, and the
 * authorization server's issuer and key set join it. A value in position four says nothing at the
 * call site about what it is. Those three are not here yet: a host has nowhere to read its own
 * settings from, which decision 16 names as the question to answer before the {@code security}
 * migration hoist. The shape they arrive into is the point of this type.
 *
 * @param basePath   the prefix the application is served under, from its catalogue entry
 *                   (docs/base-path.md decision 5). {@code ""} is the origin root, and is a host
 *                   answer like any other; {@code null} means no host is speaking, so the
 *                   application's own {@code tesseraql.http.basePath} stands
 * @param cookiePath the {@code Path} session cookies are issued with (docs/base-path.md decision
 *                   4). {@code null} means the standalone answer: the application's own base path,
 *                   so its cookie is not offered to whatever else lives on that origin
 */
public record HostContext(String basePath, String cookiePath) {

    /**
     * The suite's answers, before any one application's prefix is stamped onto them: one sign-in
     * across one origin.
     *
     * <p>Every field here is the host's. {@link #basePath} is simply the one that differs per
     * application, so it is left unset until {@link #forApplication} supplies the address the
     * catalogue declared for the runtime being started.
     */
    public static HostContext suite() {
        return new HostContext(null, "/");
    }

    /** These settings, for the application the catalogue addresses at {@code basePath}. */
    public HostContext forApplication(String basePath) {
        return new HostContext(basePath, cookiePath);
    }
}
