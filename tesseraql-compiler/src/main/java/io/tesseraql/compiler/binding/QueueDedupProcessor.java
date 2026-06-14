package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.messaging.EventChannelStore;
import java.util.Arrays;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Resolves a {@code queue-consume} delivery's idempotency key and recognises a redelivery before
 * the SQL pipeline runs (roadmap Phase 27), mirroring {@link WebhookVerifyProcessor}.
 *
 * <p>It runs after request binding, so the message body is parsed into the execution context. The
 * idempotency key is the route's {@code consume.idempotencyKey} expression resolved against the
 * body (e.g. {@code body.orderId}); when none is declared it falls back to the message key the
 * channel carried. The resolved key is stashed on the exchange so the consumer can record it
 * transactionally when it marks the message consumed.
 *
 * <p>When the key was already consumed for this channel/topic the delivery is a duplicate: the
 * processor flags it and the compiled route short-circuits the pipeline (no row is written), so the
 * at-least-once channel becomes effectively exactly-once per business key. A duplicate is not an
 * error — the consumer still acknowledges the message.
 */
public final class QueueDedupProcessor implements Processor {

    /** TQL-CAMEL-3111: the event channel store backing a queue-consume route is not configured. */
    private static final TqlErrorCode NO_STORE = new TqlErrorCode(TqlDomain.CAMEL, 3111);

    private final String channel;
    private final String topic;
    private final String idempotencyKey;

    public QueueDedupProcessor(String channel, String topic, String idempotencyKey) {
        this.channel = channel;
        this.topic = topic;
        this.idempotencyKey = idempotencyKey;
    }

    @Override
    public void process(Exchange exchange) {
        String key = resolveKey(exchange);
        if (key != null) {
            exchange.setProperty(TesseraqlProperties.QUEUE_IDEM_KEY, key);
        }
        if (key == null) {
            return;
        }
        EventChannelStore store = exchange.getContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.EVENT_CHANNEL_STORE_BEAN, EventChannelStore.class);
        if (store == null) {
            throw new TqlException(NO_STORE, "Event channel store is not configured");
        }
        if (store.consumed(channel, topic, key)) {
            exchange.setProperty(TesseraqlProperties.QUEUE_DUPLICATE, Boolean.TRUE);
        }
    }

    /** The declared idempotency-key expression resolved against the body, else the message key. */
    @SuppressWarnings("unchecked")
    private String resolveKey(Exchange exchange) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return exchange.getMessage().getHeader(TesseraqlProperties.QUEUE_MESSAGE_KEY,
                    String.class);
        }
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.class);
        Object value = new EvaluationContext(context == null ? Map.of() : context)
                .resolve(Arrays.asList(idempotencyKey.split("\\.")));
        return value == null ? null : String.valueOf(value);
    }
}
