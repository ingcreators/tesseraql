package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * The {@code publish:} block of a command route (roadmap Phase 27): the command emits a domain
 * event to a messaging channel. Like {@code notify:}, the event is written in the command's
 * transaction as a transactional outbox event, so it is published at-least-once after the commit —
 * an outbound integration that cannot be lost and never escapes a rolled-back command.
 *
 * <p>The {@code channel} names a transport configured under
 * {@code tesseraql.messaging.channels.<name>} (the built-in {@code pg-notify} transport, or a
 * broker added by an opt-in leaf module). The {@code topic} is the logical event name a
 * {@code queue-consume} route subscribes to; {@code key} is an optional partition/ordering key
 * expression. The {@code payload} maps keys to source expressions resolved against the command's
 * execution context, exactly like {@code notify:}.
 *
 * @param channel the configured channel name (required), e.g. {@code events}
 * @param topic   the logical event topic a consumer subscribes to (required), e.g.
 *                {@code orders.created}
 * @param key     optional source expression for the event key (ordering/partitioning); the resolved
 *                value also seeds a consumer's idempotency key when it has none of its own
 * @param payload map of payload key to source expression, resolved against the execution context;
 *                the payload rides the channel and is delivered to subscribers as the message body
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PublishSpec(String channel, String topic, String key, Map<String, String> payload) {

    public PublishSpec {
        payload = payload == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload));
    }
}
