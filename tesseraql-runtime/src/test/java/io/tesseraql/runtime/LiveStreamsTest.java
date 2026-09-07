package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The unified live-event hub (docs/inbox.md "Live badge", docs/realtime.md): one mailbox per
 * stream over inbox and topic signal keys, tenant-scoped fan-out, per-key coalescing, and
 * bounded subscriptions that end evicted streams.
 */
class LiveStreamsTest {

    private static final Duration SHORT = Duration.ofMillis(50);

    @Test
    void emitsReachOnlyTheTenantsSubscribedTopics() throws Exception {
        LiveStreams streams = new LiveStreams();
        try (var mine = streams.subscribe("alice",
                List.of(LiveStreams.topicKey("t1", "orders.changed")));
                var otherTopic = streams.subscribe("bob",
                        List.of(LiveStreams.topicKey("t1", "stock.changed")));
                var otherTenant = streams.subscribe("carol",
                        List.of(LiveStreams.topicKey("t2", "orders.changed")))) {
            streams.emit("t1", "orders.changed");

            assertThat(mine.await(SHORT)).isEqualTo(LiveStreams.topicKey("t1", "orders.changed"));
            assertThat(otherTopic.await(SHORT)).isEqualTo(LiveStreams.IDLE);
            assertThat(otherTenant.await(SHORT)).isEqualTo(LiveStreams.IDLE);
        }
    }

    @Test
    void oneMailboxCarriesTheBadgeAndTheTopicsAndCoalescesPerKey() throws Exception {
        LiveStreams streams = new LiveStreams();
        String inbox = LiveStreams.inboxKey(null, "alice");
        String orders = LiveStreams.topicKey(null, "orders.changed");
        try (var sub = streams.subscribe("alice", List.of(inbox, orders))) {
            streams.signal(inbox);
            streams.emit(null, "orders.changed");
            streams.emit(null, "orders.changed");

            assertThat(sub.await(SHORT)).isEqualTo(inbox);
            assertThat(sub.await(SHORT)).isEqualTo(orders);
            // The duplicate orders.changed coalesced away.
            assertThat(sub.await(SHORT)).isEqualTo(LiveStreams.IDLE);
        }
    }

    @Test
    void theSubjectCapEvictsTheOldestStreamWhichEnds() throws Exception {
        LiveStreams streams = new LiveStreams();
        String key = LiveStreams.topicKey(null, "a");
        var first = streams.subscribe("alice", List.of(key));
        for (int i = 0; i < 4; i++) {
            streams.subscribe("alice", List.of(key));
        }
        assertThat(first.await(SHORT)).isEqualTo(LiveStreams.CLOSED);
        // The evicted stream no longer receives signals (its registration is gone).
        streams.signal(key);
        assertThat(first.await(SHORT)).isEqualTo(LiveStreams.CLOSED);
    }

    @Test
    void theGlobalCapRefusesTheNewcomerInsteadOfEndingSomeoneElsesStream() throws Exception {
        // Small configured caps so the test does not open 256 streams.
        LiveStreams streams = new LiveStreams(4, 2);
        String key = LiveStreams.topicKey(null, "a");
        var alice = streams.subscribe("alice", List.of(key));
        streams.subscribe("bob", List.of(key));

        // The registry is full: carol is refused with the coded 503 (contract-bugfixes
        // track I) and alice's stream stays live — a full registry never ends another
        // user's live view.
        assertThatThrownBy(() -> streams.subscribe("carol", List.of(key)))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("TQL-RATE-5030");
        streams.signal(key);
        assertThat(alice.await(SHORT)).isEqualTo(key);
    }

    @Test
    void thePerSubjectEvictionFreesTheSlotTheSameSubjectRefills() throws Exception {
        // At both caps at once, the subject's own eviction frees the slot: the newcomer is
        // the same user, so no refusal — and the freed registration cannot leak (the old
        // global-cap eviction bug this test's predecessor pinned).
        LiveStreams streams = new LiveStreams(1, 2);
        String key = LiveStreams.topicKey(null, "a");
        var aliceOld = streams.subscribe("alice", List.of(key));
        streams.subscribe("bob", List.of(key));

        var aliceNew = streams.subscribe("alice", List.of(key));
        assertThat(aliceOld.await(SHORT)).isEqualTo(LiveStreams.CLOSED);

        aliceNew.close();
        streams.signal(key);
        // Closing must take the stream off the registry; a detached list would keep
        // receiving signals and never return its slot.
        assertThat(aliceNew.await(SHORT)).isEqualTo(LiveStreams.IDLE);
    }

    @Test
    void closingUnregistersSoLaterSignalsAreNoOps() throws Exception {
        LiveStreams streams = new LiveStreams();
        String key = LiveStreams.topicKey(null, "a");
        var sub = streams.subscribe("alice", List.of(key));
        sub.close();
        streams.signal(key);
        // A closed subscription only reports IDLE on timeout — nothing was queued for it.
        assertThat(sub.await(SHORT)).isEqualTo(LiveStreams.IDLE);
    }

    /**
     * A shutdown asks every stream to stop, and a producer already parked wakes at once.
     *
     * <p>This is the half {@code SseRoutes}' guarded cleanup is the belt for. A producer parks on
     * {@code await} for twenty-five seconds and its stream lasts fifteen minutes, and nothing
     * counted it: the edge drains the requests it serves, and an SSE producer is not one of them.
     * So it slept through the whole shutdown and woke into a runtime that had finished closing.
     *
     * <p><b>The subscription is parked before {@code close()} is called, and that is the whole
     * test.</b> Written the other way round — close first, then await — it passes against a
     * {@code close()} that sets the flag and never notifies, because {@code await} checks the flag
     * before it waits and returns without ever parking. Measured: that version of this test was
     * green against a deliberately broken {@code end()}. Production always has the producer parked
     * first, so only this order asks the question that matters.
     */
    @Test
    void closingTheHubWakesAProducerThatIsAlreadyParked() throws Exception {
        LiveStreams streams = new LiveStreams();
        try (var subscription = streams.subscribe("alice",
                List.of(LiveStreams.topicKey(null, "orders")))) {

            var outcome = new java.util.concurrent.atomic.AtomicReference<String>();
            var elapsed = new java.util.concurrent.atomic.AtomicLong();
            Thread producer = new Thread(() -> {
                long from = System.nanoTime();
                try {
                    // Ten seconds is twice the bound asserted below: long enough that a stream
                    // which was never woken cannot satisfy it by luck, short enough that a broken
                    // build says so quickly.
                    outcome.set(subscription.await(Duration.ofSeconds(10)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                elapsed.set(System.nanoTime() - from);
            }, "test-parked-producer");
            producer.start();

            // Wait for it to be genuinely inside wait(), not merely started: closing before it
            // parks would test the flag check again rather than the wake-up.
            long parkedBy = System.currentTimeMillis() + 5_000;
            while (producer.getState() != Thread.State.TIMED_WAITING
                    && System.currentTimeMillis() < parkedBy) {
                Thread.sleep(5);
            }
            assertThat(producer.getState())
                    .as("the producer never parked, so this test would not exercise the wake-up")
                    .isEqualTo(Thread.State.TIMED_WAITING);

            streams.close();
            producer.join(Duration.ofSeconds(30).toMillis());

            assertThat(outcome.get()).isEqualTo(LiveStreams.CLOSED);
            assertThat(Duration.ofNanos(elapsed.get()))
                    .as("a parked producer was left to time out instead of being woken")
                    .isLessThan(Duration.ofSeconds(5));
        }
    }
}
