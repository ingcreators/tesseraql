package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.messaging.ChannelEvent;
import io.tesseraql.core.messaging.EventChannelStore;
import io.tesseraql.core.messaging.EventMessage;
import io.tesseraql.core.telemetry.AggregatingMeter;
import io.tesseraql.pipeline.RuntimeContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A failed queue delivery is never silent (docs/silent-tolerance.md O1): the consumer still
 * records the failure in the store, and one that crosses the dead-letter ceiling counts on the
 * {@code tesseraql.queue.deadletters} meter — the observable half the {@code OutboxDispatcher}
 * always had and this consumer did not. The delivery fails naturally here: no
 * {@code direct:queue.<id>} route exists, exactly the shape of a consumer pipeline that throws.
 */
class QueueConsumerDeadLetterTest {

    /** A one-message store recording what the consumer marked failed or consumed. */
    private static final class OneMessageStore implements EventChannelStore {
        private final EventMessage message;
        String failedId;
        String failedError;
        String consumedId;
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
            consumedId = messageId;
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
        RuntimeContext context = new RuntimeContext();
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
            context.close();
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

    /**
     * A failure the route's own error clauses answered is still a failed delivery. The runner
     * clears the exchange's exception before running the envelope (the exception moves to
     * {@code EXCEPTION_CAUGHT}), so a consumer reading only {@code getException()} would mark a
     * rolled-back transaction consumed — silent message loss behind a rendered 500 nobody reads.
     */
    @Test
    void aRenderedFailureIsRecordedAsFailedNotConsumed() throws Exception {
        OneMessageStore store = new OneMessageStore(0);
        RuntimeContext context = new RuntimeContext();
        context.start();
        try {
            // The real consumer pipeline's shape: a step that throws, behind the inherited
            // error clause that renders the failure as a status.
            io.tesseraql.compiler.pipeline.Pipelines.of(context)
                    .compiling(List.of())
                    .pipeline("queue.items.route")
                    .process(exchange -> {
                        throw new IllegalStateException("constraint violation");
                    })
                    .onException(Exception.class, exchange -> exchange.response().status(500));
            new QueueConsumer(context, store,
                    List.of(new QueueConsumer.Subscription("events", "items.changed",
                            "items.route")),
                    3)
                    .drainAll();
        } finally {
            context.close();
        }

        assertThat(store.consumedId).isNull();
        assertThat(store.failedId).isEqualTo("m-1");
        assertThat(store.failedError).contains("constraint violation");
    }

    @Test
    void aSuccessfulDeliveryIsConsumed() throws Exception {
        OneMessageStore store = new OneMessageStore(0);
        RuntimeContext context = new RuntimeContext();
        context.start();
        try {
            io.tesseraql.compiler.pipeline.Pipelines.of(context)
                    .compiling(List.of())
                    .pipeline("queue.items.route")
                    .process(exchange -> exchange.setBody("done"));
            new QueueConsumer(context, store,
                    List.of(new QueueConsumer.Subscription("events", "items.changed",
                            "items.route")),
                    3)
                    .drainAll();
        } finally {
            context.close();
        }

        assertThat(store.consumedId).isEqualTo("m-1");
        assertThat(store.failedId).isNull();
    }
}
