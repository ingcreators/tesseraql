package io.tesseraql.runtime;

import io.tesseraql.core.inbox.InboxStore;
import java.util.List;

/**
 * The single choke point that makes the badge live (docs/inbox.md, "Live badge"): wraps
 * the store the runtime binds — outbox delivery, the inbox page's mark-read routes, and
 * anything else that mutates read state all signal the subject's open event streams
 * automatically, with no per-caller wiring. Reads pass straight through (the caching
 * wrapper below already makes the count a map lookup, and a local mutation invalidates it
 * before the signal fires, so the pushed badge is fresh).
 */
final class NotifyingInboxStore implements InboxStore {

    private final InboxStore delegate;
    private final LiveStreams streams;

    NotifyingInboxStore(InboxStore delegate, LiveStreams streams) {
        this.delegate = delegate;
        this.streams = streams;
    }

    @Override
    public void deliver(String eventId, String tenantId, String subject, String channel,
            String source, String title, String body) {
        delegate.deliver(eventId, tenantId, subject, channel, source, title, body);
        streams.signal(LiveStreams.inboxKey(tenantId, subject));
    }

    @Override
    public int unreadCount(String tenantId, String subject) {
        return delegate.unreadCount(tenantId, subject);
    }

    @Override
    public List<InboxMessage> recent(String tenantId, String subject, int limit) {
        return delegate.recent(tenantId, subject, limit);
    }

    @Override
    public boolean markRead(String tenantId, String subject, String eventId) {
        boolean changed = delegate.markRead(tenantId, subject, eventId);
        if (changed) {
            streams.signal(LiveStreams.inboxKey(tenantId, subject));
        }
        return changed;
    }

    @Override
    public int markAllRead(String tenantId, String subject) {
        int changed = delegate.markAllRead(tenantId, subject);
        if (changed > 0) {
            streams.signal(LiveStreams.inboxKey(tenantId, subject));
        }
        return changed;
    }
}
