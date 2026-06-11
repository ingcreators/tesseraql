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

    /**
     * Creates the outbox table (including the multi-node claim column) if absent, from the
     * bundled {@code V1__framework_operations.sql} migration script.
     */
    public void ensureSchema() {
        try {
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource, JdbcOutboxStore.class,
                    "/tesseraql/db/migration/operations/V1__framework_operations.sql");
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
                   attempts, created_at, app_name)
                values (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)""")) {
            ps.setString(1, id);
            ps.setString(2, event.aggregateType());
            ps.setString(3, event.aggregateId());
            ps.setString(4, event.eventType());
            ps.setString(5, event.payloadJson());
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.setString(7, event.appName());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw error("Failed to insert outbox event", ex);
        }
        return id;
    }

    /** The connected vendor (for SQL variants and the row-limit clause), detected once. */
    private volatile String vendor;
    private volatile boolean vendorDetected;

    private String vendor() {
        if (!vendorDetected) {
            vendor = io.tesseraql.core.util.DatabaseVendors.vendor(dataSource).orElse(null);
            vendorDetected = true;
        }
        return vendor;
    }

    private String fetchClause() {
        return io.tesseraql.core.dialect.Pagination.fetchClause(vendor());
    }

    @Override
    public List<OutboxEvent> listPending(int limit) {
        List<OutboxEvent> events = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select * from tql_outbox_event where status = 'PENDING' "
                                + "order by created_at " + fetchClause())) {
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

    /**
     * Claims up to {@code limit} deliverable events for this node (design ch. 39.3): rows are
     * selected with {@code FOR UPDATE SKIP LOCKED} and flipped to {@code SENDING} in one short
     * transaction, so concurrent dispatcher nodes never pick the same event. A {@code SENDING}
     * row whose claim is older than five minutes is treated as abandoned (the claiming node
     * crashed mid-delivery) and becomes claimable again, preserving at-least-once delivery.
     */
    @Override
    public List<OutboxEvent> claimPending(int limit) {
        return claimPending(limit, null);
    }

    /**
     * As {@link #claimPending(int)}, additionally narrowed to events emitted by the given apps.
     * A null or empty scope claims everything. The claim query is the bundled
     * {@code outbox-claim-pending.sql} 2-way template (IN expansion and the optional scope
     * condition render there, not in Java).
     */
    @Override
    public List<OutboxEvent> claimPending(int limit, java.util.Collection<String> apps) {
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("abandonedBefore", Timestamp.from(
                Instant.now().minus(java.time.Duration.ofMinutes(5))));
        params.put("apps", apps == null || apps.isEmpty() ? null : List.copyOf(apps));
        params.put("limit", limit);
        io.tesseraql.core.sql.BoundSql bound = io.tesseraql.core.sql.SqlResources.render(
                JdbcOutboxStore.class, "/tesseraql/sql/operations/outbox-claim-pending.sql",
                vendor(), params);
        List<OutboxEvent> events = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement ps = connection.prepareStatement(bound.sql())) {
                    for (int i = 0; i < bound.parameters().size(); i++) {
                        ps.setObject(i + 1, bound.parameters().get(i).value());
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            events.add(read(rs));
                        }
                    }
                }
                if (!events.isEmpty()) {
                    try (PreparedStatement claim = connection.prepareStatement(
                            "update tql_outbox_event set status = 'SENDING', claimed_at = ? "
                                    + "where event_id = ?")) {
                        Timestamp now = Timestamp.from(Instant.now());
                        for (OutboxEvent event : events) {
                            claim.setTimestamp(1, now);
                            claim.setString(2, event.id());
                            claim.addBatch();
                        }
                        claim.executeBatch();
                    }
                }
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException ex) {
            throw error("Failed to claim pending outbox events", ex);
        }
        return events;
    }

    @Override
    public void markSent(String eventId) {
        update("update tql_outbox_event set status = 'SENT', sent_at = ? where event_id = ?",
                ps -> {
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

    /**
     * Dead-letters an event whose attempts are exhausted (roadmap Phase 20): {@code DEAD} rows
     * are never claimed again; they stay visible in the operations console until an operator
     * redelivers them or retention sweeps them.
     */
    @Override
    public void markDead(String eventId, String error) {
        update("update tql_outbox_event set status = 'DEAD', attempts = attempts + 1, "
                + "last_error = ? where event_id = ?", ps -> {
                    ps.setString(1, error);
                    ps.setString(2, eventId);
                });
    }

    /**
     * Inserts an event on its own connection (roadmap Phase 20), for enqueuers outside a business
     * transaction — operations alerts and job notifications. Returns the new event id.
     */
    public String insert(OutboxEvent event) {
        try (Connection connection = dataSource.getConnection()) {
            return insert(connection, event);
        } catch (SQLException ex) {
            throw error("Failed to insert outbox event", ex);
        }
    }

    /**
     * Requeues a {@code FAILED} or {@code DEAD} event for delivery (roadmap Phase 20): the status
     * flips back to {@code PENDING} and the claim is cleared; the attempt count is kept so the
     * history stays honest. Returns false when the event is unknown or not redeliverable.
     */
    public boolean redeliver(String eventId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "update tql_outbox_event set status = 'PENDING', claimed_at = null "
                                + "where event_id = ? and status in ('FAILED', 'DEAD')")) {
            ps.setString(1, eventId);
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw error("Failed to redeliver outbox event", ex);
        }
    }

    /** Looks up one event, for the operations console's scope check before a redelivery. */
    public java.util.Optional<OutboxEvent> find(String eventId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select * from tql_outbox_event where event_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? java.util.Optional.of(read(rs)) : java.util.Optional.empty();
            }
        } catch (SQLException ex) {
            throw error("Failed to find outbox event", ex);
        }
    }

    /** The most recent events (newest first), for the operations console's delivery log. */
    public List<OutboxEvent> recent(int limit) {
        List<OutboxEvent> events = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select * from tql_outbox_event order by created_at desc, event_id "
                                + fetchClause())) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(read(rs));
                }
            }
        } catch (SQLException ex) {
            throw error("Failed to list recent outbox events", ex);
        }
        return events;
    }

    /** Event counts per status, for the operations console's outbox summary and alerts. */
    public java.util.Map<String, Integer> countByStatus() {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select status, count(*) as total from tql_outbox_event group by status");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                counts.put(rs.getString("status"), rs.getInt("total"));
            }
        } catch (SQLException ex) {
            throw error("Failed to count outbox events", ex);
        }
        return counts;
    }

    private static OutboxEvent read(ResultSet rs) throws SQLException {
        Timestamp sentAt = rs.getTimestamp("sent_at");
        return new OutboxEvent(
                rs.getString("event_id"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                rs.getString("payload_json"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(),
                sentAt == null ? null : sentAt.toInstant(),
                rs.getString("app_name"));
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
        return TqlException.builder(STORE_ERROR).message(message + ": " + ex.getMessage()).cause(ex)
                .build();
    }
}
