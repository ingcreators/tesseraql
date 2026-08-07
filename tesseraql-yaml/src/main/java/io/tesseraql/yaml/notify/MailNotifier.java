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
 * {@code subject} (an inline TEXT template), optional {@code username}/{@code password} —
 * typically {@code ${secret.<provider>.<name>}} via the SecretResolver SPI, resolved at send —
 * and {@code maxAttachmentBytes} (default 10485760), the cap an attached transfer must fit.
 *
 * <p>An envelope carrying an {@code attach} transfer id (docs/analytics-experience.md) sends
 * multipart: the rendered body plus the transfer's produced file, read from the transfer store
 * at delivery time through the wired {@link AttachmentSource}. The attachment lives in memory
 * for the send, which is exactly what the size cap bounds.
 */
public final class MailNotifier {

    /** TQL-BATCH-5304: a mail channel is misdeclared or its template escapes the app home. */
    private static final TqlErrorCode MAIL_CHANNEL = new TqlErrorCode(TqlDomain.BATCH, 5304);
    /** TQL-BATCH-5303: the mail was not accepted by the server (shared with webhooks). */
    private static final TqlErrorCode DELIVERY_FAILED = new TqlErrorCode(TqlDomain.BATCH, 5303);

    private static final long DEFAULT_MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;

    private static final TemplateEngine INLINE = inlineEngine();

    /**
     * Opens an attached transfer's produced file — the runtime wires the transfer service's
     * {@code download()}; this module stays free of the operations stack.
     */
    @FunctionalInterface
    public interface AttachmentSource {
        java.util.Optional<io.tesseraql.core.files.FileTransferService.Download> open(
                String transferId);
    }

    private final Path appHome;
    private final AttachmentSource attachments;

    public MailNotifier(Path appHome) {
        this(appHome, null);
    }

    public MailNotifier(Path appHome, AttachmentSource attachments) {
        this.appHome = appHome.toAbsolutePath().normalize();
        this.attachments = attachments;
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
        Session session = session(channel, transport, hostOverride, portOverride);
        String bodyType = template.endsWith(".html")
                ? "text/html; charset=UTF-8"
                : "text/plain; charset=UTF-8";
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(channel.require("from")));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject, "UTF-8");
            if (envelope.attach() == null) {
                message.setContent(body, bodyType);
            } else {
                message.setContent(attached(channel, envelope.attach(), body, bodyType));
            }
            Transport.send(message);
        } catch (jakarta.mail.MessagingException ex) {
            // The outbox dispatcher retries and eventually dead-letters the event.
            throw new TqlException(DELIVERY_FAILED, "Mail channel '" + channel.name()
                    + "' delivery failed: " + ex.getMessage());
        }
    }

    /**
     * The Studio test send (docs/pages-and-mail-lints.md follow-ups): delivers an
     * already-rendered body over the channel's own transport to an explicit recipient —
     * the draft need not be applied first, so the caller renders (draft-aware) and hands
     * the result in. The subject renders exactly like a real delivery (the channel's
     * inline TEXT template against the same model); no attachment, no outbox — a direct,
     * synchronous send whose failure surfaces to the caller.
     */
    public void sendTest(NotificationChannels.Channel channel, Map<String, Object> model,
            String body, boolean html, String to) {
        String subject = INLINE.process(channel.raw("subject").orElse("Test mail"),
                new Context(java.util.Locale.ROOT, model));
        String transport = channel.setting("transport").orElse("smtp");
        if (!"smtp".equals(transport) && !"smtps".equals(transport)) {
            throw new TqlException(MAIL_CHANNEL, "Mail channel '" + channel.name()
                    + "': transport must be smtp or smtps");
        }
        try {
            MimeMessage message = new MimeMessage(session(channel, transport, null, null));
            message.setFrom(new InternetAddress(channel.require("from")));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject, "UTF-8");
            message.setContent(body,
                    html ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8");
            Transport.send(message);
        } catch (jakarta.mail.MessagingException ex) {
            throw new TqlException(DELIVERY_FAILED, "Mail channel '" + channel.name()
                    + "' test delivery failed: " + ex.getMessage());
        }
    }

    /**
     * The channel's SMTP session; a host/port override (the declarative test runner's
     * capture server) is plain SMTP with no TLS and no credentials.
     */
    private static Session session(NotificationChannels.Channel channel, String transport,
            String hostOverride, Integer portOverride) {
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
        return username == null
                ? Session.getInstance(properties)
                : Session.getInstance(properties, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                });
    }

    /**
     * The multipart body: the rendered template plus the attached transfer's file, read from
     * the transfer store at delivery time. An unknown or not-yet-downloadable transfer throws
     * — the dispatcher's retry covers the race with a still-writing export, and a genuinely
     * missing file dead-letters with this message. The size cap is a channel setting because
     * the mail server's limit is the operator's fact, not the framework's.
     */
    private jakarta.mail.Multipart attached(NotificationChannels.Channel channel,
            String transferId, String body, String bodyType)
            throws jakarta.mail.MessagingException {
        if (attachments == null) {
            throw new TqlException(MAIL_CHANNEL, "Mail channel '" + channel.name()
                    + "': the envelope attaches transfer " + transferId
                    + " but no transfer store is wired in this runtime");
        }
        io.tesseraql.core.files.FileTransferService.Download download = attachments
                .open(transferId).orElseThrow(() -> new TqlException(DELIVERY_FAILED,
                        "Mail channel '" + channel.name() + "': attached transfer "
                                + transferId + " has no downloadable file"));
        long maxBytes = channel.setting("maxAttachmentBytes").map(Long::parseLong)
                .orElse(DEFAULT_MAX_ATTACHMENT_BYTES);
        byte[] bytes = readCapped(channel, download, maxBytes);

        jakarta.mail.internet.MimeBodyPart text = new jakarta.mail.internet.MimeBodyPart();
        text.setContent(body, bodyType);
        jakarta.mail.internet.MimeBodyPart file = new jakarta.mail.internet.MimeBodyPart();
        file.setDataHandler(new jakarta.activation.DataHandler(
                new jakarta.mail.util.ByteArrayDataSource(bytes,
                        download.contentType() == null
                                ? "application/octet-stream"
                                : download.contentType())));
        file.setFileName(download.filename());
        jakarta.mail.Multipart multipart = new jakarta.mail.internet.MimeMultipart();
        multipart.addBodyPart(text);
        multipart.addBodyPart(file);
        return multipart;
    }

    private static byte[] readCapped(NotificationChannels.Channel channel,
            io.tesseraql.core.files.FileTransferService.Download download, long maxBytes) {
        try (java.io.InputStream in = download.content()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new TqlException(MAIL_CHANNEL, "Mail channel '" + channel.name()
                            + "': attachment '" + download.filename() + "' exceeds "
                            + maxBytes + " bytes (raise maxAttachmentBytes on the channel"
                            + " to allow it)");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (java.io.IOException ex) {
            throw new TqlException(DELIVERY_FAILED, "Mail channel '" + channel.name()
                    + "': attachment read failed: " + ex.getMessage());
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
