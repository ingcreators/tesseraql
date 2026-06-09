package io.tesseraql.security.session;

import io.tesseraql.security.Principal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory browser session store (design ch. 11.2). A login flow creates a session for an
 * authenticated principal; the session id is carried in the configured cookie, and each session
 * holds a CSRF token (design ch. 11.3).
 *
 * <p>This first implementation is in-memory and process-local; a durable, clustered store and
 * idle/absolute timeouts arrive with the full session lifecycle in a later phase.
 */
public final class SessionStore {

    public static final String DEFAULT_COOKIE_NAME = "tesseraql_sid";

    /** A browser session: the authenticated principal and its CSRF token. */
    public record Session(Principal principal, String csrfToken) {
    }

    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final String cookieName;

    public SessionStore() {
        this(DEFAULT_COOKIE_NAME);
    }

    public SessionStore(String cookieName) {
        this.cookieName = cookieName == null || cookieName.isBlank()
                ? DEFAULT_COOKIE_NAME : cookieName;
    }

    /** Creates a session for the principal and returns its id. */
    public String create(Principal principal) {
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Session(principal, UUID.randomUUID().toString()));
        return id;
    }

    /** Returns the session for an id, or {@code null} if unknown. */
    public Session session(String sessionId) {
        return sessionId == null ? null : sessions.get(sessionId);
    }

    /** Returns the principal for a session id, or {@code null} if unknown. */
    public Principal get(String sessionId) {
        Session session = session(sessionId);
        return session == null ? null : session.principal();
    }

    /** Returns the CSRF token for a session id, or {@code null} if unknown. */
    public String csrfToken(String sessionId) {
        Session session = session(sessionId);
        return session == null ? null : session.csrfToken();
    }

    public void invalidate(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    public String cookieName() {
        return cookieName;
    }
}
