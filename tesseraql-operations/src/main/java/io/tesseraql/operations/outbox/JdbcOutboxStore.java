package io.tesseraql.operations.outbox;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.core.outbox.OutboxStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * JDBC-backed {@link OutboxStore} persisting to {@code TQL_OUTBOX_EVENT} (design ch. 39.3).
 */
public final class JdbcOutboxStore implements OutboxStore {

    private static final TqlErrorCode STORE_ERROR = new TqlErrorCode(TqlDomain.BATCH, 5101);

    private final DataSource dataSource;

    public JdbcOutboxStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void ensureSchema() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table if not exists tql_outbox_event (
                      event_id varchar(64) primary key,
                      aggregate_type varchar(128),
                      aggregate_id varchar(256),
                      event_type varchar(256) not null,
                      payload_json text,
                      status varchar(32) not null,
                      attempts integer not null default 0,
                      last_error varchar(2000),
                      created_at timestamp not null,
                      sent_at timestamp
                    )""");
        } catch (SQLException ex) {
            throw error("Failed to create outbox schema", ex);
        }
    }

    @Override
    public String insert(Connection connection, OutboxEvent event) {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement ps = connection.prepareStatement("""
                insert into tql_outbox_event
                  (event_id, aggregate_type, aggregate_id, event_type, payload_json, status,
                   attempts, created_at)
                values (?, ?, ?, ?, ?, 'PENDING', 0, ?)""")) {
            ps.setString(1, id);
            ps.setString(2, event.aggregateType());
            ps.setString(3, event.aggregateId());
            ps.setString(4, event.eventType());
            ps.setString(5, event.payloadJson());
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw error("Failed to insert outbox event", ex);
        }
        return id;
    }

    @Override
    public List<OutboxEvent> listPending(int limit) {
        List<OutboxEvent> events = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select * from tql_outbox_event where status = 'PENDING' "
                                + "order by created_at limit ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(read(rs));
                }
            }
        } catch (SQLException ex) {
            throw error("Failed to list pending outbox events", ex);
        }
        return events;
    }

    @Override
    public void markSent(String eventId) {
        update("update tql_outbox_event set status = 'SENT', sent_at = ? where event_id = ?", ps -> {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, eventId);
        });
    }

    @Override
    public void markFailed(String eventId, String error) {
        update("update tql_outbox_event set status = 'FAILED', attempts = attempts + 1, "
                + "last_error = ? where event_id = ?", ps -> {
            ps.setString(1, error);
            ps.setString(2, eventId);
        });
    }

    private static OutboxEvent read(ResultSet rs) throws SQLException {
        return new OutboxEvent(
                rs.getString("event_id"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                rs.getString("payload_json"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getTimestamp("created_at").toInstant());
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private void update(String sql, Binder binder) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw error("Outbox update failed", ex);
        }
    }

    private static TqlException error(String message, SQLException ex) {
        return TqlException.builder(STORE_ERROR).message(message + ": " + ex.getMessage()).cause(ex).build();
    }
}
