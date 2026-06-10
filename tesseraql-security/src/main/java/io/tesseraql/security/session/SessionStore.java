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

    void invalidate(String sessionId);

    String cookieName();
}
