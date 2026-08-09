package io.tesseraql.operations.messaging;

import io.tesseraql.core.dialect.SqlErrors;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.messaging.ChannelEvent;
import io.tesseraql.core.messaging.EventChannelStore;
import io.tesseraql.core.messaging.EventMessage;
import io.tesseraql.core.util.SqlScripts;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * JDBC-backed {@link EventChannelStore} for the built-in {@code pg-notify} transport (roadmap
 * Phase 27): a durable {@code tql_event} log with {@code FOR UPDATE SKIP LOCKED} claiming — the
 * canonical "PostgreSQL as a queue" pattern, the same one {@code JdbcOutboxStore} already uses.
 *
 * <p>{@link #publish} writes the durable row and issues a PostgreSQL {@code NOTIFY} in the same
 * transaction, so a listening consumer wakes the instant the row becomes visible; the wake is only
 * an optimisation, never the source of durability. Idempotency keys are deduplicated in
 * {@code tql_queue_consumed}, with the dedup record and the message's terminal state committed
 * together so they cannot diverge.
 */
public final class JdbcEventChannelStore implements EventChannelStore {

    /** TQL-BATCH-5313: the event channel store could not complete an operation. */
    private static final TqlErrorCode STORE_ERROR = new TqlErrorCode(TqlDomain.BATCH, 5313);
    /** A claim older than this is treated as abandoned (the claiming node crashed mid-consume). */
    private static final Duration ABANDONED_AFTER = Duration.ofMinutes(5);

    private final DataSource dataSource;
    private volatile String vendor;
    private volatile boolean vendorDetected;

    public JdbcEventChannelStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates {@code tql_event} and {@code tql_queue_consumed} (per dialect) if they do not exist. */
    public void ensureSchema() {
        try {
            SqlScripts.applyForVendor(dataSource, JdbcEventChannelStore.class,
                    "/tesseraql/db/migration/messaging/V1__messaging.sql");
        } catch (SQLException ex) {
            throw error("Failed to create messaging schema", ex);
        }
    }

    /**
     * The PostgreSQL LISTEN/NOTIFY channel name a publisher signals and a consumer listens on.
     * The sanitizer keeps Unicode letters (docs/unicode-identifiers.md), so the NOTIFY/LISTEN
     * statements double-quote it — quotes themselves can never appear here.
     */
    public static String notifyChannel(String channel) {
        StringBuilder safe = new StringBuilder("tql_evt_");
        for (char c : channel.toLowerCase(Locale.ROOT).toCharArray()) {
            safe.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return safe.toString();
    }

    /** The connected vendor (for the per-dialect claim SQL and the NOTIFY guard), detected once. */
    private String vendor() {
        if (!vendorDetected) {
            vendor = io.tesseraql.core.util.DatabaseVendors.vendor(dataSource).orElse(null);
            vendorDetected = true;
        }
        return vendor;
    }

    @Override
    public String publish(String channel, String topic, String key, String payloadJson,
            String appName) {
        String id = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement ps = connection.prepareStatement("""
                        insert into tql_event
                          (event_id, channel, topic, msg_key, payload_json, status, attempts,
                           published_at, app_name)
                        values (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)""")) {
                    ps.setString(1, id);
                    ps.setString(2, channel);
                    ps.setString(3, topic);
                    ps.setString(4, key);
                    ps.setString(5, payloadJson);
                    ps.setTimestamp(6, Timestamp.from(Instant.now()));
                    ps.setString(7, appName);
                    ps.executeUpdate();
                }
                // NOTIFY rides the same transaction, so it is delivered exactly when the row is
                // visible — and only on PostgreSQL, where a consumer can LISTEN for it. The db-poll
                // transport (every other dialect, and PostgreSQL behind a pooler) just polls.
                if ("postgresql".equals(vendor())) {
                    try (Statement notify = connection.createStatement()) {
                        notify.execute("NOTIFY \"" + notifyChannel(channel) + "\"");
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
            throw error("Failed to publish event", ex);
        }
        return id;
    }

    @Override
    public List<EventMessage> claim(String channel, String topic, int limit) {
        List<EventMessage> messages = new ArrayList<>();
        // The SKIP LOCKED claim renders per dialect (PostgreSQL/MySQL LIMIT, Oracle ROWNUM, SQL
        // Server TOP + READPAST) from the bundled event-claim 2-way SQL, the same approach the
        // outbox dispatcher uses — so the durable-table queue is portable across every dialect.
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("channel", channel);
        params.put("topic", topic);
        params.put("abandonedBefore", Timestamp.from(Instant.now().minus(ABANDONED_AFTER)));
        params.put("limit", limit);
        io.tesseraql.core.sql.BoundSql bound = io.tesseraql.core.sql.SqlResources.render(
                JdbcEventChannelStore.class, "/tesseraql/sql/messaging/event-claim.sql", vendor(),
                params);
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
                            messages.add(new EventMessage(rs.getString("event_id"),
                                    rs.getString("channel"), rs.getString("topic"),
                                    rs.getString("msg_key"), rs.getString("payload_json"),
                                    rs.getInt("attempts")));
                        }
                    }
                }
                if (!messages.isEmpty()) {
                    try (PreparedStatement claim = connection.prepareStatement(
                            "update tql_event set status = 'CLAIMED', claimed_at = ? "
                                    + "where event_id = ?")) {
                        Timestamp now = Timestamp.from(Instant.now());
                        for (EventMessage message : messages) {
                            claim.setTimestamp(1, now);
                            claim.setString(2, message.id());
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
            throw error("Failed to claim events", ex);
        }
        return messages;
    }

    @Override
    public boolean consumed(String channel, String topic, String idempotencyKey) {
        if (idempotencyKey == null) {
            return false;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select 1 from tql_queue_consumed "
                                + "where channel = ? and topic = ? and idem_key = ?")) {
            ps.setString(1, channel);
            ps.setString(2, topic);
            ps.setString(3, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            throw error("Failed to check event idempotency key", ex);
        }
    }

    @Override
    public void markConsumed(String messageId, String channel, String topic,
            String idempotencyKey) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement consume = connection.prepareStatement(
                        "update tql_event set status = 'CONSUMED', consumed_at = ? "
                                + "where event_id = ?")) {
                    consume.setTimestamp(1, Timestamp.from(Instant.now()));
                    consume.setString(2, messageId);
                    consume.executeUpdate();
                }
                if (idempotencyKey != null) {
                    recordDedup(connection, channel, topic, idempotencyKey);
                }
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException ex) {
            throw error("Failed to mark event consumed", ex);
        }
    }

    /**
     * Records the idempotency key, tolerating a concurrent delivery of the same key. The insert is
     * fenced by a savepoint: a unique violation rolls back only to the savepoint, not the whole
     * transaction (PostgreSQL aborts a transaction on any error), so the message's consumed state
     * still commits. The savepoint is never explicitly released — the commit that follows releases
     * it implicitly on every dialect, whereas {@code releaseSavepoint} is a
     * {@code SQLFeatureNotSupportedException} on the Oracle and SQL Server drivers.
     */
    private void recordDedup(Connection connection, String channel, String topic, String key)
            throws SQLException {
        java.sql.Savepoint savepoint = connection.setSavepoint("dedup");
        try (PreparedStatement dedup = connection.prepareStatement(
                "insert into tql_queue_consumed (channel, topic, idem_key, consumed_at) "
                        + "values (?, ?, ?, ?)")) {
            dedup.setString(1, channel);
            dedup.setString(2, topic);
            dedup.setString(3, key);
            dedup.setTimestamp(4, Timestamp.from(Instant.now()));
            dedup.executeUpdate();
        } catch (SQLException ex) {
            if (!SqlErrors.isUniqueViolation(ex)) {
                throw ex;
            }
            connection.rollback(savepoint);
        }
    }

    @Override
    public void markFailed(String messageId, String error, int maxAttempts) {
        // A failed consume increments attempts; the row stays PENDING (retried later) until the
        // ceiling, when it is dead-lettered (never claimed again). The claim timestamp is kept, so
        // the abandoned-claim window doubles as the retry backoff — a transiently failing message
        // is not re-delivered in a tight loop within one drain.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "update tql_event set "
                                + "status = case when attempts + 1 >= ? then 'DEAD' else 'PENDING' end, "
                                + "attempts = attempts + 1, last_error = ? "
                                + "where event_id = ?")) {
            ps.setInt(1, maxAttempts);
            ps.setString(2, error);
            ps.setString(3, messageId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw error("Failed to record event delivery failure", ex);
        }
    }

    /** The most recent messages (newest first), for the operations console's queue events log. */
    @Override
    public List<ChannelEvent> recent(int limit) {
        List<ChannelEvent> events = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select * from tql_event order by published_at desc, event_id "
                                + io.tesseraql.core.dialect.Pagination.fetchClause(vendor()))) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(read(rs));
                }
            }
        } catch (SQLException ex) {
            throw error("Failed to list recent events", ex);
        }
        return events;
    }

    /** Message counts per status, for the console's queue summary and the dead-letter alert. */
    @Override
    public java.util.Map<String, Integer> countByStatus() {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select status, count(*) as total from tql_event group by status");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                counts.put(rs.getString("status"), rs.getInt("total"));
            }
        } catch (SQLException ex) {
            throw error("Failed to count events", ex);
        }
        return counts;
    }

    /** Looks up one message, for the operations console's scope check before a redelivery. */
    @Override
    public java.util.Optional<ChannelEvent> find(String messageId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select * from tql_event where event_id = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? java.util.Optional.of(read(rs)) : java.util.Optional.empty();
            }
        } catch (SQLException ex) {
            throw error("Failed to find event", ex);
        }
    }

    /**
     * Requeues a {@code DEAD} message: the status flips back to {@code PENDING} and the claim is
     * cleared, so the next drain claims it immediately; the attempt count is kept so the history
     * stays honest. Returns false when the message is unknown or not dead.
     */
    @Override
    public boolean redeliver(String messageId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "update tql_event set status = 'PENDING', claimed_at = null "
                                + "where event_id = ? and status = 'DEAD'")) {
            ps.setString(1, messageId);
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw error("Failed to redeliver event", ex);
        }
    }

    private static ChannelEvent read(ResultSet rs) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        Timestamp consumedAt = rs.getTimestamp("consumed_at");
        return new ChannelEvent(
                rs.getString("event_id"),
                rs.getString("channel"),
                rs.getString("topic"),
                rs.getString("msg_key"),
                rs.getString("app_name"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                publishedAt == null ? null : publishedAt.toInstant(),
                consumedAt == null ? null : consumedAt.toInstant());
    }

    private static TqlException error(String message, SQLException ex) {
        return TqlException.builder(STORE_ERROR).message(message + ": " + ex.getMessage()).cause(ex)
                .build();
    }
}
