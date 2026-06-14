package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code consume:} block of a {@code queue-consume} route (roadmap Phase 27): the route runs
 * its SQL pipeline once for every message delivered on a messaging channel, with at-least-once
 * semantics and idempotency-key deduplication.
 *
 * <p>The {@code channel} names a transport configured under
 * {@code tesseraql.messaging.channels.<name>} (the built-in {@code pg-notify} transport, or a
 * broker added by an opt-in leaf module); {@code topic} is the logical event name a {@code publish:}
 * block emits to. {@code idempotencyKey} is a source expression resolved against the delivered
 * message (e.g. {@code body.orderId}); a redelivery of an already-processed key is a no-op, so
 * at-least-once delivery becomes effectively exactly-once per business key. When omitted, the
 * message key carried by the channel is used.
 *
 * @param channel        the configured channel name (required), e.g. {@code events}
 * @param topic          the logical topic to subscribe to (required), e.g. {@code orders.created}
 * @param idempotencyKey optional source expression resolved against the message body for dedup
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConsumeSpec(String channel, String topic, String idempotencyKey) {
}
