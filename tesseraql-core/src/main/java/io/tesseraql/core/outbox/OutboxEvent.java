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
 * @param status        PENDING / SENDING / SENT / FAILED / DEAD
 * @param attempts      delivery attempt count
 * @param lastError     the last delivery error, when a delivery has failed
 * @param createdAt     creation time
 * @param sentAt        successful delivery time, once SENT
 * @param appName       the app that emitted the event (required), so dispatchers and operators
 *                      can scope a shared outbox table per app
 * @param notBefore     the instant before which the dispatcher must not deliver this event, or
 *                      null for "as soon as the dispatcher gets to it" (docs/notifications.md,
 *                      "Scheduled delivery")
 * @param cancelKey     the withdrawal key a later command may name to cancel this event while
 *                      it is still undelivered, or null when it can never be withdrawn
 */
public record OutboxEvent(
        String id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payloadJson,
        String status,
        int attempts,
        String lastError,
        Instant createdAt,
        Instant sentAt,
        String appName,
        Instant notBefore,
        String cancelKey) {

    /** Whether this event is due at the given instant — an unscheduled event always is. */
    public boolean isDue(Instant now) {
        return notBefore == null || !notBefore.isAfter(now);
    }

    /**
     * Builds an event to insert, tagged with the emitting app (id/status/attempts/createdAt are
     * assigned by the store). Every event carries its app: dispatch scoping and per-app
     * operations depend on it, and the column is not null.
     */
    public static OutboxEvent toInsert(String aggregateType, String aggregateId,
            String eventType, String payloadJson, String appName) {
        return toInsert(aggregateType, aggregateId, eventType, payloadJson, appName, null, null);
    }

    /**
     * The same, scheduled: {@code notBefore} holds the event back until its time comes, and
     * {@code cancelKey} is the name a later command can withdraw it by while it is still
     * undelivered. Both are properties of the row, so they are written in the business
     * transaction like everything else about it.
     */
    public static OutboxEvent toInsert(String aggregateType, String aggregateId,
            String eventType, String payloadJson, String appName, Instant notBefore,
            String cancelKey) {
        java.util.Objects.requireNonNull(appName, "appName");
        return new OutboxEvent(null, aggregateType, aggregateId, eventType, payloadJson,
                null, 0, null, null, null, appName, notBefore, cancelKey);
    }
}
