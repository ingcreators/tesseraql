package io.tesseraql.yaml.notify;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.yaml.template.Templates;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/**
 * Delivers a notification as SMTP mail (roadmap Phase 20) over plain jakarta.mail — the same
 * one-sender symmetry as the {@link WebhookNotifier}, no transport component in between. The
 * body renders the channel's template with the standard engine — the same trust model as page
 * templates: the template is app-authored and confined to the app home, never taken from the
 * payload — and the subject renders inline in TEXT mode. The model exposes {@code payload}
 * (the notification's resolved payload) and {@code event} (id, source, app).
 *
 * <p>Channel settings: {@code host} (required), {@code port} (default 25), {@code transport}
 * ({@code smtp}/{@code smtps}, default smtp), {@code from} and {@code template} (required),
 * {@code to} (default recipient; a {@code to} payload key overrides per notification),
 * {@code subject} (an inline TEXT template), and optional {@code username}/{@code password} —
 * typically {@code ${secret.<provider>.<name>}} via the SecretResolver SPI, resolved at send.
 */
public final class MailNotifier {

    /** TQL-BATCH-5304: a mail channel is misdeclared or its template escapes the app home. */
    private static final TqlErrorCode MAIL_CHANNEL = new TqlErrorCode(TqlDomain.BATCH, 5304);
    /** TQL-BATCH-5303: the mail was not accepted by the server (shared with webhooks). */
    private static final TqlErrorCode DELIVERY_FAILED = new TqlErrorCode(TqlDomain.BATCH, 5303);

    private static final TemplateEngine INLINE = inlineEngine();

    private final Path appHome;

    public MailNotifier(Path appHome) {
        this.appHome = appHome.toAbsolutePath().normalize();
    }

    public void send(NotificationChannels.Channel channel, NotifyEvents.Envelope envelope,
            OutboxEvent event) {
        send(channel, envelope, event, null, null);
    }

    /**
     * Delivery with the destination overridden — the declarative test runner's real-send mode
     * (docs/testing.md): the message — template body, subject, to/from — is built exactly as
     * for the channel's own host, but the wire goes to the runner's capture server (plain
     * SMTP, no TLS, no credentials).
     */
    public void send(NotificationChannels.Channel channel, NotifyEvents.Envelope envelope,
            OutboxEvent event, String hostOverride, Integer portOverride) {
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

        String transport = channel.setting("transport").orElse("smtp");
        if (!"smtp".equals(transport) && !"smtps".equals(transport)) {
            throw new TqlException(MAIL_CHANNEL, "Mail channel '" + channel.name()
                    + "': transport must be smtp or smtps");
        }
        boolean overridden = hostOverride != null;
        String host = overridden ? hostOverride : channel.require("host");
        String port = overridden
                ? String.valueOf(portOverride)
                : channel.setting("port").orElse("25");

        Properties properties = new Properties();
        properties.put("mail.smtp.host", host);
        properties.put("mail.smtp.port", port);
        if (!overridden && "smtps".equals(transport)) {
            properties.put("mail.smtp.ssl.enable", "true");
        }
        String username = overridden ? null : channel.setting("username").orElse(null);
        String password = overridden ? null : channel.setting("password").orElse(null);
        if (username != null) {
            properties.put("mail.smtp.auth", "true");
        }
        Session session = username == null
                ? Session.getInstance(properties)
                : Session.getInstance(properties, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                });
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(channel.require("from")));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject, "UTF-8");
            message.setContent(body, template.endsWith(".html")
                    ? "text/html; charset=UTF-8"
                    : "text/plain; charset=UTF-8");
            Transport.send(message);
        } catch (jakarta.mail.MessagingException ex) {
            // The outbox dispatcher retries and eventually dead-letters the event.
            throw new TqlException(DELIVERY_FAILED, "Mail channel '" + channel.name()
                    + "' delivery failed: " + ex.getMessage());
        }
    }

    private static TemplateEngine inlineEngine() {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.TEXT);
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
