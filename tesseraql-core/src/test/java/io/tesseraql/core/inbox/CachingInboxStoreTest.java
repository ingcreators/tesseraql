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
}
