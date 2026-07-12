package io.tesseraql.runtime;

import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.core.outbox.OutboxEventSink;
import io.tesseraql.yaml.notify.MailNotifier;
import io.tesseraql.yaml.notify.NotificationChannels;
import io.tesseraql.yaml.notify.NotifyEvents;
import io.tesseraql.yaml.notify.WebhookNotifier;
import java.nio.file.Path;
import org.apache.camel.CamelContext;

/**
 * Delivers {@code NOTIFICATION} outbox events through the configured channels (roadmap Phase 20):
 * SMTP mail or HMAC-signed webhooks. Other event types are left for the sinks composed around
 * this one. A delivery failure (unknown channel, refused mail, non-2xx webhook answer) throws,
 * so the outbox dispatcher's at-least-once retry and dead-letter policy applies.
 */
final class NotificationSink implements OutboxEventSink {

    private final NotificationChannels channels;
    private final MailNotifier mail;
    private final WebhookNotifier webhook = new WebhookNotifier();
    private final InboxNotifier inboxNotifier = new InboxNotifier();
    private final io.tesseraql.core.inbox.InboxStore inbox;

    NotificationSink(NotificationChannels channels, Path appHome, CamelContext camelContext,
            io.tesseraql.core.inbox.InboxStore inbox) {
        this.channels = channels;
        this.mail = new MailNotifier(appHome);
        this.inbox = inbox;
    }

    @Override
    public void send(OutboxEvent event) throws Exception {
        if (!NotifyEvents.isNotification(event)) {
            return;
        }
        NotifyEvents.Envelope envelope = NotifyEvents.parse(event.payloadJson());
        NotificationChannels.Channel channel = channels.require(envelope.channel());
        switch (channel.type()) {
            case NotificationChannels.MAIL -> mail.send(channel, envelope, event);
            case NotificationChannels.WEBHOOK -> webhook.send(channel, envelope, event);
            // Roadmap Phase 49: delivery into the per-user inbox; a throw (no recipient, no
            // store) rides the dispatcher's retry/dead-letter policy like any other channel.
            case NotificationChannels.INBOX -> inboxNotifier.send(channel, envelope, event,
                    java.util.Objects.requireNonNull(inbox, "inbox store not configured"));
            default -> throw new IllegalStateException(
                    "Unsupported channel type " + channel.type());
        }
    }
}
