package io.tesseraql.security.session;

/**
 * The {@code Set-Cookie} header carrying a browser session (docs/base-path.md decision 4).
 *
 * <p>Its {@code Path} is not derivable from the application's base path, because two correct
 * answers conflict: a standalone application behind a proxy should scope its cookie to its own
 * prefix, and a shared suite <em>must</em> scope it to {@code /}, that being what makes one
 * sign-in reach every application in the suite. The value is therefore supplied by whatever
 * starts the runtime, and read here rather than assembled at each of the places that issue one —
 * there were seven, in five modules, agreeing by copy.
 *
 * <p>{@code Secure} is deliberately absent: TLS terminates at the deployment edge
 * (docs/deployment.md), which is also where the flag belongs.
 */
public final class SessionCookie {

    private SessionCookie() {
    }

    /** The cookie that establishes a session. */
    public static String issue(String name, String sessionId, String path) {
        return name + "=" + sessionId + "; Path=" + path(path) + "; HttpOnly; SameSite=Lax";
    }

    /** The cookie that ends one: same name and path, emptied and expired. */
    public static String expire(String name, String path) {
        return name + "=; Path=" + path(path) + "; HttpOnly; SameSite=Lax; Max-Age=0";
    }

    /**
     * A browser matches {@code Path} by prefix and drops a cookie whose path it cannot match, so
     * an absent or malformed value falls back to the origin root rather than issuing a cookie
     * that is silently never sent back.
     */
    private static String path(String path) {
        return path == null || path.isBlank() || !path.startsWith("/") ? "/" : path;
    }
}
