package io.tesseraql.runtime;

import io.tesseraql.operations.messaging.JdbcEventChannelStore;
import io.tesseraql.pipeline.RuntimeContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code pg-notify} transport's consumer engine (roadmap Phase 27): a dedicated PostgreSQL
 * connection that {@code LISTEN}s on each subscribed channel and wakes the {@link QueueConsumer} the
 * instant an event is published, with the listen poll timeout doubling as the at-least-once backstop
 * so a missed notification never strands a message. Durability lives in the {@code tql_event} table;
 * this thread only decides <em>when</em> to drain, never <em>whether</em> a message survives.
 *
 * <p>Run as a Camel service, so it starts with the context and stops cleanly on shutdown. On a
 * connection loss (a database restart) it reconnects, re-listens, and drains to catch up — the same
 * recovery the backstop would eventually provide, only sooner.
 */
final class PgNotifyListener implements RuntimeContext.Service {

    /** Whether this service is running; a stop is asked for, not waited on. */
    private volatile boolean running;

    private static final Logger LOG = LoggerFactory.getLogger(PgNotifyListener.class);
    private static final long RECONNECT_DELAY_MS = 5_000;

    private final DataSource dataSource;
    private final QueueConsumer consumer;
    private final long backstopMillis;
    private final Set<String> channels = new LinkedHashSet<>();
    private volatile Thread thread;
    private volatile Connection connection;

    PgNotifyListener(DataSource dataSource, QueueConsumer consumer, long backstopMillis) {
        this.dataSource = dataSource;
        this.consumer = consumer;
        this.backstopMillis = backstopMillis;
        consumer.subscriptions().forEach(s -> channels.add(s.channel()));
    }

    @Override
    public void start() {
        running = true;
        thread = new Thread(this::run, "tql-pg-notify");
        thread.setDaemon(true);
        thread.start();
        LOG.info("pg-notify consumer listening on {} channel(s)", channels.size());
    }

    @Override
    public void stop() {
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
                listenAndDrain();
            } catch (SQLException ex) {
                if (!running) {
                    return;
                }
                LOG.warn("pg-notify listen connection lost; reconnecting: {}", ex.getMessage());
                sleep(RECONNECT_DELAY_MS);
            } catch (RuntimeException ex) {
                if (!running) {
                    return;
                }
                LOG.warn("pg-notify drain failed; continuing: {}", ex.getMessage());
                sleep(RECONNECT_DELAY_MS);
            }
        }
    }

    private void listenAndDrain() throws SQLException {
        Connection conn = dataSource.getConnection();
        connection = conn;
        try {
            conn.setAutoCommit(true);
            try (Statement statement = conn.createStatement()) {
                for (String channel : channels) {
                    statement.execute(
                            "LISTEN \"" + JdbcEventChannelStore.notifyChannel(channel) + "\"");
                }
            }
            // Catch up on anything published while we were not listening (startup or a reconnect).
            consumer.drainAll();
            PGConnection pg = conn.unwrap(PGConnection.class);
            while (running) {
                // Blocks up to the backstop interval, returning early when a NOTIFY arrives. Either
                // way we drain: a notification means new work, a timeout is the periodic sweep.
                pg.getNotifications((int) backstopMillis);
                if (!running) {
                    return;
                }
                consumer.drainAll();
            }
        } finally {
            // Every exit releases the dedicated LISTEN connection, because the next attempt opens
            // a fresh one. A drain that threw a RuntimeException — which is what the event store
            // raises, wrapping SQLException in TqlException — used to skip the close and orphan
            // one pooled connection per reconnect cycle. The pool is the app's main pool, so a
            // persistent drain failure took every other component down with it.
            closeQuietly(conn);
            connection = null;
        }
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
                // best effort
            }
        }
    }
}
