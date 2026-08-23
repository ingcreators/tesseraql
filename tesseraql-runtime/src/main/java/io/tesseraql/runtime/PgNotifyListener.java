package io.tesseraql.runtime;

import io.tesseraql.operations.messaging.JdbcEventChannelStore;
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
 * <p>The connection, thread, reconnect and stop lifecycle is {@link PgListenLoop}'s; on a
 * connection loss (a database restart) the loop reconnects, this session re-listens, and drains
 * to catch up — the same recovery the backstop would eventually provide, only sooner.
 */
final class PgNotifyListener extends PgListenLoop {

    private static final Logger LOG = LoggerFactory.getLogger(PgNotifyListener.class);

    private final QueueConsumer consumer;
    private final long backstopMillis;
    private final Set<String> channels = new LinkedHashSet<>();

    PgNotifyListener(DataSource dataSource, QueueConsumer consumer, long backstopMillis) {
        super(dataSource, LOG, "tql-pg-notify", "pg-notify listen", "pg-notify drain");
        this.consumer = consumer;
        this.backstopMillis = backstopMillis;
        consumer.subscriptions().forEach(s -> channels.add(s.channel()));
    }

    @Override
    void started() {
        LOG.info("pg-notify consumer listening on {} channel(s)", channels.size());
    }

    @Override
    void session(Connection conn) throws SQLException {
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
        while (running()) {
            // Blocks up to the backstop interval, returning early when a NOTIFY arrives. Either
            // way we drain: a notification means new work, a timeout is the periodic sweep.
            pg.getNotifications((int) backstopMillis);
            if (!running()) {
                return;
            }
            consumer.drainAll();
        }
    }
}
