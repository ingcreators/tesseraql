package io.tesseraql.runtime;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.camel.support.service.ServiceSupport;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The receiving side of the cross-node topic bus (docs/realtime.md): a dedicated PostgreSQL
 * connection that {@code LISTEN}s on {@link CrossNodeTopicBus#CHANNEL} and forwards each
 * notification's {@code tenant|topic} payload to this node's local {@link LiveStreams} — never
 * back onto the bus, so a signal crosses the database exactly once. Mirrors the pg-notify
 * messaging consumer's lifecycle ({@link PgNotifyListener}): a Camel service that starts with
 * the context, reconnects with backoff on a connection loss, and stops cleanly. Signals are a
 * freshness hint with no durability to recover, so unlike the messaging listener there is
 * nothing to drain after a reconnect — subscribers simply refresh on their next signal.
 */
final class TopicNotifyBridge extends ServiceSupport {

    private static final Logger LOG = LoggerFactory.getLogger(TopicNotifyBridge.class);
    private static final long RECONNECT_DELAY_MS = 5_000;
    private static final int POLL_TIMEOUT_MS = 30_000;

    private final DataSource dataSource;
    private final LiveStreams local;
    private volatile boolean running;
    private volatile Thread thread;
    private volatile Connection connection;

    TopicNotifyBridge(DataSource dataSource, LiveStreams local) {
        this.dataSource = dataSource;
        this.local = local;
    }

    @Override
    protected void doStart() {
        running = true;
        thread = new Thread(this::run, "tql-live-topics");
        thread.setDaemon(true);
        thread.start();
        LOG.info("Cross-node live-view topics listening on '{}'", CrossNodeTopicBus.CHANNEL);
    }

    @Override
    protected void doStop() {
        running = false;
        Thread current = thread;
        if (current != null) {
            current.interrupt();
        }
        closeQuietly(connection);
    }

    private void run() {
        while (running) {
            try {
                listen();
            } catch (SQLException ex) {
                closeQuietly(connection);
                connection = null;
                if (!running) {
                    return;
                }
                LOG.warn("Live-topic listen connection lost; reconnecting: {}", ex.getMessage());
                sleep(RECONNECT_DELAY_MS);
            }
        }
    }

    private void listen() throws SQLException {
        Connection conn = dataSource.getConnection();
        connection = conn;
        conn.setAutoCommit(true);
        try (Statement statement = conn.createStatement()) {
            statement.execute("LISTEN " + CrossNodeTopicBus.CHANNEL);
        }
        PGConnection pg = conn.unwrap(PGConnection.class);
        while (running) {
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // best effort: the thread is ending or reconnecting
            }
        }
    }
}
