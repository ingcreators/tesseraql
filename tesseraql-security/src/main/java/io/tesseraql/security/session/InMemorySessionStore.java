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
    /** Creation instants for the account surface's session list (roadmap Phase 48). */
    private final ConcurrentMap<String, java.time.Instant> created = new ConcurrentHashMap<>();
    private final String cookieName;

    public InMemorySessionStore() {
        this(DEFAULT_COOKIE_NAME);
    }

    public InMemorySessionStore(String cookieName) {
        this.cookieName = cookieName == null || cookieName.isBlank()
                ? DEFAULT_COOKIE_NAME
                : cookieName;
    }

    @Override
    public String create(Principal principal) {
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Session(principal, UUID.randomUUID().toString()));
        created.put(id, java.time.Instant.now());
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
            created.remove(sessionId);
        }
    }

    @Override
    public java.util.List<ActiveSession> sessionsFor(String subject) {
        return sessions.entrySet().stream()
                .filter(entry -> subject != null
                        && subject.equals(entry.getValue().principal().subject()))
                .map(entry -> new ActiveSession(entry.getKey(),
                        created.get(entry.getKey()), null))
                .sorted(java.util.Comparator.comparing(ActiveSession::createdAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();
    }

    @Override
    public void invalidateOthersFor(String subject, String keepSessionId) {
        sessions.entrySet().removeIf(entry -> subject != null
                && subject.equals(entry.getValue().principal().subject())
                && !entry.getKey().equals(keepSessionId));
        created.keySet().retainAll(sessions.keySet());
    }

    @Override
    public String cookieName() {
        return cookieName;
    }
}
