package io.tesseraql.runtime;

import io.tesseraql.core.events.TopicBus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The PostgreSQL cross-node topic bus (docs/realtime.md): a committed command's {@code emit:}
 * signals this node's streams directly and rides {@code pg_notify} to every peer sharing the
 * main database, where the {@link TopicNotifyBridge} forwards it to that node's local hub —
 * so a viewer connected to any node refreshes. The notify is best-effort by design (the
 * command already committed; a lost signal means one badgeless wait, not lost data), so a
 * failure logs and never fails the request. The emitting node hears its own notify echoed
 * back; the per-topic signal coalescing absorbs it.
 */
final class CrossNodeTopicBus implements TopicBus {

    /** The one NOTIFY channel every TesseraQL node listens on for live-view topics. */
    static final String CHANNEL = "tql_live_topics";

    private static final Logger LOG = LoggerFactory.getLogger(CrossNodeTopicBus.class);

    private final LiveStreams local;
    private final DataSource dataSource;

    CrossNodeTopicBus(LiveStreams local, DataSource dataSource) {
        this.local = local;
        this.dataSource = dataSource;
    }

    @Override
    public void emit(String tenantId, String topic) {
        local.emit(tenantId, topic);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement notify = connection
                        .prepareStatement("select pg_notify(?, ?)")) {
            notify.setString(1, CHANNEL);
            notify.setString(2, payload(tenantId, topic));
            notify.execute();
        } catch (SQLException ex) {
            LOG.warn("Cross-node topic notify failed for '{}'; peers converge on reload: {}",
                    topic, ex.getMessage());
        }
    }

    /** {@code tenant|topic}; a topic is a slug (no '|'), so peers split at the LAST bar. */
    static String payload(String tenantId, String topic) {
        return (tenantId == null ? "" : tenantId) + '|' + topic;
    }
}
