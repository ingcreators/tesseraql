package io.tesseraql.yaml.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.yaml.model.PublishSpec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compiles a {@code publish:} declaration and encodes it as a transactional outbox event (roadmap
 * Phase 27), mirroring {@link io.tesseraql.yaml.notify.NotifyEvents}.
 *
 * <p>A published event rides the outbox as an {@code EVENT} event whose payload JSON is the envelope
 * {@code {"channel", "topic", "key", "source", "payload"}}: the channel to publish through, the
 * logical topic, an optional ordering/idempotency key, the declaring route, and the resolved
 * payload. The channel-publish sink decodes the envelope and publishes through the channel's
 * transport, so at-least-once, retries, and dead-letters all come from the outbox — and a published
 * event never escapes a rolled-back command, because it is written in the command's transaction.
 */
public final class PublishEvents {

    /** The outbox event type carrying a channel-publish envelope. */
    public static final String EVENT_TYPE = "EVENT";
    /** The aggregate type tagged on published events. */
    public static final String AGGREGATE_TYPE = "Event";

    /** TQL-FIELD-2005: an invalid publish declaration (fails fast at build time). */
    public static final TqlErrorCode INVALID_PUBLISH = new TqlErrorCode(TqlDomain.FIELD, 2005);
    private static final TqlErrorCode ENCODE_ERROR = new TqlErrorCode(TqlDomain.BATCH, 5312);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PublishEvents() {
    }

    /** Compiles a route's {@code publish:} block, failing fast on a missing channel or topic. */
    public static CompiledPublish compile(String source, PublishSpec spec) {
        if (spec.channel() == null || spec.channel().isBlank()) {
            throw new TqlException(INVALID_PUBLISH,
                    "publish: of '" + source + "' needs a channel:");
        }
        if (spec.topic() == null || spec.topic().isBlank()) {
            throw new TqlException(INVALID_PUBLISH,
                    "publish: of '" + source + "' needs a topic:");
        }
        return new CompiledPublish(source, spec.channel(), spec.topic(), spec.key(),
                spec.payload());
    }

    /**
     * A compiled publish declaration: the key and payload expressions kept for per-event resolution
     * against the live execution context.
     */
    public record CompiledPublish(String source, String channel, String topic, String key,
            Map<String, String> payload) {

        /** Resolves the declared payload expressions against the execution context. */
        public Map<String, Object> resolvePayload(Map<String, Object> context) {
            EvaluationContext evaluation = new EvaluationContext(
                    context == null ? Map.of() : context);
            Map<String, Object> resolved = new LinkedHashMap<>();
            payload.forEach((name, expr) -> resolved.put(name,
                    evaluation.resolve(Arrays.asList(expr.split("\\.")))));
            return resolved;
        }

        /** Resolves the optional key expression, or {@code null} when none is declared. */
        public String resolveKey(Map<String, Object> context) {
            if (key == null || key.isBlank()) {
                return null;
            }
            Object value = new EvaluationContext(context == null ? Map.of() : context)
                    .resolve(Arrays.asList(key.split("\\.")));
            return value == null ? null : String.valueOf(value);
        }

        /** Builds the insertable outbox event carrying the publish envelope. */
        public OutboxEvent build(Map<String, Object> context, String appName) {
            return event(channel, topic, resolveKey(context), source, resolvePayload(context),
                    appName);
        }
    }

    /** Builds an insertable channel-publish event directly. */
    public static OutboxEvent event(String channel, String topic, String key, String source,
            Map<String, Object> payload, String appName) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("channel", channel);
        envelope.put("topic", topic);
        envelope.put("key", key);
        envelope.put("source", source);
        envelope.put("payload", payload == null ? Map.of() : payload);
        try {
            return OutboxEvent.toInsert(AGGREGATE_TYPE, topic, EVENT_TYPE,
                    MAPPER.writeValueAsString(envelope), appName);
        } catch (JsonProcessingException ex) {
            throw new TqlException(ENCODE_ERROR,
                    "Failed to encode published event '" + source + "': " + ex.getMessage());
        }
    }

    /** Whether an outbox event carries a channel-publish envelope. */
    public static boolean isEvent(OutboxEvent event) {
        return EVENT_TYPE.equals(event.eventType());
    }

    /** Decodes a published event's envelope. */
    public static Envelope parse(String payloadJson) {
        try {
            Map<?, ?> raw = MAPPER.readValue(payloadJson == null ? "{}" : payloadJson, Map.class);
            Map<String, Object> payload = new LinkedHashMap<>();
            if (raw.get("payload") instanceof Map<?, ?> declared) {
                declared.forEach((key, value) -> payload.put(String.valueOf(key), value));
            }
            return new Envelope(string(raw.get("channel")), string(raw.get("topic")),
                    string(raw.get("key")), string(raw.get("source")), payload);
        } catch (JsonProcessingException ex) {
            throw new TqlException(ENCODE_ERROR,
                    "Failed to decode published event envelope: " + ex.getMessage());
        }
    }

    /** The payload serialized as the message body delivered to subscribers. */
    public static String payloadJson(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException ex) {
            throw new TqlException(ENCODE_ERROR,
                    "Failed to encode event payload: " + ex.getMessage());
        }
    }

    /** A decoded channel-publish envelope. */
    public record Envelope(String channel, String topic, String key, String source,
            Map<String, Object> payload) {

        public Envelope {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
