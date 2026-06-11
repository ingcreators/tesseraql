package io.tesseraql.runtime;

import io.tesseraql.compiler.binding.Templates;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.yaml.notify.NotificationChannels;
import io.tesseraql.yaml.notify.NotifyEvents;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/**
 * Delivers a notification as SMTP mail through camel-mail (roadmap Phase 20). The body renders
 * the channel's template with the standard engine — the same trust model as page templates: the
 * template is app-authored and confined to the app home, never taken from the payload — and the
 * subject renders inline in TEXT mode. The model exposes {@code payload} (the notification's
 * resolved payload) and {@code event} (id, source, app).
 *
 * <p>Channel settings: {@code host} (required), {@code port} (default 25), {@code transport}
 * ({@code smtp}/{@code smtps}, default smtp), {@code from} and {@code template} (required),
 * {@code to} (default recipient; a {@code to} payload key overrides per notification),
 * {@code subject} (an inline TEXT template), and optional {@code username}/{@code password} —
 * typically {@code ${secret.<provider>.<name>}} via the SecretResolver SPI, resolved at send.
 */
final class MailNotifier {

    /** TQL-BATCH-5304: a mail channel is misdeclared or its template escapes the app home. */
    private static final TqlErrorCode MAIL_CHANNEL = new TqlErrorCode(TqlDomain.BATCH, 5304);

    private static final TemplateEngine INLINE = inlineEngine();

    private final Path appHome;
    private final ProducerTemplate producer;

    MailNotifier(Path appHome, CamelContext camelContext) {
        this.appHome = appHome.toAbsolutePath().normalize();
        this.producer = camelContext.createProducerTemplate();
    }

    void send(NotificationChannels.Channel channel, NotifyEvents.Envelope envelope,
            OutboxEvent event) {
        String template = channel.require("template");
        Path resolved = appHome.resolve(template).normalize();
        if (!resolved.startsWith(appHome) || !Files.isRegularFile(resolved)) {
            throw new TqlException(MAIL_CHANNEL, "Mail channel '" + channel.name()
                    + "': template '" + template + "' is not a file inside the app home");
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("payload", envelope.payload());
        model.put("event", Map.of(
                "id", event.id() == null ? "" : event.id(),
                "source", envelope.source() == null ? "" : envelope.source(),
                "app", event.appName() == null ? "" : event.appName()));
        String body = Templates.render(appHome,
                appHome.relativize(resolved).toString().replace('\\', '/'), model);
        // The subject is itself an inline TEXT template, so it reads raw: its [(${...})]
        // interpolation must not be mistaken for a config placeholder.
        String subject = INLINE.process(channel.raw("subject").orElse(envelope.source()),
                new Context(java.util.Locale.ROOT, model));

        Object payloadTo = envelope.payload().get("to");
        String to = payloadTo != null ? String.valueOf(payloadTo) : channel.require("to");

        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("To", to);
        headers.put("From", channel.require("from"));
        headers.put("Subject", subject);
        producer.sendBodyAndHeaders(endpoint(channel, template), body, headers);
    }

    /**
     * The camel-mail endpoint for the channel. Credentials ride RAW() endpoint options, which
     * Camel masks in logs and route dumps; an HTML template sends a text/html body.
     */
    private static String endpoint(NotificationChannels.Channel channel, String template) {
        String transport = channel.setting("transport").orElse("smtp");
        if (!"smtp".equals(transport) && !"smtps".equals(transport)) {
            throw new TqlException(MAIL_CHANNEL, "Mail channel '" + channel.name()
                    + "': transport must be smtp or smtps");
        }
        StringBuilder uri = new StringBuilder(transport).append("://")
                .append(channel.require("host"))
                .append(':').append(channel.setting("port").orElse("25"))
                .append("?contentType=").append(template.endsWith(".html")
                        ? "text/html;charset=UTF-8"
                        : "text/plain;charset=UTF-8");
        channel.setting("username")
                .ifPresent(username -> uri.append("&username=RAW(").append(username).append(')'));
        channel.setting("password")
                .ifPresent(password -> uri.append("&password=RAW(").append(password).append(')'));
        return uri.toString();
    }

    private static TemplateEngine inlineEngine() {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.TEXT);
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
