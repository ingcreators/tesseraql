package io.tesseraql.pipeline;

/**
 * The exchange headers a request's peer addresses ride on (docs/http-edge.md decision 1).
 *
 * <p>These began as {@code camel-platform-http-vertx}'s, which is what this class is named after.
 * The runtime builds the same exchange without that consumer now, and the names became the
 * framework's own with the rest of the header vocabulary — so a surface two producers must agree
 * on is named once, here. Changing them would change what a request looks like to a route, which
 * is why they are constants and not literals at the two call sites.
 */
public final class PlatformHttpHeaders {

    /** The address the connection arrived on. */
    public static final String LOCAL_ADDRESS = "tql.http.localAddress";

    /**
     * The peer's address — what a role's network condition and the session record resolve
     * against, which is why the runtime's own edge has to set it too.
     */
    public static final String REMOTE_ADDRESS = "tql.http.remoteAddress";

    private PlatformHttpHeaders() {
    }
}
