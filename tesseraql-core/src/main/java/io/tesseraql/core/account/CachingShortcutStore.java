package io.tesseraql.core.account;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * A bounded TTL cache over the PIN list (roadmap Phase 51): the sidebar's Pinned group
 * reads it on every page render, so it must cost a map lookup — the inbox badge's exact
 * trade-off (local writes refresh at once, other nodes lag by at most the TTL). Recents
 * and mutations pass through; only the hot read is cached.
 */
public final class CachingShortcutStore implements ShortcutStore {

    private static final int DEFAULT_MAX_SUBJECTS = 512;
    private static final long DEFAULT_TTL_MILLIS = 15_000;

    private final ShortcutStore delegate;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Cached> cache;

    /** Named {@code Cached} — a nested {@code Entry} shadows {@code Map.Entry} on Java 25. */
    private record Cached(List<Shortcut> pins, long loadedAt) {
    }

    public CachingShortcutStore(ShortcutStore delegate) {
        this(delegate, DEFAULT_TTL_MILLIS, DEFAULT_MAX_SUBJECTS, System::currentTimeMillis);
    }

    /** Test seam: injectable clock, explicit bounds. */
    public CachingShortcutStore(ShortcutStore delegate, long ttlMillis, int maxSubjects,
            LongSupplier clock) {
        this.delegate = delegate;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                return size() > maxSubjects;
            }
        };
    }

    @Override
    public List<Shortcut> list(String tenantId, String subject, String kind, int limit) {
        if (!PIN.equals(kind)) {
            return delegate.list(tenantId, subject, kind, limit);
        }
        String key = cacheKey(tenantId, subject);
        long now = clock.getAsLong();
        synchronized (cache) {
            Cached cached = cache.get(key);
            if (cached != null && now - cached.loadedAt() < ttlMillis) {
                return cached.pins();
            }
        }
        List<Shortcut> pins = List.copyOf(delegate.list(tenantId, subject, PIN, limit));
        synchronized (cache) {
            cache.put(key, new Cached(pins, now));
        }
        return pins;
    }

    @Override
    public void put(String tenantId, String subject, String kind, String href, String label,
            int cap) {
        delegate.put(tenantId, subject, kind, href, label, cap);
        if (PIN.equals(kind)) {
            invalidate(tenantId, subject);
        }
    }

    @Override
    public boolean remove(String tenantId, String subject, String kind, String href) {
        boolean removed = delegate.remove(tenantId, subject, kind, href);
        if (PIN.equals(kind)) {
            invalidate(tenantId, subject);
        }
        return removed;
    }

    private void invalidate(String tenantId, String subject) {
        synchronized (cache) {
            cache.remove(cacheKey(tenantId, subject));
        }
    }

    private static String cacheKey(String tenantId, String subject) {
        return (tenantId == null ? "" : tenantId) + ' ' + subject;
    }
}
