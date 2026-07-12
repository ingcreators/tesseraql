package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The live-view topic hub (docs/realtime.md): tenant-scoped fan-out, per-topic coalescing,
 * and bounded subscriptions that end evicted streams.
 */
class TopicStreamsTest {

    private static final Duration SHORT = Duration.ofMillis(50);

    @Test
    void emitsReachOnlyTheTenantsSubscribedTopics() throws Exception {
        TopicStreams streams = new TopicStreams();
        try (var mine = streams.subscribe("t1", "alice", List.of("orders.changed"));
                var otherTopic = streams.subscribe("t1", "bob", List.of("stock.changed"));
                var otherTenant = streams.subscribe("t2", "carol", List.of("orders.changed"))) {
            streams.emit("t1", "orders.changed");

            assertThat(mine.await(SHORT)).isEqualTo("orders.changed");
            assertThat(otherTopic.await(SHORT)).isEqualTo(TopicStreams.IDLE);
            assertThat(otherTenant.await(SHORT)).isEqualTo(TopicStreams.IDLE);
        }
    }

    @Test
    void pendingSignalsCoalescePerTopicAndDistinctTopicsBothFire() throws Exception {
        TopicStreams streams = new TopicStreams();
        try (var sub = streams.subscribe(null, "alice",
                List.of("orders.changed", "stock.changed"))) {
            streams.emit(null, "orders.changed");
            streams.emit(null, "orders.changed");
            streams.emit(null, "stock.changed");

            assertThat(sub.await(SHORT)).isEqualTo("orders.changed");
            assertThat(sub.await(SHORT)).isEqualTo("stock.changed");
            // The duplicate orders.changed coalesced away.
            assertThat(sub.await(SHORT)).isEqualTo(TopicStreams.IDLE);
        }
    }

    @Test
    void theSubjectCapEvictsTheOldestStreamWhichEnds() throws Exception {
        TopicStreams streams = new TopicStreams();
        var first = streams.subscribe(null, "alice", List.of("a"));
        for (int i = 0; i < 4; i++) {
            streams.subscribe(null, "alice", List.of("a"));
        }
        assertThat(first.await(SHORT)).isEqualTo(TopicStreams.CLOSED);
        // The evicted stream no longer receives emits (its registration is gone).
        streams.emit(null, "a");
        assertThat(first.await(SHORT)).isEqualTo(TopicStreams.CLOSED);
    }

    @Test
    void closingUnregistersSoLaterEmitsAreNoOps() throws Exception {
        TopicStreams streams = new TopicStreams();
        var sub = streams.subscribe(null, "alice", List.of("a"));
        sub.close();
        streams.emit(null, "a");
        // A closed subscription only reports IDLE on timeout — nothing was queued for it.
        assertThat(sub.await(SHORT)).isEqualTo(TopicStreams.IDLE);
    }
}
