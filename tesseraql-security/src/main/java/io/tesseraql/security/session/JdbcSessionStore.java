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
import javax.sql.DataSource;

/**
 * A database-backed {@link SessionStore} (design ch. 11.2): sessions live in {@code tql_session},
 * so any runtime node sharing the database resolves a login made on another node - the durable
 * choice for multi-node deployments. Sessions expire after the configured time-to-live; expired
 * rows are ignored on read and pruned opportunistically on create.
 */
public final class JdbcSessionStore implements SessionStore {

    private final DataSource dataSource;
    private final Duration timeToLive;
    private final String cookieName;
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcSessionStore(DataSource dataSource, Duration timeToLive) {
        this(dataSource, timeToLive, DEFAULT_COOKIE_NAME);
    }

    public JdbcSessionStore(DataSource dataSource, Duration timeToLive, String cookieName) {
        this.dataSource = dataSource;
        this.timeToLive = timeToLive;
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
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create session schema", ex);
        }
    }

    @Override
    public String create(Principal principal) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement prune = connection.prepareStatement(
                    "delete from tql_session where expires_at < ?")) {
                prune.setTimestamp(1, Timestamp.from(now));
                prune.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into tql_session (session_id, principal_json, csrf_token, "
                            + "created_at, expires_at) values (?, ?, ?, ?, ?)")) {
                insert.setString(1, id);
                insert.setString(2, mapper.writeValueAsString(principal));
                insert.setString(3, UUID.randomUUID().toString());
                insert.setTimestamp(4, Timestamp.from(now));
                insert.setTimestamp(5, Timestamp.from(now.plus(timeToLive)));
                insert.executeUpdate();
            }
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Failed to create session", ex);
        }
        return id;
    }

    @Override
    public Session session(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select principal_json, csrf_token from tql_session "
                                + "where session_id = ? and expires_at >= ?")) {
            ps.setString(1, sessionId);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
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
    public void invalidate(String sessionId) {
        if (sessionId == null) {
            return;
        }
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
    public String cookieName() {
        return cookieName;
    }
}
