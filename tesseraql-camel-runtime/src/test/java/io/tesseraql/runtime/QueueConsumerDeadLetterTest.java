package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.messaging.ChannelEvent;
import io.tesseraql.core.messaging.EventChannelStore;
import io.tesseraql.core.messaging.EventMessage;
import io.tesseraql.core.telemetry.AggregatingMeter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;

/**
 * A failed queue delivery is never silent (docs/silent-tolerance.md O1): the consumer still
 * records the failure in the store, and one that crosses the dead-letter ceiling counts on the
 * {@code tesseraql.queue.deadletters} meter — the observable half the {@code OutboxDispatcher}
 * always had and this consumer did not. The delivery fails naturally here: no
 * {@code direct:queue.<id>} route exists, exactly the shape of a consumer pipeline that throws.
 */
class QueueConsumerDeadLetterTest {

    /** A one-message store recording what the consumer marked failed. */
    private static final class OneMessageStore implements EventChannelStore {
        private final EventMessage message;
        String failedId;
        String failedError;
        private boolean served;

        private OneMessageStore(int attempts) {
            this.message = new EventMessage("m-1", "events", "items.changed", "k", "{}", attempts);
        }

        @Override
        public String publish(String channel, String topic, String key, String payloadJson,
                String appName) {
            return "0";
        }

        @Override
        public List<EventMessage> claim(String channel, String topic, int limit) {
            if (served) {
                return List.of();
            }
            served = true;
            return List.of(message);
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
            failedId = messageId;
            failedError = error;
        }

        @Override
        public List<ChannelEvent> recent(int limit) {
            return List.of();
        }

        @Override
        public Map<String, Integer> countByStatus() {
            return Map.of();
        }

        @Override
        public Optional<ChannelEvent> find(String messageId) {
            return Optional.empty();
        }

        @Override
        public boolean redeliver(String messageId) {
            return false;
        }
    }

    private static long deadLetters(AggregatingMeter meter) {
        return meter.counterSnapshot()
                .getOrDefault("tesseraql.queue.deadletters", List.of()).stream()
                .mapToLong(AggregatingMeter.CounterSample::value).sum();
    }

    private static OneMessageStore drainOnce(int attempts, AggregatingMeter meter)
            throws Exception {
        OneMessageStore store = new OneMessageStore(attempts);
        DefaultCamelContext context = new DefaultCamelContext();
        context.start();
        try {
            new QueueConsumer(context,
                    store,
                    List.of(new QueueConsumer.Subscription("events", "items.changed",
                            "items.route")),
                    3)
                    .meter(meter)
                    .drainAll();
        } finally {
            context.stop();
        }
        return store;
    }

    @Test
    void aFailureCrossingTheCeilingCountsOnTheDeadLetterMeter() throws Exception {
        AggregatingMeter meter = new AggregatingMeter();
        // Two completed attempts, ceiling of three: this failure is the dead-lettering one.
        OneMessageStore store = drainOnce(2, meter);

        assertThat(store.failedId).isEqualTo("m-1");
        assertThat(store.failedError).isNotBlank();
        assertThat(deadLetters(meter)).isEqualTo(1);
    }

    @Test
    void aFailureBelowTheCeilingIsRecordedButNotCountedAsDead() throws Exception {
        AggregatingMeter meter = new AggregatingMeter();
        OneMessageStore store = drainOnce(0, meter);

        // The store still records the failure (the retry path), but nothing dead-lettered.
        assertThat(store.failedId).isEqualTo("m-1");
        assertThat(deadLetters(meter)).isZero();
    }
}
