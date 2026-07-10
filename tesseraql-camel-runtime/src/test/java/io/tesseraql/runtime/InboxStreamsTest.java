package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The live-badge subscriber registry (docs/inbox.md, "Live badge"): signals fan out to the
 * subject's streams only, coalesce while pending, and the caps evict the oldest stream as
 * a CLOSED signal so its producer ends cleanly.
 */
class InboxStreamsTest {

    private static final Duration NOW = Duration.ofMillis(50);

    @Test
    void aSignalReachesEverySubscriberOfTheSubjectOnly() throws Exception {
        InboxStreams streams = new InboxStreams();
        try (InboxStreams.Subscription mine = streams.subscribe(null, "alice");
                InboxStreams.Subscription other = streams.subscribe(null, "bob")) {
            streams.changed(null, "alice");

            assertThat(mine.await(NOW)).isEqualTo(InboxStreams.Signal.CHANGED);
            assertThat(other.await(NOW)).isEqualTo(InboxStreams.Signal.IDLE);
        }
    }

    @Test
    void pendingSignalsCoalesceIntoOne() throws Exception {
        InboxStreams streams = new InboxStreams();
        try (InboxStreams.Subscription events = streams.subscribe("t1", "alice")) {
            streams.changed("t1", "alice");
            streams.changed("t1", "alice");
            streams.changed("t1", "alice");

            assertThat(events.await(NOW)).isEqualTo(InboxStreams.Signal.CHANGED);
            assertThat(events.await(NOW)).isEqualTo(InboxStreams.Signal.IDLE);
        }
    }

    @Test
    void thePerSubjectCapEvictsTheOldestStreamAsClosed() throws Exception {
        InboxStreams streams = new InboxStreams();
        InboxStreams.Subscription first = streams.subscribe(null, "alice");
        try (InboxStreams.Subscription second = streams.subscribe(null, "alice");
                InboxStreams.Subscription third = streams.subscribe(null, "alice")) {
            // The cap is two per subject: the third subscription evicted the first.
            assertThat(first.await(NOW)).isEqualTo(InboxStreams.Signal.CLOSED);

            streams.changed(null, "alice");
            assertThat(second.await(NOW)).isEqualTo(InboxStreams.Signal.CHANGED);
            assertThat(third.await(NOW)).isEqualTo(InboxStreams.Signal.CHANGED);
        }
    }

    @Test
    void aClosedSubscriptionNoLongerReceivesSignals() throws Exception {
        InboxStreams streams = new InboxStreams();
        InboxStreams.Subscription events = streams.subscribe(null, "alice");
        events.close();

        streams.changed(null, "alice");
        assertThat(events.await(NOW)).isEqualTo(InboxStreams.Signal.IDLE);
    }

    @Test
    void theNotifyingStoreSignalsOnEveryMutationButNotOnReads() throws Exception {
        InboxStreams streams = new InboxStreams();
        io.tesseraql.core.inbox.InboxStore fake = new io.tesseraql.core.inbox.InboxStore() {
            @Override
            public void deliver(String eventId, String tenantId, String subject,
                    String channel, String source, String title, String body) {
            }

            @Override
            public int unreadCount(String tenantId, String subject) {
                return 3;
            }

            @Override
            public java.util.List<InboxMessage> recent(String tenantId, String subject,
                    int limit) {
                return java.util.List.of();
            }

            @Override
            public boolean markRead(String tenantId, String subject, String eventId) {
                return "mine".equals(eventId);
            }

            @Override
            public int markAllRead(String tenantId, String subject) {
                return "alice".equals(subject) ? 2 : 0;
            }
        };
        NotifyingInboxStore store = new NotifyingInboxStore(fake, streams);
        try (InboxStreams.Subscription events = streams.subscribe(null, "alice")) {
            store.deliver("e1", null, "alice", "ch", "s", "t", null);
            assertThat(events.await(NOW)).isEqualTo(InboxStreams.Signal.CHANGED);

            // Reads never signal; a no-op mark-read neither.
            store.unreadCount(null, "alice");
            store.recent(null, "alice", 10);
            store.markRead(null, "alice", "not-mine");
            assertThat(events.await(NOW)).isEqualTo(InboxStreams.Signal.IDLE);

            store.markRead(null, "alice", "mine");
            assertThat(events.await(NOW)).isEqualTo(InboxStreams.Signal.CHANGED);
            store.markAllRead(null, "alice");
            assertThat(events.await(NOW)).isEqualTo(InboxStreams.Signal.CHANGED);
            store.markAllRead(null, "nobody");
            assertThat(events.await(NOW)).isEqualTo(InboxStreams.Signal.IDLE);
        }
    }
}
