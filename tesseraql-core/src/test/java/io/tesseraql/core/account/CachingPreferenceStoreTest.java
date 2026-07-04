package io.tesseraql.core.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The read-through TTL cache in front of the preference store (roadmap Phase 48): reads sit
 * on the request path, so a lookup must not cost a query per request — while a local write
 * must be visible immediately.
 */
class CachingPreferenceStoreTest {

    /** An in-memory delegate that counts loads. */
    private static final class CountingStore implements PreferenceStore {
        final Map<String, Map<String, String>> data = new HashMap<>();
        final AtomicInteger loads = new AtomicInteger();

        @Override
        public Map<String, String> preferences(String tenantId, String subject) {
            loads.incrementAndGet();
            return data.getOrDefault(subject, Map.of());
        }

        @Override
        public void put(String tenantId, String subject, String key, String value) {
            data.computeIfAbsent(subject, s -> new HashMap<>()).put(key, value);
        }

        @Override
        public void remove(String tenantId, String subject, String key) {
            data.getOrDefault(subject, new HashMap<>()).remove(key);
        }
    }

    @Test
    void readsAreCachedWithinTheTtlAndReloadAfterIt() {
        CountingStore delegate = new CountingStore();
        delegate.put(null, "alice", "ui.theme", "dark");
        AtomicLong clock = new AtomicLong(0);
        CachingPreferenceStore store = new CachingPreferenceStore(delegate, 30_000, 8,
                clock::get);

        assertThat(store.preferences(null, "alice")).containsEntry("ui.theme", "dark");
        assertThat(store.preferences(null, "alice")).containsEntry("ui.theme", "dark");
        assertThat(delegate.loads.get()).isEqualTo(1);

        clock.set(30_001);
        assertThat(store.preferences(null, "alice")).containsEntry("ui.theme", "dark");
        assertThat(delegate.loads.get()).isEqualTo(2);
    }

    @Test
    void aLocalWriteIsVisibleImmediately() {
        CountingStore delegate = new CountingStore();
        AtomicLong clock = new AtomicLong(0);
        CachingPreferenceStore store = new CachingPreferenceStore(delegate, 30_000, 8,
                clock::get);

        assertThat(store.preferences(null, "alice")).isEmpty();
        store.put(null, "alice", "ui.locale", "ja");
        assertThat(store.preferences(null, "alice")).containsEntry("ui.locale", "ja");

        store.remove(null, "alice", "ui.locale");
        assertThat(store.preferences(null, "alice")).isEmpty();
    }

    @Test
    void theCacheStaysBoundedByEvictingLeastRecentlyUsedSubjects() {
        CountingStore delegate = new CountingStore();
        AtomicLong clock = new AtomicLong(0);
        CachingPreferenceStore store = new CachingPreferenceStore(delegate, 60_000, 2,
                clock::get);

        store.preferences(null, "a");
        store.preferences(null, "b");
        store.preferences(null, "a");
        store.preferences(null, "c"); // evicts b (least recently used)
        assertThat(delegate.loads.get()).isEqualTo(3);

        store.preferences(null, "a"); // still cached
        assertThat(delegate.loads.get()).isEqualTo(3);
        store.preferences(null, "b"); // was evicted — loads again
        assertThat(delegate.loads.get()).isEqualTo(4);
    }
}
