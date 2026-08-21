package io.tesseraql.camel;

/**
 * The exchange headers {@code camel-platform-http-vertx} puts a request's peer addresses on
 * (docs/http-edge.md decision 1).
 *
 * <p>Restated here rather than imported because the runtime now builds the same exchange without
 * that consumer, and a surface two producers must agree on is worth naming once. The names are
 * the component's own: changing them would change what a request looks like to a route, which is
 * why they are constants and not literals at the two call sites.
 */
public final class PlatformHttpHeaders {

    /** The address the connection arrived on. */
    public static final String LOCAL_ADDRESS = "CamelVertxPlatformHttpLocalAddress";

    /**
     * The peer's address — what a role's network condition and the session record resolve
     * against, which is why the runtime's own edge has to set it too.
     */
    public static final String REMOTE_ADDRESS = "CamelVertxPlatformHttpRemoteAddress";

    private PlatformHttpHeaders() {
    }
}
