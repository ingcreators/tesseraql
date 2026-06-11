package io.tesseraql.security.session;

import io.tesseraql.security.Principal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The in-memory, process-local {@link SessionStore} (design ch. 11.2): the default for a single
 * runtime node. Multi-node deployments need sticky sessions, or {@link JdbcSessionStore}.
 */
public final class InMemorySessionStore implements SessionStore {

    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final String cookieName;

    public InMemorySessionStore() {
        this(DEFAULT_COOKIE_NAME);
    }

    public InMemorySessionStore(String cookieName) {
        this.cookieName = cookieName == null || cookieName.isBlank()
                ? DEFAULT_COOKIE_NAME : cookieName;
    }

    @Override
    public String create(Principal principal) {
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Session(principal, UUID.randomUUID().toString()));
        return id;
    }

    @Override
    public Session session(String sessionId) {
        return sessionId == null ? null : sessions.get(sessionId);
    }

    @Override
    public void invalidate(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    @Override
    public String cookieName() {
        return cookieName;
    }
}
