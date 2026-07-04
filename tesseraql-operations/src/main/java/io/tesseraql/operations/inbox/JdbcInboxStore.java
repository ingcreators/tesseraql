package io.tesseraql.operations.inbox;

import io.tesseraql.core.inbox.InboxStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * The JDBC inbox (roadmap Phase 49): one row per delivered message in
 * {@code tql_user_notification}, keyed by the outbox event id — the dedupe that makes
 * at-least-once delivery read-once. Delivery opportunistically prunes READ messages older
 * than the retention window (the session store's prune-on-create pattern); unread messages
 * stay until read.
 */
public final class JdbcInboxStore implements InboxStore {

    private final DataSource dataSource;
    private final Duration retention;

    public JdbcInboxStore(DataSource dataSource, Duration retention) {
        this.dataSource = dataSource;
        this.retention = retention;
    }

    /** Creates the inbox table if absent, from the bundled vendor-aware script. */
    public void ensureSchema() {
        try {
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource, JdbcInboxStore.class,
                    "/tesseraql/db/migration/inbox/V1__user_notifications.sql");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create inbox schema", ex);
        }
    }

    @Override
    public void deliver(String eventId, String tenantId, String subject, String channel,
            String source, String title, String body) {
        Instant now = Instant.now();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement prune = connection.prepareStatement(
                    "delete from tql_user_notification where read_at is not null "
                            + "and created_at < ?")) {
                prune.setTimestamp(1, Timestamp.from(now.minus(retention)));
                prune.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    insert into tql_user_notification
                      (event_id, tenant_id, subject, channel, source, title, body, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, eventId);
                insert.setString(2, tenant(tenantId));
                insert.setString(3, subject);
                insert.setString(4, channel);
                insert.setString(5, source);
                insert.setString(6, title);
                insert.setString(7, body);
                insert.setTimestamp(8, Timestamp.from(now));
                insert.executeUpdate();
            } catch (SQLException raced) {
                // At-least-once redelivery: the row exists -> already delivered, done. Any
                // other failure keeps throwing so the dispatcher retries/dead-letters.
                if (!exists(connection, eventId)) {
                    throw raced;
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to deliver inbox notification", ex);
        }
    }

    @Override
    public int unreadCount(String tenantId, String subject) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select count(*) from tql_user_notification "
                                + "where tenant_id = ? and subject = ? and read_at is null")) {
            ps.setString(1, tenant(tenantId));
            ps.setString(2, subject);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to count inbox notifications", ex);
        }
    }

    @Override
    public List<InboxMessage> recent(String tenantId, String subject, int limit) {
        List<InboxMessage> messages = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select event_id, title, body, source, created_at, read_at "
                                + "from tql_user_notification "
                                + "where tenant_id = ? and subject = ? "
                                + "order by created_at desc, event_id")) {
            ps.setString(1, tenant(tenantId));
            ps.setString(2, subject);
            ps.setMaxRows(limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp readAt = rs.getTimestamp(6);
                    messages.add(new InboxMessage(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4),
                            rs.getTimestamp(5).toInstant(),
                            readAt == null ? null : readAt.toInstant()));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list inbox notifications", ex);
        }
        return messages;
    }

    @Override
    public boolean markRead(String tenantId, String subject, String eventId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "update tql_user_notification set read_at = ? "
                                + "where event_id = ? and tenant_id = ? and subject = ? "
                                + "and read_at is null")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, eventId);
            ps.setString(3, tenant(tenantId));
            ps.setString(4, subject);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to mark inbox notification read", ex);
        }
    }

    @Override
    public int markAllRead(String tenantId, String subject) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "update tql_user_notification set read_at = ? "
                                + "where tenant_id = ? and subject = ? and read_at is null")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, tenant(tenantId));
            ps.setString(3, subject);
            return ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to mark inbox notifications read", ex);
        }
    }

    private static boolean exists(Connection connection, String eventId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "select 1 from tql_user_notification where event_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String tenant(String tenantId) {
        return tenantId == null ? "" : tenantId;
    }
}
