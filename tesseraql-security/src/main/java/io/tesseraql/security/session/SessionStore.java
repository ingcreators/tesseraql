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

    /**
     * Client facts captured at login (docs/session-visibility.md): informational — the
     * address is whatever the edge presented, and an edge that does not strip inbound
     * {@code X-Forwarded-For} lets a client spoof it. The user agent is truncated to 255.
     */
    record ClientInfo(String userAgent, String remoteAddr) {

        public static final ClientInfo NONE = new ClientInfo(null, null);

        public ClientInfo {
            userAgent = userAgent != null && userAgent.length() > 255
                    ? userAgent.substring(0, 255)
                    : userAgent;
        }

        /** First {@code X-Forwarded-For} entry when the edge presents one, else the peer. */
        public static ClientInfo of(String userAgent, String forwardedFor, String peerAddress) {
            String address = forwardedFor != null && !forwardedFor.isBlank()
                    ? forwardedFor.split(",")[0].trim()
                    : peerAddress;
            return new ClientInfo(userAgent, address);
        }
    }

    /** Creates a session for the principal and returns its id. */
    String create(Principal principal, ClientInfo client);

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

    /**
     * Marks the session as active now (docs/session-visibility.md), feeding the
     * idle-timeout window and the "last active" column. Implementations throttle the
     * write; the default is a no-op for stores that never learned metadata.
     */
    default void touch(String sessionId) {
    }

    /**
     * Invalidates the subject's session named by its public handle
     * (docs/session-visibility.md). Subject-scoped on purpose: a leaked handle cannot
     * name another subject's session. Unknown handles are a no-op.
     */
    default void invalidateByHandle(String subject, String handle) {
        if (subject == null || handle == null) {
            return;
        }
        for (ActiveSession active : sessionsFor(subject)) {
            if (handle.equals(active.handle())) {
                invalidate(active.sessionId());
            }
        }
    }

    /**
     * Rotates a session in place (docs/session-rotation.md): a fresh id and CSRF token for
     * the same principal, the old id invalidated before the response leaves — no
     * rotate-later window. Returns the new id, or {@code null} when the id resolves to no
     * session: an expired session mid-flight is the caller's next 401, not a rotation
     * crash. Stores with a cheaper primitive can override.
     */
    default String rotate(String sessionId) {
        Session session = sessionId == null ? null : session(sessionId);
        if (session == null) {
            return null;
        }
        // Carry the client facts forward (docs/session-visibility.md): rotation is the
        // same person on the same device. Bundled stores also carry created-at.
        ClientInfo client = ClientInfo.NONE;
        for (ActiveSession active : sessionsFor(session.principal().subject())) {
            if (sessionId.equals(active.sessionId())) {
                client = new ClientInfo(active.userAgent(), active.remoteAddr());
                break;
            }
        }
        String fresh = create(session.principal(), client);
        invalidate(sessionId);
        return fresh;
    }

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
     * An active session for the self-service list, the IAM Admin panels, and the
     * cross-subject page (docs/session-visibility.md). The id is for keep-checks only and
     * must never be rendered; {@code handle} is the public identifier a row action names.
     * Metadata may be {@code null} — pre-upgrade rows, or stores that never learned it —
     * and renders as a dash.
     */
    record ActiveSession(String sessionId, String subject, String handle,
            java.time.Instant createdAt, java.time.Instant expiresAt,
            java.time.Instant lastSeenAt, String userAgent, String remoteAddr) {
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

    /**
     * Every live session, newest first, for the cross-subject administration page
     * (docs/session-visibility.md). Default: empty — a custom store that never learned to
     * enumerate simply renders no rows.
     */
    default java.util.List<ActiveSession> activeSessions(int limit) {
        return java.util.List.of();
    }

    String cookieName();
}
