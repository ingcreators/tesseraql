package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.messaging.EventChannelStore;
import io.tesseraql.core.messaging.EventMessage;
import java.util.List;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;

/**
 * The queue consumer's send template is stopped with the context.
 *
 * <p>It was created lazily and never stopped. A {@link ProducerTemplate} holds a producer cache,
 * so an app close or a reload left its endpoints — and whatever connections they hold — behind.
 * Neither owner had a close path to add one to: a {@code PgNotifyListener} and a route builder.
 * Handing the template to the context as a service is what closes the row without inventing one.
 */
class QueueConsumerTemplateTest {

    /**
     * One message, then none: the drain has to reach the send for the template to exist at all.
     * The send fails (no such route) and the consumer records that, which is fine — the point is
     * the template, not the delivery.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean SERVED = new java.util.concurrent.atomic.AtomicBoolean();

    private static final EventChannelStore EMPTY = new EventChannelStore() {
        @Override
        public String publish(String channel, String topic, String key, String payloadJson,
                String appName) {
            return "0";
        }

        @Override
        public List<EventMessage> claim(String channel, String topic, int limit) {
            return SERVED.compareAndSet(false, true)
                    ? List.of(new EventMessage("1", channel, topic, "k", "{}", 0))
                    : List.of();
        }

        @Override
        public boolean consumed(String channel, String topic, String idempotencyKey) {
            return false;
        }

        @Override
        public void markConsumed(String messageId, String channel, String topic,
                String idempotencyKey) {
        }

        @Override
        public void markFailed(String messageId, String error, int maxAttempts) {
        }

        @Override
        public List<io.tesseraql.core.messaging.ChannelEvent> recent(int limit) {
            return List.of();
        }

        @Override
        public java.util.Map<String, Integer> countByStatus() {
            return java.util.Map.of();
        }

        @Override
        public java.util.Optional<io.tesseraql.core.messaging.ChannelEvent> find(
                String messageId) {
            return java.util.Optional.empty();
        }

        @Override
        public boolean redeliver(String messageId) {
            return false;
        }
    };

    @Test
    void stoppingTheContextStopsTheTemplate() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        context.start();
        QueueConsumer consumer = new QueueConsumer(context, EMPTY,
                List.of(new QueueConsumer.Subscription("events", "items.changed", "items.route")),
                3);

        consumer.drainAll();

        ProducerTemplate template = context.getCamelContextExtension().getServices().stream()
                .filter(ProducerTemplate.class::isInstance)
                .map(ProducerTemplate.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the consumer's template is not a service of the context, so nothing"
                                + " will ever stop it"));
        assertThat(((org.apache.camel.support.service.ServiceSupport) template).isStarted())
                .isTrue();

        context.stop();

        assertThat(((org.apache.camel.support.service.ServiceSupport) template).isStarted())
                .as("the template must stop with the context that owns it")
                .isFalse();
    }
}
