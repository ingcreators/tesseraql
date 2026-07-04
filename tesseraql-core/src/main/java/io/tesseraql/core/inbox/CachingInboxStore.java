package io.tesseraql.core.inbox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * A bounded TTL cache over {@link InboxStore#unreadCount} (roadmap Phase 49): the shell's
 * bell reads the count on every page render, so it must cost a map lookup, not a query.
 * Local mutations — a delivery, a mark-read — invalidate immediately; on other nodes the
 * badge lags by at most the TTL (default 15 s), the preference-store trade-off. Everything
 * except the count passes straight through.
 */
public final class CachingInboxStore implements InboxStore {

    private static final int DEFAULT_MAX_SUBJECTS = 512;
    private static final long DEFAULT_TTL_MILLIS = 15_000;

    private final InboxStore delegate;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Counted> cache;

    /** Named {@code Counted} — a nested {@code Entry} shadows {@code Map.Entry} on Java 25. */
    private record Counted(int unread, long loadedAt) {
    }

    public CachingInboxStore(InboxStore delegate) {
        this(delegate, DEFAULT_TTL_MILLIS, DEFAULT_MAX_SUBJECTS, System::currentTimeMillis);
    }

    /** Test seam: injectable clock, explicit bounds. */
    public CachingInboxStore(InboxStore delegate, long ttlMillis, int maxSubjects,
            LongSupplier clock) {
        this.delegate = delegate;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Counted> eldest) {
                return size() > maxSubjects;
            }
        };
    }

    @Override
    public int unreadCount(String tenantId, String subject) {
        String key = cacheKey(tenantId, subject);
        long now = clock.getAsLong();
        synchronized (cache) {
            Counted counted = cache.get(key);
            if (counted != null && now - counted.loadedAt() < ttlMillis) {
                return counted.unread();
            }
        }
        int unread = delegate.unreadCount(tenantId, subject);
        synchronized (cache) {
            cache.put(key, new Counted(unread, now));
        }
        return unread;
    }

    @Override
    public void deliver(String eventId, String tenantId, String subject, String channel,
            String source, String title, String body) {
        delegate.deliver(eventId, tenantId, subject, channel, source, title, body);
        invalidate(tenantId, subject);
    }

    @Override
    public boolean markRead(String tenantId, String subject, String eventId) {
        boolean marked = delegate.markRead(tenantId, subject, eventId);
        invalidate(tenantId, subject);
        return marked;
    }

    @Override
    public int markAllRead(String tenantId, String subject) {
        int marked = delegate.markAllRead(tenantId, subject);
        invalidate(tenantId, subject);
        return marked;
    }

    @Override
    public List<InboxMessage> recent(String tenantId, String subject, int limit) {
        return delegate.recent(tenantId, subject, limit);
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
