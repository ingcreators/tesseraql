package io.tesseraql.core.outbox;

import java.sql.Connection;
import java.util.List;

/**
 * Persists and dispatches transactional outbox events (design ch. 39.2, 39.3).
 *
 * <p>{@link #insert(Connection, OutboxEvent)} writes on a caller-supplied connection so the event
 * commits atomically with the business change. The dispatcher uses {@link #listPending(int)} and
 * {@link #markSent}/{@link #markFailed} on its own connection.
 */
public interface OutboxStore {

    /** Inserts an event on the given (transactional) connection; returns the new event id. */
    String insert(Connection connection, OutboxEvent event);

    List<OutboxEvent> listPending(int limit);

    /**
     * Atomically claims up to {@code limit} pending events for this dispatcher node, so several
     * nodes polling the same outbox never deliver the same event concurrently (design ch. 39.3).
     * The default falls back to {@link #listPending(int)} (single-node semantics); JDBC stores
     * override it with row-level claiming.
     */
    default List<OutboxEvent> claimPending(int limit) {
        return listPending(limit);
    }

    /**
     * Claims only events emitted by the given apps (plus untagged legacy rows), so runtimes
     * hosting different apps on one shared database never deliver each other's events to the
     * wrong sinks. The default ignores the scope (single-runtime semantics); JDBC stores
     * override it.
     */
    default List<OutboxEvent> claimPending(int limit, java.util.Collection<String> apps) {
        return claimPending(limit);
    }

    void markSent(String eventId);

    void markFailed(String eventId, String error);
}
