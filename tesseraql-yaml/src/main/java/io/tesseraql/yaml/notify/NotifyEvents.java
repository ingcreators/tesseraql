package io.tesseraql.yaml.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.expr.Expr;
import io.tesseraql.core.expr.ExpressionParser;
import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.yaml.model.NotifySpec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles {@code notify:} declarations and encodes them as transactional outbox events (roadmap
 * Phase 20).
 *
 * <p>A notification rides the outbox as a {@code NOTIFICATION} event whose payload JSON is the
 * envelope {@code {"channel": ..., "source": ..., "payload": {...}}}: the channel to deliver
 * through, the declaring route/job and notification id, and the declared payload resolved against
 * the execution context. The notification sink decodes the envelope and delivers through the
 * configured channel, so at-least-once, retries, and dead-letters all come from the outbox.
 */
public final class NotifyEvents {

    /** The outbox event type carrying a notification envelope. */
    public static final String EVENT_TYPE = "NOTIFICATION";
    /** The aggregate type tagged on notification events. */
    public static final String AGGREGATE_TYPE = "Notification";

    /** TQL-FIELD-2004: an invalid notify declaration (fails fast at build time). */
    public static final TqlErrorCode INVALID_NOTIFY = new TqlErrorCode(TqlDomain.FIELD, 2004);
    private static final TqlErrorCode ENCODE_ERROR = new TqlErrorCode(TqlDomain.BATCH, 5302);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NotifyEvents() {
    }

    /**
     * Compiles one declaration, failing fast on a missing channel or a malformed guard.
     *
     * @param source the declaring route or job id
     * @param id     the notification id within the declaring {@code notify:} block or pipeline
     */
    public static CompiledNotify compile(String source, String id, NotifySpec spec) {
        if (spec.channel() == null || spec.channel().isBlank()) {
            throw new TqlException(INVALID_NOTIFY,
                    "Notification '" + id + "' of '" + source + "' needs a channel:");
        }
        Expr when = spec.when() == null || spec.when().isBlank()
                ? null
                : ExpressionParser.parse(spec.when());
        return new CompiledNotify(source + "." + id, id, spec.channel(), when, spec.payload());
    }

    /**
     * A compiled notification: the guard parsed, the payload expressions kept for per-event
     * resolution against the live execution context.
     */
    public record CompiledNotify(String source, String id, String channel, Expr when,
            Map<String, String> payload) {

        /** Whether the guard (if any) lets this notification fire for the given context. */
        public boolean fires(Map<String, Object> context) {
            return when == null
                    || when.evalBoolean(new EvaluationContext(context == null
                            ? Map.of()
                            : context));
        }

        /** Resolves the declared payload expressions against the execution context. */
        public Map<String, Object> resolvePayload(Map<String, Object> context) {
            EvaluationContext evaluation = new EvaluationContext(context == null
                    ? Map.of()
                    : context);
            Map<String, Object> resolved = new LinkedHashMap<>();
            payload.forEach((key, expr) -> resolved.put(key,
                    evaluation.resolve(Arrays.asList(expr.split("\\.")))));
            return resolved;
        }

        /** Builds the insertable outbox event carrying the notification envelope. */
        public OutboxEvent build(Map<String, Object> context, String appName) {
            return event(channel, source, resolvePayload(context), appName);
        }
    }

    /**
     * Builds an insertable notification event directly — operations alerts (job failures,
     * threshold breaches) enqueue through here, riding the same channels as {@code notify:}.
     */
    public static OutboxEvent event(String channel, String source, Map<String, Object> payload,
            String appName) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("channel", channel);
        envelope.put("source", source);
        envelope.put("payload", payload == null ? Map.of() : payload);
        try {
            return OutboxEvent.toInsert(AGGREGATE_TYPE, source, EVENT_TYPE,
                    MAPPER.writeValueAsString(envelope), appName);
        } catch (JsonProcessingException ex) {
            throw new TqlException(ENCODE_ERROR,
                    "Failed to encode notification '" + source + "': " + ex.getMessage());
        }
    }

    /** Whether an outbox event carries a notification envelope. */
    public static boolean isNotification(OutboxEvent event) {
        return EVENT_TYPE.equals(event.eventType());
    }

    /** Decodes a notification event's envelope. */
    public static Envelope parse(String payloadJson) {
        try {
            Map<?, ?> raw = MAPPER.readValue(payloadJson == null ? "{}" : payloadJson, Map.class);
            Map<String, Object> payload = new LinkedHashMap<>();
            if (raw.get("payload") instanceof Map<?, ?> declared) {
                declared.forEach((key, value) -> payload.put(String.valueOf(key), value));
            }
            return new Envelope(string(raw.get("channel")), string(raw.get("source")), payload);
        } catch (JsonProcessingException ex) {
            throw new TqlException(ENCODE_ERROR,
                    "Failed to decode notification envelope: " + ex.getMessage());
        }
    }

    /** A decoded notification envelope. */
    public record Envelope(String channel, String source, Map<String, Object> payload) {

        public Envelope {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    /** Compiles a route's whole {@code notify:} block in its authored order. */
    public static List<CompiledNotify> compileAll(String source, Map<String, NotifySpec> block) {
        List<CompiledNotify> compiled = new java.util.ArrayList<>();
        (block == null ? Map.<String, NotifySpec>of() : block)
                .forEach((id, spec) -> compiled.add(compile(source, id, spec)));
        return List.copyOf(compiled);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
