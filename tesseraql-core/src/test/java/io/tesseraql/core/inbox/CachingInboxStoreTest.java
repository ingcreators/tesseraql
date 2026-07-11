package io.tesseraql.core.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The unread-count cache behind the shell's bell (roadmap Phase 49): reads within the TTL
 * cost a map lookup, local mutations refresh the badge immediately.
 */
class CachingInboxStoreTest {

    private static final class CountingStore implements InboxStore {
        final AtomicInteger counts = new AtomicInteger();
        final List<String> delivered = new ArrayList<>();
        int unread;

        @Override
        public void deliver(String eventId, String tenantId, String subject, String channel,
                String source, String title, String body) {
            delivered.add(eventId);
            unread++;
        }

        @Override
        public int unreadCount(String tenantId, String subject) {
            counts.incrementAndGet();
            return unread;
        }

        @Override
        public List<InboxMessage> recent(String tenantId, String subject, int limit) {
            return List.of();
        }

        @Override
        public boolean markRead(String tenantId, String subject, String eventId) {
            unread = Math.max(0, unread - 1);
            return true;
        }

        @Override
        public int markAllRead(String tenantId, String subject) {
            int marked = unread;
            unread = 0;
            return marked;
        }
    }

    @Test
    void countsAreCachedWithinTheTtlAndMutationsRefreshImmediately() {
        CountingStore delegate = new CountingStore();
        AtomicLong clock = new AtomicLong(0);
        CachingInboxStore store = new CachingInboxStore(delegate, 15_000, 8, clock::get);

        assertThat(store.unreadCount(null, "u")).isZero();
        assertThat(store.unreadCount(null, "u")).isZero();
        assertThat(delegate.counts.get()).isEqualTo(1);

        store.deliver("e1", null, "u", "c", "s", "t", null);
        assertThat(store.unreadCount(null, "u")).isEqualTo(1);
        assertThat(delegate.counts.get()).isEqualTo(2);

        store.markRead(null, "u", "e1");
        assertThat(store.unreadCount(null, "u")).isZero();
        assertThat(delegate.counts.get()).isEqualTo(3);

        clock.set(15_001);
        store.unreadCount(null, "u");
        assertThat(delegate.counts.get()).isEqualTo(4);
    }

    /**
     * The read-through/invalidation race behind the InboxDeliveryIntegrationTest flake: a slow
     * concurrent reader (the SSE badge pusher reacting to a delivery) finishes its delegate
     * query after a mark-read invalidated the key. Its stale count must be discarded, not
     * cached — otherwise the badge serves the pre-mark-read value for a full TTL.
     */
    @Test
    void aReadThroughThatRacesAnInvalidationDoesNotCacheTheStaleCount() throws Exception {
        var queryEntered = new java.util.concurrent.CountDownLatch(1);
        var invalidated = new java.util.concurrent.CountDownLatch(1);
        CountingStore counting = new CountingStore();
        InboxStore delegate = new InboxStore() {
            @Override
            public void deliver(String eventId, String tenantId, String subject, String channel,
                    String source, String title, String body) {
                counting.deliver(eventId, tenantId, subject, channel, source, title, body);
            }

            @Override
            public int unreadCount(String tenantId, String subject) {
                int unread = counting.unreadCount(tenantId, subject);
                if (queryEntered.getCount() > 0) {
                    queryEntered.countDown();
                    try {
                        // Stall the first read-through until the main thread's markRead ran.
                        invalidated.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    // Re-read: the count the stalled reader returns is the pre-mark-read one.
                }
                return unread;
            }

            @Override
            public List<InboxMessage> recent(String tenantId, String subject, int limit) {
                return counting.recent(tenantId, subject, limit);
            }

            @Override
            public boolean markRead(String tenantId, String subject, String eventId) {
                return counting.markRead(tenantId, subject, eventId);
            }

            @Override
            public int markAllRead(String tenantId, String subject) {
                return counting.markAllRead(tenantId, subject);
            }
        };
        CachingInboxStore store = new CachingInboxStore(delegate, 15_000, 8, () -> 0L);
        store.deliver("e1", null, "u", "c", "s", "t", null);

        Thread badgePusher = new Thread(() -> store.unreadCount(null, "u"));
        badgePusher.start();
        assertThat(queryEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // The slow reader saw unread=1 and is now stalled; the owner marks the message read.
        store.markRead(null, "u", "e1");
        invalidated.countDown();
        badgePusher.join(5_000);

        // The stale 1 must not have been cached over the invalidation: the next read hits the
        // delegate and reports 0.
        assertThat(store.unreadCount(null, "u")).isZero();
    }
}
