package io.tesseraql.core.messaging;

/**
 * One durable message on a messaging channel (roadmap Phase 27): a row of the channel's event log,
 * claimed by a {@code queue-consume} consumer and delivered to its SQL pipeline.
 *
 * @param id          the unique message id (also the dispatcher's claim handle)
 * @param channel     the channel the message was published to
 * @param topic       the logical topic a consumer subscribes to
 * @param key         the optional ordering/idempotency key the publisher set (may be {@code null})
 * @param payloadJson the message body, the JSON the {@code publish:} payload resolved to
 * @param attempts    completed delivery attempts (a failed consume increments it toward the
 *                    dead-letter ceiling)
 */
public record EventMessage(String id, String channel, String topic, String key, String payloadJson,
        int attempts) {
}
