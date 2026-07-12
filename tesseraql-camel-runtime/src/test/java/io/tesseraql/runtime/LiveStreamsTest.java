package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
    void closingUnregistersSoLaterSignalsAreNoOps() throws Exception {
        LiveStreams streams = new LiveStreams();
        String key = LiveStreams.topicKey(null, "a");
        var sub = streams.subscribe("alice", List.of(key));
        sub.close();
        streams.signal(key);
        // A closed subscription only reports IDLE on timeout — nothing was queued for it.
        assertThat(sub.await(SHORT)).isEqualTo(LiveStreams.IDLE);
    }
}
