package io.tesseraql.core.outbox;

import java.time.Instant;

/**
 * A transactional outbox event (design ch. 39.3 {@code TQL_OUTBOX_EVENT}).
 *
 * <p>The event is written in the same transaction as the business change, then delivered
 * asynchronously by a dispatcher, giving at-least-once delivery without distributed transactions.
 *
 * @param id            unique event id (assigned on insert)
 * @param aggregateType the aggregate type, e.g. {@code User}
 * @param aggregateId   the aggregate id
 * @param eventType     the event type, e.g. {@code USER_DISABLED}
 * @param payloadJson   the JSON payload
 * @param status        PENDING / SENT / FAILED / DEAD
 * @param attempts      delivery attempt count
 * @param createdAt     creation time
 */
public record OutboxEvent(
        String id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payloadJson,
        String status,
        int attempts,
        Instant createdAt) {

    /** Builds an event to insert (id/status/attempts/createdAt are assigned by the store). */
    public static OutboxEvent toInsert(String aggregateType, String aggregateId,
            String eventType, String payloadJson) {
        return new OutboxEvent(null, aggregateType, aggregateId, eventType, payloadJson, null, 0, null);
    }
}
