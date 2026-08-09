package io.tesseraql.runtime;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.messaging.EventChannelStore;
import io.tesseraql.core.messaging.EventMessage;
import java.util.List;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;

/**
 * Drains a messaging channel's durable log into the {@code queue-consume} routes that subscribe to
 * it (roadmap Phase 27). It claims a batch of messages ({@code FOR UPDATE SKIP LOCKED}, so several
 * nodes never deliver the same message), sends each into the route's {@code direct:queue.<id>}
 * pipeline, and on success marks it consumed together with its idempotency key — at-least-once
 * delivery, deduplicated to effectively exactly-once per business key. A failed delivery is recorded
 * for a later retry until the dead-letter ceiling.
 *
 * <p>Draining is triggered by the {@link PgNotifyListener} the instant an event is published, and by
 * the same listener's poll timeout as a backstop, so a missed wake never strands a message.
 *
 * <p>A failed delivery is never silent (docs/silent-tolerance.md O1): every failure logs at warn,
 * and one that crosses the dead-letter ceiling additionally counts on the
 * {@code tesseraql.queue.deadletters} meter — the log-and-meter half of the {@code OutboxDispatcher}
 * triad; the status row and the console alert are the store's and the dashboard's halves.
 */
final class QueueConsumer {

    /** Messages claimed per round; a drain loops rounds until the channel/topic is empty. */
    private static final int BATCH = 100;
    /** A safety cap on rounds per drain, so a busy channel never hogs the listener thread. */
    private static final int MAX_ROUNDS = 50;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory
            .getLogger(QueueConsumer.class);

    private final CamelContext context;
    private final EventChannelStore store;
    private final List<Subscription> subscriptions;
    private final int maxAttempts;
    private io.tesseraql.core.telemetry.Meter meter = io.tesseraql.core.telemetry.NoopMeter.INSTANCE;
    private volatile ProducerTemplate template;

    /** One queue-consume route subscribing to a channel/topic, fed via its direct: endpoint. */
    record Subscription(String channel, String topic, String routeId) {
    }

    QueueConsumer(CamelContext context, EventChannelStore store, List<Subscription> subscriptions,
            int maxAttempts) {
        this.context = context;
        this.store = store;
        this.subscriptions = List.copyOf(subscriptions);
        this.maxAttempts = maxAttempts;
    }

    List<Subscription> subscriptions() {
        return subscriptions;
    }

    /** Wires the meter that counts dead-lettered messages; defaults to the no-op meter. */
    QueueConsumer meter(io.tesseraql.core.telemetry.Meter meter) {
        this.meter = meter;
        return this;
    }

    /** Drains every subscription (the backstop and the initial catch-up call both use this). */
    void drainAll() {
        for (Subscription subscription : subscriptions) {
            drain(subscription);
        }
    }

    /** Drains the subscriptions of one channel (a NOTIFY on that channel wakes only these). */
    void drainChannel(String channel) {
        for (Subscription subscription : subscriptions) {
            if (subscription.channel().equals(channel)) {
                drain(subscription);
            }
        }
    }

    private void drain(Subscription subscription) {
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<EventMessage> claimed = store.claim(subscription.channel(), subscription.topic(),
                    BATCH);
            if (claimed.isEmpty()) {
                return;
            }
            for (EventMessage message : claimed) {
                deliver(subscription, message);
            }
        }
    }

    private void deliver(Subscription subscription, EventMessage message) {
        try {
            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setBody(message.payloadJson());
            exchange.getMessage().setHeader(TesseraqlProperties.QUEUE_MESSAGE_KEY, message.key());
            Exchange result = template().send("direct:queue." + subscription.routeId(), exchange);
            if (result.getException() != null) {
                failed(subscription, message, result.getException().getMessage());
                return;
            }
            // The dedup processor stashed the resolved idempotency key; recording it with the
            // message's consumed state (one transaction in the store) is what deduplicates replays.
            String idemKey = result.getProperty(TesseraqlProperties.QUEUE_IDEM_KEY, String.class);
            store.markConsumed(message.id(), subscription.channel(), subscription.topic(), idemKey);
        } catch (RuntimeException ex) {
            failed(subscription, message, ex.getMessage());
        }
    }

    /**
     * Records a failed delivery, loudly: a message crossing the dead-letter ceiling is the one an
     * operator must learn about from the runtime, not from the business — it stops retrying and
     * only a console redelivery brings it back.
     */
    private void failed(Subscription subscription, EventMessage message, String error) {
        // message.attempts() counts completed attempts; this failure is one more.
        if (message.attempts() + 1 >= maxAttempts) {
            LOG.warn("Queue delivery failed for {} on {}/{} ({} attempts); dead-lettering: {}",
                    message.id(), subscription.channel(), subscription.topic(),
                    message.attempts() + 1, error);
            meter.counter("tesseraql.queue.deadletters").increment(java.util.Map.of(
                    "channel", subscription.channel(), "topic", subscription.topic()));
        } else {
            LOG.warn("Queue delivery failed for {} on {}/{}: {}", message.id(),
                    subscription.channel(), subscription.topic(), error);
        }
        store.markFailed(message.id(), error, maxAttempts);
    }

    /**
     * The send template, created once and owned by the context.
     *
     * <p>It was created lazily and never stopped: a template holds a producer cache, so an app
     * close or a reload left its endpoints and their connections behind. Handing it to the
     * context as a service is the fix that needs no close path in either owner — a
     * {@code PgNotifyListener} and a route builder — because stopping the context stops it.
     *
     * <p>Synchronized because the check-then-create was not: two threads reaching an unset field
     * both built a template, and whichever lost the race was leaked silently — the same object
     * that was already never being closed.
     */
    private synchronized ProducerTemplate template() {
        if (template == null) {
            ProducerTemplate created = context.createProducerTemplate();
            try {
                context.addService(created);
            } catch (Exception cannotRegister) {
                throw new IllegalStateException(
                        "Could not register the queue consumer's producer template",
                        cannotRegister);
            }
            template = created;
        }
        return template;
    }
}
