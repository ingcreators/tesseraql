package io.tesseraql.core.messaging;

import java.util.List;

/**
 * The durable event log behind a database-backed messaging channel (roadmap Phase 27): the
 * transactional bus the built-in {@code pg-notify} transport publishes to and consumes from. It is
 * the seam that keeps the {@code queue-consume} consumer transport-agnostic — a broker transport
 * (Kafka, JMS, added by a later leaf module) brings its own client and does not implement this
 * interface.
 *
 * <p>The contract is at-least-once with idempotency-key deduplication. A producer {@link #publish}
 * writes a durable message; a consumer {@link #claim}s a batch (never the same row on two nodes),
 * runs its pipeline, and {@link #markConsumed marks it consumed} together with the business
 * idempotency key, so a redelivery of an already-processed key is recognised by {@link #consumed}
 * and skipped. A failed consume is {@link #markFailed recorded} and retried until the dead-letter
 * ceiling — the durable row, not any transient signal, is what guarantees delivery.
 */
public interface EventChannelStore {

    /** Publishes a durable message to {@code channel}/{@code topic}; returns the new message id. */
    String publish(String channel, String topic, String key, String payloadJson);

    /**
     * Atomically claims up to {@code limit} unconsumed, unclaimed messages of {@code channel}/
     * {@code topic} for this node, so concurrent consumers never deliver the same message. A claim
     * abandoned by a crashed node (claimed longer than the recovery window ago) becomes claimable
     * again, preserving at-least-once delivery.
     */
    List<EventMessage> claim(String channel, String topic, int limit);

    /** Whether {@code idempotencyKey} was already consumed for {@code channel}/{@code topic}. */
    boolean consumed(String channel, String topic, String idempotencyKey);

    /**
     * Marks the message consumed and records the idempotency key in one transaction, so the dedup
     * record and the message's terminal state commit together. A {@code null} key marks the message
     * consumed without a dedup record (no idempotency declared).
     */
    void markConsumed(String messageId, String channel, String topic, String idempotencyKey);

    /**
     * Records a failed delivery: the attempt count increments and the message stays claimable for a
     * later retry, until {@code maxAttempts} is reached, after which it is dead-lettered (stops
     * retrying, stays visible to operators).
     */
    void markFailed(String messageId, String error, int maxAttempts);
}
