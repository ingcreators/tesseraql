package io.tesseraql.core.inbox;

import java.time.Instant;
import java.util.List;

/**
 * The per-user in-app notification inbox (roadmap Phase 49, design in docs/inbox.md):
 * recipient-addressed {@code notify:} events delivered by the inbox channel type land here,
 * behind the shell's bell. The subject is always the session principal's on the read side —
 * the account surface's construction rule — and delivery dedupes on the outbox event id, so
 * the dispatcher's at-least-once redelivery never doubles a message.
 */
public interface InboxStore {

    /** One delivered message; {@code readAt} is null while unread. */
    record InboxMessage(String eventId, String title, String body, String source,
            Instant createdAt, Instant readAt) {
    }

    /**
     * Delivers one message, idempotently on {@code eventId}: a redelivery of the same outbox
     * event reads as already delivered.
     */
    void deliver(String eventId, String tenantId, String subject, String channel,
            String source, String title, String body);

    /** Unread messages for the subject. */
    int unreadCount(String tenantId, String subject);

    /** The subject's messages, newest first. */
    List<InboxMessage> recent(String tenantId, String subject, int limit);

    /** Marks one of the subject's messages read; false when it is not theirs (or unknown). */
    boolean markRead(String tenantId, String subject, String eventId);

    /** Marks all of the subject's messages read; returns how many changed. */
    int markAllRead(String tenantId, String subject);
}
