package io.tesseraql.security.session;

import io.tesseraql.security.Principal;
import java.time.Duration;
import java.time.Instant;
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
 *
 * <p>Each session carries its metadata (docs/session-visibility.md): a public handle for row
 * actions, the client facts captured at login, and a last-seen instant feeding the optional
 * idle timeout, which slides inside the absolute TTL.
 */
public final class InMemorySessionStore implements SessionStore {

    /**
     * A ceiling on live sessions, so a login flood inside one TTL window cannot exhaust the
     * heap. Reaching it evicts the oldest, which is the same "the newest caller wins" rule the
     * live-stream registry uses.
     */
    private static final int MAX_SESSIONS = 50_000;

    /** How often a touch may write, so per-request activity does not become per-request work. */
    private static final Duration TOUCH_INTERVAL = Duration.ofSeconds(60);

    /** Everything about one session that is not the credential itself. */
    private record Meta(String handle, Instant createdAt, Instant lastSeenAt,
            ClientInfo client) {
    }

    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Meta> metas = new ConcurrentHashMap<>();
    private final String cookieName;
    private final Duration timeToLive;
    private final Duration idleTimeout;

    public InMemorySessionStore() {
        this(DEFAULT_COOKIE_NAME, null);
    }

    public InMemorySessionStore(String cookieName) {
        this(cookieName, null);
    }

    /** @param timeToLive how long a session stays valid; {@code null} means it never expires */
    public InMemorySessionStore(String cookieName, Duration timeToLive) {
        this(cookieName, timeToLive, null);
    }

    /**
     * @param idleTimeout invalidates a session unseen for this long — sliding, inside the
     *                    absolute {@code timeToLive}; {@code null} disables it
     */
    public InMemorySessionStore(String cookieName, Duration timeToLive, Duration idleTimeout) {
        this.cookieName = cookieName == null || cookieName.isBlank()
                ? DEFAULT_COOKIE_NAME
                : cookieName;
        this.timeToLive = timeToLive;
        this.idleTimeout = idleTimeout;
    }

    @Override
    public String create(Principal principal, ClientInfo client) {
        // Prune on write, the same opportunistic sweep JdbcSessionStore does on create.
        prune();
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        sessions.put(id, new Session(principal, UUID.randomUUID().toString()));
        metas.put(id, new Meta(UUID.randomUUID().toString(), now, now,
                client == null ? ClientInfo.NONE : client));
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

    @Override
    public void touch(String sessionId) {
        Meta meta = sessionId == null ? null : metas.get(sessionId);
        if (meta == null) {
            return;
        }
        Instant now = Instant.now();
        if (meta.lastSeenAt() == null
                || meta.lastSeenAt().plus(TOUCH_INTERVAL).isBefore(now)) {
            metas.replace(sessionId,
                    new Meta(meta.handle(), meta.createdAt(), now, meta.client()));
        }
    }

    private boolean isExpired(String sessionId) {
        Meta meta = metas.get(sessionId);
        if (meta == null) {
            return false;
        }
        Instant now = Instant.now();
        if (timeToLive != null && meta.createdAt().plus(timeToLive).isBefore(now)) {
            return true;
        }
        return idleTimeout != null && meta.lastSeenAt() != null
                && meta.lastSeenAt().plus(idleTimeout).isBefore(now);
    }

    /** Drops expired sessions, then the oldest survivors if the cap is still exceeded. */
    private void prune() {
        metas.keySet().stream().filter(this::isExpired).toList().forEach(this::invalidate);
        if (sessions.size() < MAX_SESSIONS) {
            return;
        }
        metas.entrySet().stream()
                .sorted(java.util.Comparator.comparing(entry -> entry.getValue().createdAt()))
                .limit(Math.max(1, sessions.size() - MAX_SESSIONS + 1))
                .map(java.util.Map.Entry::getKey)
                .toList()
                .forEach(this::invalidate);
    }

    @Override
    public void invalidate(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
            metas.remove(sessionId);
        }
    }

    @Override
    public java.util.List<ActiveSession> sessionsFor(String subject) {
        return sessions.entrySet().stream()
                .filter(entry -> subject != null
                        && subject.equals(entry.getValue().principal().subject()))
                .map(entry -> active(entry.getKey(), entry.getValue()))
                .sorted(java.util.Comparator.comparing(ActiveSession::createdAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();
    }

    @Override
    public java.util.List<ActiveSession> activeSessions(int limit) {
        return sessions.entrySet().stream()
                .map(entry -> active(entry.getKey(), entry.getValue()))
                .sorted(java.util.Comparator.comparing(ActiveSession::createdAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .limit(Math.max(0, limit))
                .toList();
    }

    private ActiveSession active(String sessionId, Session session) {
        Meta meta = metas.get(sessionId);
        if (meta == null) {
            return new ActiveSession(sessionId, session.principal().subject(), null, null,
                    null, null, null, null);
        }
        return new ActiveSession(sessionId, session.principal().subject(), meta.handle(),
                meta.createdAt(),
                timeToLive == null ? null : meta.createdAt().plus(timeToLive),
                meta.lastSeenAt(), meta.client().userAgent(), meta.client().remoteAddr());
    }

    @Override
    public String rotate(String sessionId) {
        Session session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null || isExpired(sessionId)) {
            return null;
        }
        Meta meta = metas.get(sessionId);
        String fresh = UUID.randomUUID().toString();
        sessions.put(fresh, new Session(session.principal(), UUID.randomUUID().toString()));
        // A fresh handle with the client facts and created-at carried: rotation is the
        // same person on the same device, not a new login (docs/session-visibility.md).
        metas.put(fresh, new Meta(UUID.randomUUID().toString(),
                meta == null ? Instant.now() : meta.createdAt(), Instant.now(),
                meta == null ? ClientInfo.NONE : meta.client()));
        invalidate(sessionId);
        return fresh;
    }

    @Override
    public void invalidateOthersFor(String subject, String keepSessionId) {
        sessions.entrySet().removeIf(entry -> subject != null
                && subject.equals(entry.getValue().principal().subject())
                && !entry.getKey().equals(keepSessionId));
        metas.keySet().retainAll(sessions.keySet());
    }

    @Override
    public String cookieName() {
        return cookieName;
    }
}
