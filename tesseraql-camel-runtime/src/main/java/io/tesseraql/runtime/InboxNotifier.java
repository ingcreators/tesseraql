package io.tesseraql.runtime;

import io.tesseraql.core.inbox.InboxStore;
import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.yaml.notify.NotificationChannels;
import io.tesseraql.yaml.notify.NotifyEvents;
import java.util.LinkedHashMap;
import java.util.Map;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/**
 * Delivers a notification into the per-user inbox (roadmap Phase 49): the third channel
 * type beside mail and webhooks. The channel's {@code title} (and optional {@code body})
 * render inline in TEXT mode against {@code payload} and {@code event} — the mail
 * notifier's trust model: templates are operator-configured, never taken from the payload.
 * An envelope without a recipient throws, so the dispatcher's retry/dead-letter policy
 * surfaces the misaddressing in ops (lint TQL-YAML-1034 prevents it at build time).
 */
final class InboxNotifier {

    private final TemplateEngine engine = inlineEngine();

    void send(NotificationChannels.Channel channel, NotifyEvents.Envelope envelope,
            OutboxEvent event, InboxStore inbox) {
        String recipient = envelope.recipient();
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalStateException("Inbox notification '" + envelope.source()
                    + "' carries no recipient");
        }
        Context context = new Context();
        context.setVariable("payload", envelope.payload());
        Map<String, Object> eventModel = new LinkedHashMap<>();
        eventModel.put("id", event.id());
        eventModel.put("source", envelope.source());
        eventModel.put("app", event.appName());
        context.setVariable("event", eventModel);

        String title = engine.process(
                channel.raw("title").orElse(envelope.source()), context);
        String body = channel.raw("body")
                .map(template -> engine.process(template, context)).orElse(null);
        inbox.deliver(event.id(), envelope.tenant(), recipient, envelope.channel(),
                envelope.source(), truncate(title, 500), truncate(body, 2000));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static TemplateEngine inlineEngine() {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.TEXT);
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
