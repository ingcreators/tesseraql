package io.tesseraql.test;

import java.nio.file.Path;
import java.util.Map;

/**
 * The runner's per-case SMTP capture (docs/testing.md, real-send mode): a real in-process SMTP
 * server the production {@link io.tesseraql.yaml.notify.MailNotifier} delivers to over a socket,
 * so a mail case asserts what the message actually carried — recipients, subject, rendered body.
 */
final class MailCapture {

    private final Path appHome;

    MailCapture(Path appHome) {
        this.appHome = appHome;
    }

    /** Mail real-send: the production sender delivers to an in-process SMTP capture. */
    void deliver(io.tesseraql.yaml.notify.NotificationChannels.Channel channel,
            io.tesseraql.yaml.notify.NotifyEvents.Envelope envelope,
            io.tesseraql.core.outbox.OutboxEvent event, Map<String, Object> row) {
        com.icegreen.greenmail.util.GreenMail smtp = new com.icegreen.greenmail.util.GreenMail(
                new com.icegreen.greenmail.util.ServerSetup(0, "127.0.0.1", "smtp"));
        try {
            smtp.start();
            new io.tesseraql.yaml.notify.MailNotifier(appHome).send(channel, envelope, event,
                    "127.0.0.1", smtp.getSmtp().getPort());
            jakarta.mail.internet.MimeMessage[] received = smtp.getReceivedMessages();
            if (received.length == 0) {
                throw new IllegalStateException("No message reached the SMTP capture");
            }
            jakarta.mail.internet.MimeMessage message = received[received.length - 1];
            row.put("delivered", true);
            row.put("to", addresses(message.getRecipients(
                    jakarta.mail.Message.RecipientType.TO)));
            row.put("from", addresses(message.getFrom()));
            row.put("subject", message.getSubject());
            row.put("wireBody", String.valueOf(message.getContent()).trim());
        } catch (Exception ex) {
            throw new IllegalStateException("Mail real-send failed: " + ex.getMessage(), ex);
        } finally {
            smtp.stop();
        }
    }

    /** Plain comma-joined address list, so a row column compares as a simple string. */
    private static String addresses(jakarta.mail.Address[] list) {
        if (list == null) {
            return "";
        }
        return java.util.Arrays.stream(list).map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
