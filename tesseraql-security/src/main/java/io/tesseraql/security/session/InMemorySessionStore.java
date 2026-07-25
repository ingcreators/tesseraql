package io.tesseraql.security.session;

import io.tesseraql.security.Principal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The in-memory, process-local {@link SessionStore} (design ch. 11.2): the default for a single
 * runtime node. Multi-node deployments need sticky sessions, or {@link JdbcSessionStore}.
 *
 * <p>Sessions expire, like the JDBC store's. They did not: {@code session()} was a bare map
 * lookup, nothing pruned, and {@code tesseraql.sessions.ttl} was read only on the JDBC branch —
 * so on the default configuration a session id stayed valid until the process restarted, and the
 * map grew one {@link Principal} per login forever. A stolen cookie outlived every control
 * except an explicit logout.
 */
public final class InMemorySessionStore implements SessionStore {

    /**
     * A ceiling on live sessions, so a login flood inside one TTL window cannot exhaust the
     * heap. Reaching it evicts the oldest, which is the same "the newest caller wins" rule the
     * live-stream registry uses.
     */
    private static final int MAX_SESSIONS = 50_000;

    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    /** Creation instants for the account surface's session list (roadmap Phase 48). */
    private final ConcurrentMap<String, java.time.Instant> created = new ConcurrentHashMap<>();
    private final String cookieName;
    private final java.time.Duration timeToLive;

    public InMemorySessionStore() {
        this(DEFAULT_COOKIE_NAME, null);
    }

    public InMemorySessionStore(String cookieName) {
        this(cookieName, null);
    }

    /** @param timeToLive how long a session stays valid; {@code null} means it never expires */
    public InMemorySessionStore(String cookieName, java.time.Duration timeToLive) {
        this.cookieName = cookieName == null || cookieName.isBlank()
                ? DEFAULT_COOKIE_NAME
                : cookieName;
        this.timeToLive = timeToLive;
    }

    @Override
    public String create(Principal principal) {
        // Prune on write, the same opportunistic sweep JdbcSessionStore does on create.
        prune();
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Session(principal, UUID.randomUUID().toString()));
        created.put(id, java.time.Instant.now());
        return id;
    }

    @Override
    public Session session(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        if (isExpired(sessionId)) {
            invalidate(sessionId);
            return null;
        }
        return sessions.get(sessionId);
    }

    private boolean isExpired(String sessionId) {
        if (timeToLive == null) {
            return false;
        }
        java.time.Instant start = created.get(sessionId);
        return start != null && start.plus(timeToLive).isBefore(java.time.Instant.now());
    }

    /** Drops expired sessions, then the oldest survivors if the cap is still exceeded. */
    private void prune() {
        if (timeToLive != null) {
            java.time.Instant cutoff = java.time.Instant.now().minus(timeToLive);
            created.entrySet().stream()
                    .filter(entry -> entry.getValue().isBefore(cutoff))
                    .map(java.util.Map.Entry::getKey)
                    .toList()
                    .forEach(this::invalidate);
        }
        if (sessions.size() < MAX_SESSIONS) {
            return;
        }
        created.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByValue())
                .limit(Math.max(1, sessions.size() - MAX_SESSIONS + 1))
                .map(java.util.Map.Entry::getKey)
                .toList()
                .forEach(this::invalidate);
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
