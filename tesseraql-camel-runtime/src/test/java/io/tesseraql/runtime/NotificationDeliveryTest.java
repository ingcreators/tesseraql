package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpServer;
import io.tesseraql.core.notify.HmacSignatures;
import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.notify.NotificationChannels;
import io.tesseraql.yaml.notify.NotifyEvents;
import jakarta.mail.internet.MimeMessage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * Delivery of NOTIFICATION outbox events through the configured channels (roadmap Phase 20):
 * SMTP mail rendered with the standard template engine, and HMAC-signed webhooks. No database —
 * the sink is fed events directly; the outbox ride is covered by the runtime integration tests.
 */
class NotificationDeliveryTest {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(ServerSetupTest.SMTP);

    @TempDir
    static Path appHome;

    static DefaultCamelContext camel;
    static HttpServer receiver;
    static final AtomicReference<Map<String, String>> received = new AtomicReference<>();
    static volatile int receiverStatus = 200;

    @BeforeAll
    static void start() throws Exception {
        camel = new DefaultCamelContext();
        camel.start();
        Files.createDirectories(appHome.resolve("templates/mail"));
        Files.writeString(appHome.resolve("templates/mail/welcome.txt"), """
                Hello [(${payload.userName})],

                your account ([(${payload.email})]) is ready.
                -- [(${event.app})]
                """);
        receiver = HttpServer.create(new InetSocketAddress(0), 0);
        receiver.createContext("/hook", exchange -> {
            Map<String, String> request = new LinkedHashMap<>();
            request.put("body", new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            request.put("signature",
                    exchange.getRequestHeaders().getFirst(HmacSignatures.SIGNATURE_HEADER));
            request.put("timestamp",
                    exchange.getRequestHeaders().getFirst(HmacSignatures.TIMESTAMP_HEADER));
            received.set(request);
            exchange.sendResponseHeaders(receiverStatus, -1);
            exchange.close();
        });
        receiver.start();
    }

    @AfterAll
    static void stop() throws Exception {
        if (camel != null) {
            camel.stop();
        }
        if (receiver != null) {
            receiver.stop(0);
        }
    }

    private static NotificationSink sink(Map<String, Object> channels) {
        AppConfig config = new AppConfig(
                Map.of("tesseraql", Map.of("notifications", Map.of("channels", channels))),
                name -> null);
        return new NotificationSink(NotificationChannels.load(config), appHome, camel, null);
    }

    private static OutboxEvent notification(String channel, Map<String, Object> payload) {
        OutboxEvent toInsert = NotifyEvents.event(channel, "users.register.confirmation",
                payload, "user-admin");
        return new OutboxEvent("evt-1", toInsert.aggregateType(), toInsert.aggregateId(),
                toInsert.eventType(), toInsert.payloadJson(), "PENDING", 0, null, Instant.now(),
                null, toInsert.appName());
    }

    @Test
    void deliversMailRenderedFromTheChannelTemplate() throws Exception {
        NotificationSink sink = sink(Map.of("user-mail", Map.of(
                "type", "mail",
                "host", "localhost",
                "port", MAIL.getSmtp().getPort(),
                "from", "noreply@example.com",
                "to", "fallback@example.com",
                "subject", "Welcome [(${payload.userName})]",
                "template", "templates/mail/welcome.txt")));

        sink.send(notification("user-mail",
                Map.of("userName", "sato", "email", "sato@example.com",
                        "to", "sato@example.com")));

        MAIL.waitForIncomingEmail(1);
        MimeMessage message = MAIL.getReceivedMessages()[0];
        assertThat(message.getSubject()).isEqualTo("Welcome sato");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("sato@example.com");
        assertThat(message.getFrom()[0].toString()).isEqualTo("noreply@example.com");
        String body = GreenMailUtil.getBody(message);
        assertThat(body).contains("Hello sato,").contains("sato@example.com")
                .contains("-- user-admin");
    }

    @Test
    void postsSignedWebhooksTheReceiverCanVerify() throws Exception {
        receiverStatus = 200;
        NotificationSink sink = sink(Map.of("audit-webhook", Map.of(
                "type", "webhook",
                "url", "http://localhost:" + receiver.getAddress().getPort() + "/hook",
                "secret", "hook-secret")));

        sink.send(notification("audit-webhook", Map.of("userName", "sato")));

        Map<String, String> request = received.get();
        assertThat(request).isNotNull();
        assertThat(request.get("body")).contains("\"source\":\"users.register.confirmation\"")
                .contains("\"app\":\"user-admin\"").contains("\"userName\":\"sato\"");
        // The receiver authenticates with the documented scheme: HMAC-SHA256 over ts.body.
        assertThat(HmacSignatures.verify("hook-secret", request.get("timestamp"),
                request.get("body").getBytes(StandardCharsets.UTF_8),
                request.get("signature"))).isTrue();
        assertThat(HmacSignatures.verify("wrong-secret", request.get("timestamp"),
                request.get("body").getBytes(StandardCharsets.UTF_8),
                request.get("signature"))).isFalse();
    }

    @Test
    void aRejectedWebhookThrowsSoTheOutboxRetries() {
        receiverStatus = 500;
        NotificationSink sink = sink(Map.of("audit-webhook", Map.of(
                "type", "webhook",
                "url", "http://localhost:" + receiver.getAddress().getPort() + "/hook")));

        assertThatThrownBy(() -> sink.send(notification("audit-webhook", Map.of())))
                .hasMessageContaining("TQL-BATCH-5303");
        receiverStatus = 200;
    }

    @Test
    void anUnknownChannelThrowsSoTheOutboxRetries() {
        NotificationSink sink = sink(Map.of());

        assertThatThrownBy(() -> sink.send(notification("missing", Map.of())))
                .hasMessageContaining("TQL-BATCH-5301");
    }

    @Test
    void aTemplateOutsideTheAppHomeIsRejected() {
        NotificationSink sink = sink(Map.of("user-mail", Map.of(
                "type", "mail",
                "host", "localhost",
                "port", MAIL.getSmtp().getPort(),
                "from", "noreply@example.com",
                "to", "x@example.com",
                "template", "../outside.txt")));

        assertThatThrownBy(() -> sink.send(notification("user-mail", Map.of())))
                .hasMessageContaining("TQL-BATCH-5304");
    }

    @Test
    void nonNotificationEventsAreLeftForOtherSinks() throws Exception {
        NotificationSink sink = sink(Map.of());

        // Must not throw: a USER_PROVISIONED event is some other sink's business.
        sink.send(new OutboxEvent("evt-2", "User", "sato", "USER_PROVISIONED", "{}",
                "PENDING", 0, null, Instant.now(), null, "user-admin"));
    }
}
