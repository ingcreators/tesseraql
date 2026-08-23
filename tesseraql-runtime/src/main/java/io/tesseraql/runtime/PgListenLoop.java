package io.tesseraql.runtime;

import io.tesseraql.pipeline.RuntimeContext;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;

/**
 * The lifecycle two PostgreSQL LISTEN services shared by copy — the pg-notify messaging
 * consumer and the cross-node topic bridge, whose javadoc already said "mirrors the … listener's
 * lifecycle" (docs/duplication-consolidation.md, campaign 4): one dedicated connection on one
 * named daemon thread, a reconnect-with-delay loop whose {@code RuntimeException} arm exists
 * because one sibling learned the hard way that a single unchecked failure otherwise ends the
 * service for the life of the process, and a stop that interrupts the thread and closes the
 * connection so a blocked poll wakes. Every session exit releases its connection — the leak the
 * messaging listener's own comment records fixing lives here now, for both.
 *
 * <p>What a session <em>does</em> — which channels to LISTEN on, whether to drain durable work
 * or forward transient signals — stays with each subclass.
 */
abstract class PgListenLoop implements RuntimeContext.Service {

    static final long RECONNECT_DELAY_MS = 5_000;

    /** Whether this service is running; a stop is asked for, not waited on. */
    private volatile boolean running;
    private volatile Thread thread;
    private volatile Connection connection;

    private final DataSource dataSource;
    private final Logger log;
    private final String threadName;
    /** The subject of a lost-connection warning, e.g. {@code pg-notify listen}. */
    private final String lossSubject;
    /** The subject of a failed-work warning, e.g. {@code pg-notify drain}. */
    private final String workSubject;

    PgListenLoop(DataSource dataSource, Logger log, String threadName, String lossSubject,
            String workSubject) {
        this.dataSource = dataSource;
        this.log = log;
        this.threadName = threadName;
        this.lossSubject = lossSubject;
        this.workSubject = workSubject;
    }

    /** One connected session: LISTEN and work until {@link #running()} turns false. */
    abstract void session(Connection connection) throws SQLException;

    /** The start-up log line, once the thread is running. */
    abstract void started();

    final boolean running() {
        return running;
    }

    @Override
    public final void start() {
        running = true;
        thread = new Thread(this::run, threadName);
        thread.setDaemon(true);
        thread.start();
        started();
    }

    @Override
    public final void stop() {
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
                Connection conn = dataSource.getConnection();
                connection = conn;
                try {
                    session(conn);
                } finally {
                    // Every exit releases the dedicated LISTEN connection, because the next
                    // attempt opens a fresh one; skipping this on a thrown drain orphaned one
                    // pooled connection per reconnect cycle, out of the app's main pool.
                    closeQuietly(conn);
                    connection = null;
                }
            } catch (SQLException ex) {
                if (!running) {
                    return;
                }
                log.warn("{} connection lost; reconnecting: {}", lossSubject, ex.getMessage());
                sleep(RECONNECT_DELAY_MS);
            } catch (RuntimeException ex) {
                // Without this arm, one unchecked exception ends the service for the life of
                // the process.
                if (!running) {
                    return;
                }
                log.warn("{} failed; continuing: {}", workSubject, ex.getMessage());
                sleep(RECONNECT_DELAY_MS);
            }
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
                // best effort: the thread is ending or reconnecting
            }
        }
    }
}
