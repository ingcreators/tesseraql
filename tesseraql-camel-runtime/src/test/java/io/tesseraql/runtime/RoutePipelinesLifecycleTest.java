package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.messaging.EventChannelStore;
import io.tesseraql.core.messaging.EventMessage;
import java.util.List;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;

/**
 * The pipeline runner is stopped with the context (docs/camel-removal.md decision 1).
 *
 * <p>This guarded a {@code ProducerTemplate} the queue consumer created lazily and never stopped:
 * a template holds a producer cache, so an app close or a reload left its endpoints — and whatever
 * connections they hold — behind, and neither owner had a close path to add one to. The template
 * is gone and the leak is not, because {@link RoutePipelines} caches resolved pipelines and the
 * producers inside them for exactly the same reason. So the property is the same one, asserted of
 * what holds the cache now.
 */
class RoutePipelinesLifecycleTest {

    /**
     * One message, then none: the drain has to reach the run for the runner to exist at all. The
     * run finds no compiled pipeline and the consumer records that failure, which is fine — the
     * point is the runner's lifecycle, not the delivery.
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
    void stoppingTheContextStopsTheRunner() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        context.start();
        QueueConsumer consumer = new QueueConsumer(context, EMPTY,
                List.of(new QueueConsumer.Subscription("events", "items.changed", "items.route")),
                3);

        consumer.drainAll();

        RoutePipelines runner = context.getCamelContextExtension().getServices().stream()
                .filter(RoutePipelines.class::isInstance)
                .map(RoutePipelines.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the pipeline runner is not a service of the context, so nothing will"
                                + " ever stop the producers it resolved"));
        assertThat(runner.isStarted()).isTrue();

        context.stop();

        assertThat(runner.isStarted())
                .as("the runner must stop with the context that owns it")
                .isFalse();
    }
}
