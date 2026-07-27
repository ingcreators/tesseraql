package io.tesseraql.security.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.security.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.sql.DataSource;

/**
 * A database-backed {@link SessionStore} (design ch. 11.2): sessions live in {@code tql_session},
 * so any runtime node sharing the database resolves a login made on another node - the durable
 * choice for multi-node deployments. Sessions expire after the configured time-to-live; expired
 * rows are ignored on read and pruned opportunistically on create.
 *
 * <p>Each row carries its metadata (docs/session-visibility.md): a public handle for row
 * actions, the client facts captured at login, and a last-seen instant feeding the optional
 * idle timeout. Touches throttle through a node-local map, so per-request activity does not
 * become a per-request UPDATE on the shared store — a lost throttled touch costs at most the
 * throttle window of staleness, the accepted trade.
 */
public final class JdbcSessionStore implements SessionStore {

    /** How often a touch may write, per node. */
    private static final Duration TOUCH_INTERVAL = Duration.ofSeconds(60);

    private final DataSource dataSource;
    private final Duration timeToLive;
    private final Duration idleTimeout;
    private final String cookieName;
    private final ObjectMapper mapper = new ObjectMapper();
    /** Node-local last-touch instants, keyed by session id; entries die with the session. */
    private final ConcurrentMap<String, Instant> touched = new ConcurrentHashMap<>();

    public JdbcSessionStore(DataSource dataSource, Duration timeToLive) {
        this(dataSource, timeToLive, null, DEFAULT_COOKIE_NAME);
    }

    public JdbcSessionStore(DataSource dataSource, Duration timeToLive, String cookieName) {
        this(dataSource, timeToLive, null, cookieName);
    }

    /**
     * @param idleTimeout invalidates a session unseen for this long — sliding, inside the
     *                    absolute {@code timeToLive}; {@code null} disables it
     */
    public JdbcSessionStore(DataSource dataSource, Duration timeToLive, Duration idleTimeout,
            String cookieName) {
        this.dataSource = dataSource;
        this.timeToLive = timeToLive;
        this.idleTimeout = idleTimeout;
        this.cookieName = cookieName == null || cookieName.isBlank()
                ? DEFAULT_COOKIE_NAME
                : cookieName;
    }

    /**
     * Creates the session table if absent, from the bundled
     * {@code V1__framework_sessions.sql} migration script.
     */
    public void ensureSchema() {
        try {
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource, JdbcSessionStore.class,
                    "/tesseraql/db/migration/security/V1__framework_sessions.sql");
            // The subject column powering the account surface's session list (roadmap
            // Phase 48). Pre-upgrade rows keep a null subject: not listed, aging out at expiry.
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource, JdbcSessionStore.class,
                    "/tesseraql/db/migration/security/V2__session_subject.sql");
            // Handle, client facts and last-seen (docs/session-visibility.md). Pre-upgrade
            // rows keep null metadata: listed with dashes, aging out at expiry.
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource, JdbcSessionStore.class,
                    "/tesseraql/db/migration/security/V3__session_metadata.sql");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create session schema", ex);
        }
    }

    @Override
    public String create(Principal principal, ClientInfo client) {
        return insert(principal, client == null ? ClientInfo.NONE : client, Instant.now(), null);
    }

    /** Inserts one session row; {@code createdAt}/{@code expiresAt} carry on rotation. */
    private String insert(Principal principal, ClientInfo client, Instant createdAt,
            Instant expiresAt) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        try (Connection connection = dataSource.getConnection()) {
            prune(connection, now);
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into tql_session (session_id, principal_json, csrf_token, "
                            + "created_at, expires_at, subject, session_handle, user_agent, "
                            + "remote_addr, last_seen_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, id);
                insert.setString(2, mapper.writeValueAsString(principal));
                insert.setString(3, UUID.randomUUID().toString());
                insert.setTimestamp(4, Timestamp.from(createdAt));
                insert.setTimestamp(5, Timestamp.from(
                        expiresAt != null ? expiresAt : createdAt.plus(timeToLive)));
                insert.setString(6, principal.subject());
                insert.setString(7, UUID.randomUUID().toString());
                insert.setString(8, client.userAgent());
                insert.setString(9, client.remoteAddr());
                insert.setTimestamp(10, Timestamp.from(now));
                insert.executeUpdate();
            }
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Failed to create session", ex);
        }
        return id;
    }

    /** Absolute expiry always prunes; the idle window prunes only rows that have a clock. */
    private void prune(Connection connection, Instant now) throws SQLException {
        try (PreparedStatement prune = connection.prepareStatement(
                idleTimeout == null
                        ? "delete from tql_session where expires_at < ?"
                        : "delete from tql_session where expires_at < ? "
                                + "or (last_seen_at is not null and last_seen_at < ?)")) {
            prune.setTimestamp(1, Timestamp.from(now));
            if (idleTimeout != null) {
                prune.setTimestamp(2, Timestamp.from(now.minus(idleTimeout)));
            }
            prune.executeUpdate();
        }
    }

    @Override
    public Session session(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        Instant now = Instant.now();
        String sql = "select principal_json, csrf_token from tql_session "
                + "where session_id = ? and expires_at >= ?"
                // Pre-upgrade rows have no last-seen clock; the idle window cannot
                // honestly apply to them, so they live to their absolute expiry.
                + (idleTimeout == null ? "" : " and (last_seen_at is null or last_seen_at >= ?)");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setTimestamp(2, Timestamp.from(now));
            if (idleTimeout != null) {
                ps.setTimestamp(3, Timestamp.from(now.minus(idleTimeout)));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Principal principal = mapper.readValue(rs.getString(1), Principal.class);
                return new Session(principal, rs.getString(2));
            }
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Failed to read session", ex);
        }
    }

    @Override
    public void touch(String sessionId) {
        if (sessionId == null) {
            return;
        }
        Instant now = Instant.now();
        Instant last = touched.get(sessionId);
        if (last != null && last.plus(TOUCH_INTERVAL).isAfter(now)) {
            return;
        }
        touched.put(sessionId, now);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "update tql_session set last_seen_at = ? where session_id = ?")) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setString(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to touch session", ex);
        }
    }

    @Override
    public void invalidate(String sessionId) {
        if (sessionId == null) {
            return;
        }
        touched.remove(sessionId);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "delete from tql_session where session_id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to invalidate session", ex);
        }
    }

    @Override
    public void invalidateByHandle(String subject, String handle) {
        if (subject == null || handle == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "delete from tql_session where subject = ? and session_handle = ?")) {
            ps.setString(1, subject);
            ps.setString(2, handle);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to invalidate session", ex);
        }
    }

    @Override
    public java.util.List<ActiveSession> sessionsFor(String subject) {
        return query("select session_id, subject, session_handle, created_at, expires_at, "
                + "last_seen_at, user_agent, remote_addr from tql_session "
                + "where subject = ? and expires_at >= ? order by created_at desc",
                ps -> {
                    ps.setString(1, subject);
                    ps.setTimestamp(2, Timestamp.from(Instant.now()));
                }, 0);
    }

    @Override
    public java.util.List<ActiveSession> activeSessions(int limit) {
        return query("select session_id, subject, session_handle, created_at, expires_at, "
                + "last_seen_at, user_agent, remote_addr from tql_session "
                + "where expires_at >= ? order by created_at desc",
                ps -> ps.setTimestamp(1, Timestamp.from(Instant.now())), limit);
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private java.util.List<ActiveSession> query(String sql, Binder binder, int limit) {
        java.util.List<ActiveSession> active = new java.util.ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            if (limit > 0) {
                ps.setMaxRows(limit);
            }
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && (limit <= 0 || active.size() < limit)) {
                    active.add(new ActiveSession(rs.getString(1), rs.getString(2),
                            rs.getString(3), instant(rs.getTimestamp(4)),
                            instant(rs.getTimestamp(5)), instant(rs.getTimestamp(6)),
                            rs.getString(7), rs.getString(8)));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list sessions", ex);
        }
        return active;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    @Override
    public String rotate(String sessionId) {
        Session session = session(sessionId);
        if (session == null) {
            return null;
        }
        // Carry created-at, the original expiry ceiling, and the client facts: rotation
        // is the same person on the same device (docs/session-visibility.md). The handle
        // and CSRF token are freshly minted with the id.
        Instant createdAt = null;
        Instant expiresAt = null;
        ClientInfo client = ClientInfo.NONE;
        for (ActiveSession active : sessionsFor(session.principal().subject())) {
            if (sessionId.equals(active.sessionId())) {
                createdAt = active.createdAt();
                expiresAt = active.expiresAt();
                client = new ClientInfo(active.userAgent(), active.remoteAddr());
                break;
            }
        }
        String fresh = insert(session.principal(), client,
                createdAt == null ? Instant.now() : createdAt, expiresAt);
        invalidate(sessionId);
        return fresh;
    }

    @Override
    public void invalidateOthersFor(String subject, String keepSessionId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "delete from tql_session where subject = ? and session_id <> ?")) {
            ps.setString(1, subject);
            ps.setString(2, keepSessionId == null ? "" : keepSessionId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to invalidate sessions", ex);
        }
    }

    @Override
    public String cookieName() {
        return cookieName;
    }
}
