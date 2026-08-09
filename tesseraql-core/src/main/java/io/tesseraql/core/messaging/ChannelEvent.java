package io.tesseraql.core.messaging;

import java.time.Instant;

/**
 * One channel message with its delivery state (docs/silent-tolerance.md O1): the operator's view of
 * a {@code tql_event} row, the messaging counterpart of {@link io.tesseraql.core.outbox.OutboxEvent}.
 * Where {@link EventMessage} is the slice a consumer needs to run its pipeline, this record carries
 * the status, error, and timestamps the operations console and its dead-letter alert render.
 *
 * @param id          the unique message id
 * @param channel     the channel the message was published to
 * @param topic       the logical topic a consumer subscribes to
 * @param key         the optional ordering/idempotency key the publisher set (may be {@code null})
 * @param appName     the app that published the message, so operators scope a shared event table
 *                    per app (may be {@code null} for rows published before app tagging)
 * @param status      PENDING / CLAIMED / CONSUMED / DEAD
 * @param attempts    completed delivery attempts
 * @param lastError   the last delivery error, when a delivery has failed
 * @param publishedAt publication time
 * @param consumedAt  successful consumption time, once CONSUMED
 */
public record ChannelEvent(
        String id,
        String channel,
        String topic,
        String key,
        String appName,
        String status,
        int attempts,
        String lastError,
        Instant publishedAt,
        Instant consumedAt) {
}
