package io.tesseraql.runtime;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The receiving side of the cross-node topic bus (docs/realtime.md): a dedicated PostgreSQL
 * connection that {@code LISTEN}s on {@link CrossNodeTopicBus#CHANNEL} and forwards each
 * notification's {@code tenant|topic} payload to this node's local {@link LiveStreams} — never
 * back onto the bus, so a signal crosses the database exactly once. The connection, thread,
 * reconnect and stop lifecycle is {@link PgListenLoop}'s, shared with the pg-notify messaging
 * consumer this class used to mirror by copy. Signals are a freshness hint with no durability
 * to recover, so unlike the messaging listener there is nothing to drain after a reconnect —
 * subscribers simply refresh on their next signal.
 */
final class TopicNotifyBridge extends PgListenLoop {

    private static final Logger LOG = LoggerFactory.getLogger(TopicNotifyBridge.class);
    private static final int POLL_TIMEOUT_MS = 30_000;

    private final LiveStreams local;

    TopicNotifyBridge(DataSource dataSource, LiveStreams local) {
        super(dataSource, LOG, "tql-live-topics", "Live-topic listen", "Live-topic forward");
        this.local = local;
    }

    @Override
    void started() {
        LOG.info("Cross-node live-view topics listening on '{}'", CrossNodeTopicBus.CHANNEL);
    }

    @Override
    void session(Connection conn) throws SQLException {
        conn.setAutoCommit(true);
        try (Statement statement = conn.createStatement()) {
            statement.execute("LISTEN " + CrossNodeTopicBus.CHANNEL);
        }
        PGConnection pg = conn.unwrap(PGConnection.class);
        while (running()) {
            PGNotification[] notifications = pg.getNotifications(POLL_TIMEOUT_MS);
            if (notifications == null) {
                continue;
            }
            for (PGNotification notification : notifications) {
                forward(notification.getParameter());
            }
        }
    }

    /** Splits {@code tenant|topic} at the last bar (a topic is a slug and never contains one). */
    private void forward(String payload) {
        int bar = payload == null ? -1 : payload.lastIndexOf('|');
        if (bar < 0) {
            return; // not ours; another producer on the channel
        }
        String tenant = payload.substring(0, bar);
        local.emit(tenant.isEmpty() ? null : tenant, payload.substring(bar + 1));
    }
}
