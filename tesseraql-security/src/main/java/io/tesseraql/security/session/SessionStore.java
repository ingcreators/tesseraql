package io.tesseraql.security.session;

import io.tesseraql.security.Principal;

/**
 * Browser session store (design ch. 11.2). A login flow creates a session for an authenticated
 * principal; the session id is carried in the configured cookie, and each session holds a CSRF
 * token (design ch. 11.3).
 *
 * <p>Implementations: {@link InMemorySessionStore} (process-local, the default) and
 * {@link JdbcSessionStore} (shared across runtime nodes).
 */
public interface SessionStore {

    String DEFAULT_COOKIE_NAME = "tesseraql_sid";

    /** A browser session: the authenticated principal and its CSRF token. */
    record Session(Principal principal, String csrfToken) {
    }

    /** Creates a session for the principal and returns its id. */
    String create(Principal principal);

    /** Returns the session for an id, or {@code null} if unknown or expired. */
    Session session(String sessionId);

    /** Returns the principal for a session id, or {@code null} if unknown. */
    default Principal get(String sessionId) {
        Session session = session(sessionId);
        return session == null ? null : session.principal();
    }

    /** Returns the CSRF token for a session id, or {@code null} if unknown. */
    default String csrfToken(String sessionId) {
        Session session = session(sessionId);
        return session == null ? null : session.csrfToken();
    }

    /**
     * Returns the CSRF token for the session named by a {@code Cookie} header, or {@code null} when
     * no session resolves. Lets a request pipeline publish the token (for example as a
     * {@code <meta name="csrf-token">} tag) without parsing cookies itself.
     */
    default String csrfTokenFromCookie(String cookieHeader) {
        return csrfToken(Cookies.value(cookieHeader, cookieName()));
    }

    void invalidate(String sessionId);

    /** Invalidates the session named by a {@code Cookie} header, if one resolves (logout). */
    default void invalidateFromCookie(String cookieHeader) {
        String sessionId = Cookies.value(cookieHeader, cookieName());
        if (sessionId != null) {
            invalidate(sessionId);
        }
    }

    /** The session id carried by a {@code Cookie} header, or {@code null} when absent. */
    default String sessionIdFromCookie(String cookieHeader) {
        return Cookies.value(cookieHeader, cookieName());
    }

    /**
     * An active session for the account surface's self-service list (roadmap Phase 48).
     * Timestamps may be {@code null} where a store does not track them (in-memory); the id is
     * for keep-checks only and must never be rendered.
     */
    record ActiveSession(String sessionId, java.time.Instant createdAt,
            java.time.Instant expiresAt) {
    }

    /**
     * The subject's active sessions, newest first. Default: empty — a custom store that never
     * learned subjects simply renders no session list. Rows created before the store tracked
     * subjects are not listed; they age out at their expiry.
     */
    default java.util.List<ActiveSession> sessionsFor(String subject) {
        return java.util.List.of();
    }

    /** Invalidates every session of the subject except the one to keep (sign out others). */
    default void invalidateOthersFor(String subject, String keepSessionId) {
    }

    String cookieName();
}
