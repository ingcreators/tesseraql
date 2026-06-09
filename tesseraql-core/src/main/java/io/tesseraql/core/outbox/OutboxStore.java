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

    void markSent(String eventId);

    void markFailed(String eventId, String error);
}
