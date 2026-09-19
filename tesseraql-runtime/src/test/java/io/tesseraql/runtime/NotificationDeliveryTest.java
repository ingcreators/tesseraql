package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpServer;
import io.tesseraql.core.notify.HmacSignatures;
import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.pipeline.RuntimeContext;
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
    // A dynamic port, not ServerSetupTest's fixed 3025: test classes from parallel surefire
    // forks run concurrently in separate JVMs, and two mail suites must not race for one port.
    static final GreenMailExtension MAIL = new GreenMailExtension(
            ServerSetupTest.SMTP.dynamicPort());

    @TempDir
    static Path appHome;

    static RuntimeContext context;
    static HttpServer receiver;
    static final AtomicReference<Map<String, String>> received = new AtomicReference<>();
    static volatile int receiverStatus = 200;

    @BeforeAll
    static void start() throws Exception {
        context = new RuntimeContext();
        context.start();
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
        if (context != null) {
            context.close();
        }
        if (receiver != null) {
            receiver.stop(0);
        }
    }

    private static NotificationSink sink(Map<String, Object> channels) {
        return sink(channels, null);
    }

    private static NotificationSink sink(Map<String, Object> channels,
            io.tesseraql.core.files.FileTransferService transfers) {
        AppConfig config = new AppConfig(
                Map.of("tesseraql", Map.of("notifications", Map.of("channels", channels))),
                name -> null);
        return new NotificationSink(NotificationChannels.load(config), appHome, null,
                transfers, gateway());
    }

    private static OutboxEvent notification(String channel, Map<String, Object> payload) {
        return notification(channel, null, payload);
    }

    private static OutboxEvent notification(String channel, String attachTransferId,
            Map<String, Object> payload) {
        OutboxEvent toInsert = NotifyEvents.event(channel, "users.register.confirmation",
                null, null, attachTransferId, payload, "user-admin");
        return new OutboxEvent("evt-1", toInsert.aggregateType(), toInsert.aggregateId(),
                toInsert.eventType(), toInsert.payloadJson(), "PENDING", 0, null, Instant.now(),
                null, toInsert.appName(), null, null);
    }

    /**
     * A transfer store serving one known export from memory — the delivery-side seam
     * (docs/analytics-experience.md): only {@code download()} matters to the mail path.
     */
    private static io.tesseraql.core.files.FileTransferService oneTransfer(String transferId,
            io.tesseraql.core.files.FileTransferService.Download download) {
        return (io.tesseraql.core.files.FileTransferService) java.lang.reflect.Proxy
                .newProxyInstance(NotificationDeliveryTest.class.getClassLoader(),
                        new Class<?>[]{io.tesseraql.core.files.FileTransferService.class},
                        (proxy, method, args) -> {
                            if ("download".equals(method.getName())) {
                                return transferId.equals(args[0])
                                        ? java.util.Optional.of(download)
                                        : java.util.Optional.empty();
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
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
    void sendTestDeliversTheRenderedBodyToTheExplicitRecipient() throws Exception {
        // The Studio test send (docs/pages-and-mail-lints.md follow-ups): a pre-rendered
        // body over the channel's own transport, subject rendered like a real delivery,
        // recipient explicit — no outbox, no template-path indirection.
        io.tesseraql.yaml.notify.NotificationChannels channels = io.tesseraql.yaml.notify.NotificationChannels
                .load(
                        new io.tesseraql.yaml.config.AppConfig(Map.of("tesseraql", Map.of(
                                "notifications", Map.of("channels", Map.of("user-mail",
                                        Map.of(
                                                "type", "mail",
                                                "host", "localhost",
                                                "port", MAIL.getSmtp().getPort(),
                                                "from", "noreply@example.com",
                                                "to", "fallback@example.com",
                                                "subject", "Test [(${payload.userName})]",
                                                "template", "templates/mail/welcome.txt"))))),
                                name -> null));
        int before = MAIL.getReceivedMessages().length;

        new io.tesseraql.yaml.notify.MailNotifier(appHome).sendTest(
                channels.require("user-mail"),
                Map.of("payload", Map.of("userName", "sato")),
                "<p>Hello test</p>", true, "dev@example.com");

        MAIL.waitForIncomingEmail(before + 1);
        MimeMessage message = java.util.Arrays.stream(MAIL.getReceivedMessages())
                .filter(m -> {
                    try {
                        return "Test sato".equals(m.getSubject());
                    } catch (jakarta.mail.MessagingException ex) {
                        return false;
                    }
                }).findFirst().orElseThrow();
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("dev@example.com");
        assertThat(message.getContentType()).contains("text/html");
        assertThat(GreenMailUtil.getBody(message)).contains("<p>Hello test</p>");
    }

    @Test
    void attachesTheTransferredFileAsMultipartMail() throws Exception {
        Map<String, Object> channel = Map.of(
                "type", "mail",
                "host", "localhost",
                "port", MAIL.getSmtp().getPort(),
                "from", "noreply@example.com",
                "to", "ops@example.com",
                "template", "templates/mail/welcome.txt");
        NotificationSink sink = sink(Map.of("report-mail", channel),
                oneTransfer("tr-1", new io.tesseraql.core.files.FileTransferService.Download(
                        "price-summary.csv", "text/csv",
                        new java.io.ByteArrayInputStream(
                                "sku,price\nMS-230,9\n".getBytes(StandardCharsets.UTF_8)))));

        // The extension's mailbox accumulates across tests in this class: start clean so
        // "one message" means this test's message.
        MAIL.purgeEmailFromAllMailboxes();
        sink.send(notification("report-mail", "tr-1",
                Map.of("userName", "ops", "email", "ops@example.com")));

        MAIL.waitForIncomingEmail(1);
        MimeMessage message = MAIL.getReceivedMessages()[0];
        jakarta.mail.Multipart multipart = (jakarta.mail.Multipart) message.getContent();
        assertThat(multipart.getCount()).isEqualTo(2);
        assertThat(String.valueOf(multipart.getBodyPart(0).getContent()))
                .contains("Hello ops,");
        jakarta.mail.BodyPart file = multipart.getBodyPart(1);
        assertThat(file.getFileName()).isEqualTo("price-summary.csv");
        assertThat(new String(file.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .contains("MS-230,9");
    }

    @Test
    void refusesAnOversizeAttachmentNamingTheSetting() {
        Map<String, Object> channel = Map.of(
                "type", "mail",
                "host", "localhost",
                "port", MAIL.getSmtp().getPort(),
                "from", "noreply@example.com",
                "to", "ops@example.com",
                "maxAttachmentBytes", 4,
                "template", "templates/mail/welcome.txt");
        NotificationSink sink = sink(Map.of("report-mail", channel),
                oneTransfer("tr-1", new io.tesseraql.core.files.FileTransferService.Download(
                        "big.csv", "text/csv",
                        new java.io.ByteArrayInputStream(
                                "far-too-many-bytes".getBytes(StandardCharsets.UTF_8)))));

        assertThatThrownBy(() -> sink.send(notification("report-mail", "tr-1",
                Map.of("userName", "ops", "email", "ops@example.com"))))
                .hasMessageContaining("maxAttachmentBytes");
    }

    @Test
    void anUnknownAttachedTransferFailsDeliveryForTheRetryPolicy() {
        Map<String, Object> channel = Map.of(
                "type", "mail",
                "host", "localhost",
                "port", MAIL.getSmtp().getPort(),
                "from", "noreply@example.com",
                "to", "ops@example.com",
                "template", "templates/mail/welcome.txt");
        NotificationSink withStore = sink(Map.of("report-mail", channel),
                oneTransfer("tr-1", new io.tesseraql.core.files.FileTransferService.Download(
                        "x.csv", "text/csv", java.io.InputStream.nullInputStream())));
        assertThatThrownBy(() -> withStore.send(notification("report-mail", "tr-gone",
                Map.of("userName", "ops", "email", "ops@example.com"))))
                .hasMessageContaining("no downloadable file");

        // Without the transfer store wired, attaching says so plainly.
        NotificationSink unwired = sink(Map.of("report-mail", channel));
        assertThatThrownBy(() -> unwired.send(notification("report-mail", "tr-1",
                Map.of("userName", "ops", "email", "ops@example.com"))))
                .hasMessageContaining("no transfer store is wired");
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
                "PENDING", 0, null, Instant.now(), null, "user-admin", null, null));
    }

    // ---- a notification renders in a named locale (docs/audit-low-leads.md slice 20) ----

    /**
     * An inbox title renders in English whatever the delivering JVM's locale is (XH-20): the
     * title is persisted and read by every reader of the bell, and a bare Thymeleaf context
     * froze the host's own locale into it ({@code 1.234,50 de_DE} on a German host). The
     * {@code #locale} token keeps the row red on an English CI JVM, where the number alone
     * would pass by inheritance.
     */
    @Test
    void anInboxTitleRendersInEnglishWhateverTheJvmSays() throws Exception {
        java.util.Locale jvm = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.GERMANY);
        try {
            MemoryInbox inbox = new MemoryInbox();
            NotificationSink sink = new NotificationSink(NotificationChannels.load(new AppConfig(
                    Map.of("tesseraql", Map.of("notifications", Map.of("channels", Map.of(
                            "bell", Map.of("type", "inbox",
                                    "title", "[(${#numbers.formatDecimal(payload.total,1,2)})]"
                                            + " [(${#locale})]"))))),
                    name -> null)), appHome, inbox, null, gateway());

            sink.send(inboxNotification("bell", Map.of("total", 1234.5)));

            // The decimal point and the locale token: a German host said `1234,50 de_DE`.
            assertThat(inbox.titles).containsExactly("1234.50 en");
        } finally {
            java.util.Locale.setDefault(jvm);
        }
    }

    /**
     * A mail subject renders in the locale the body renders in (XH-20). The subject was
     * pinned to {@code Locale.ROOT} — on every JVM — so a template-created date abbreviated
     * its day and month names ({@code Thu 5 Mar}) while the body of the same mail spelled
     * them out; {@code #locale} was empty where the body's is {@code en}.
     */
    @Test
    void aMailSubjectRendersInEnglishLikeTheBody() throws Exception {
        Map<String, Object> channel = Map.of(
                "type", "mail",
                "host", "localhost",
                "port", MAIL.getSmtp().getPort(),
                "from", "noreply@example.com",
                "to", "x@example.com",
                "subject", "[(${#dates.format(#dates.create(2026,3,5),'EEEE d MMMM')})]"
                        + " [(${#locale})]",
                "template", "templates/mail/welcome.txt");
        NotificationSink sink = sink(Map.of("dated-mail", channel));

        MAIL.purgeEmailFromAllMailboxes();
        sink.send(notification("dated-mail", Map.of("userName", "x", "email", "x@example.com")));

        MAIL.waitForIncomingEmail(1);
        assertThat(MAIL.getReceivedMessages()[0].getSubject()).isEqualTo("Thursday 5 March en");
    }

    /**
     * A message key resolves in a subject and in an inbox title as it does in a mail body
     * (unfiled 54): the two inline engines set no message resolver, so {@code [(#{key})]}
     * rendered the {@code ??key_??} marker beside a body that read {@code messages/en.yml}.
     */
    @Test
    void aMessageKeyResolvesInASubjectAndAnInboxTitle() throws Exception {
        Files.createDirectories(appHome.resolve("messages"));
        Files.writeString(appHome.resolve("messages/en.yml"), """
                notice:
                  subject: Your report is ready
                  title: New report
                """);
        MemoryInbox inbox = new MemoryInbox();
        NotificationSink sink = new NotificationSink(NotificationChannels.load(new AppConfig(
                Map.of("tesseraql", Map.of("notifications", Map.of("channels", Map.of(
                        "bell", Map.of("type", "inbox", "title", "[(#{notice.title})]"),
                        "keyed-mail", Map.of(
                                "type", "mail",
                                "host", "localhost",
                                "port", MAIL.getSmtp().getPort(),
                                "from", "noreply@example.com",
                                "to", "x@example.com",
                                "subject", "[(#{notice.subject})]",
                                "template", "templates/mail/welcome.txt"))))),
                name -> null)), appHome, inbox, null, gateway());

        sink.send(inboxNotification("bell", Map.of()));
        MAIL.purgeEmailFromAllMailboxes();
        sink.send(notification("keyed-mail", Map.of("userName", "x", "email", "x@example.com")));

        assertThat(inbox.titles).containsExactly("New report");
        MAIL.waitForIncomingEmail(1);
        assertThat(MAIL.getReceivedMessages()[0].getSubject()).isEqualTo("Your report is ready");
    }

    /**
     * An attachment keeps its name on the mail wire (DN-06d, unfiled 53): jakarta.mail's
     * {@code setFileName} encoded it in the JVM's default charset — {@code ??.csv} under
     * {@code -Dfile.encoding=COMPAT} on a Western host — wrote no ASCII fallback, decorated the
     * part's {@code Content-Type} with a {@code name} parameter in that charset, and passed a
     * CR LF through into the header block. The one Content-Disposition writer every download
     * uses writes the fallback beside a UTF-8 ext-value, with the controls folded.
     */
    @Test
    void anAttachmentKeepsItsNameOnTheMailWire() throws Exception {
        jakarta.mail.BodyPart file = attachedPart("\u58f2\u4e0a.csv");

        String disposition = file.getHeader("Content-Disposition")[0];
        assertThat(disposition).contains("attachment").contains("filename=\"")
                .contains("filename*=UTF-8''%E5%A3%B2%E4%B8%8A.csv");
        // jakarta.mail's own reader decodes the ext-value back to the name.
        assertThat(file.getFileName()).isEqualTo("\u58f2\u4e0a.csv");
        // No `name` parameter in the JVM charset behind the disposition's back.
        assertThat(file.getHeader("Content-Type")[0]).isEqualTo("text/csv")
                .doesNotContain("name");
    }

    /** The original defect, under the charset an operator's {@code -Dfile.encoding} would set. */
    @Test
    void anAttachmentNameSurvivesAnIso88591Jvm() throws Exception {
        java.lang.reflect.Field charset = jakarta.mail.internet.MimeUtility.class
                .getDeclaredField("defaultMIMECharset");
        charset.setAccessible(true);
        Object before = charset.get(null);
        charset.set(null, "ISO-8859-1");
        try {
            jakarta.mail.BodyPart file = attachedPart("\u58f2\u4e0a.csv");
            assertThat(file.getHeader("Content-Disposition")[0])
                    .contains("filename*=UTF-8''%E5%A3%B2%E4%B8%8A.csv")
                    .doesNotContain("ISO-8859-1");
            assertThat(file.getFileName()).isEqualTo("\u58f2\u4e0a.csv");
        } finally {
            charset.set(null, before);
        }
        // The wrong fix — a process-global mail.mime.charset — was not taken.
        assertThat(System.getProperty("mail.mime.charset")).isNull();
    }

    /** A name carrying CR LF cannot open a new header line in the part (unfiled 53). */
    @Test
    void anAttachmentNameCannotInjectAHeader() throws Exception {
        jakarta.mail.BodyPart file = attachedPart("a\r\nX-Injected: 1.csv");

        String[] disposition = file.getHeader("Content-Disposition");
        assertThat(disposition).hasSize(1);
        // Folded into the quoted name, on the one line: never a second header.
        assertThat(disposition[0]).doesNotContain("\r").doesNotContain("\n")
                .contains("filename=\"a__X-Injected");
        assertThat(file.getHeader("X-Injected")).isNull();
    }

    /** One mail with one attachment named {@code filename}, its file part as GreenMail received it. */
    private static jakarta.mail.BodyPart attachedPart(String filename) throws Exception {
        Map<String, Object> channel = Map.of(
                "type", "mail",
                "host", "localhost",
                "port", MAIL.getSmtp().getPort(),
                "from", "noreply@example.com",
                "to", "ops@example.com",
                "template", "templates/mail/welcome.txt");
        NotificationSink sink = sink(Map.of("report-mail", channel),
                oneTransfer("tr-2", new io.tesseraql.core.files.FileTransferService.Download(
                        filename, "text/csv",
                        new java.io.ByteArrayInputStream(
                                "sku,price\n".getBytes(StandardCharsets.UTF_8)))));
        MAIL.purgeEmailFromAllMailboxes();
        sink.send(notification("report-mail", "tr-2",
                Map.of("userName", "ops", "email", "ops@example.com")));
        MAIL.waitForIncomingEmail(1);
        jakarta.mail.Multipart multipart = (jakarta.mail.Multipart) MAIL.getReceivedMessages()[0]
                .getContent();
        return multipart.getBodyPart(1);
    }

    private static OutboxEvent inboxNotification(String channel, Map<String, Object> payload) {
        OutboxEvent toInsert = NotifyEvents.event(channel, "reports.ready", "sato", null,
                payload, "user-admin");
        return new OutboxEvent("evt-inbox-" + payload.hashCode(), toInsert.aggregateType(),
                toInsert.aggregateId(), toInsert.eventType(), toInsert.payloadJson(), "PENDING",
                0, null, Instant.now(), null, toInsert.appName(), null, null);
    }

    /** An inbox that keeps what it was handed. */
    private static final class MemoryInbox implements io.tesseraql.core.inbox.InboxStore {

        private final java.util.List<String> titles = new java.util.ArrayList<>();

        @Override
        public void deliver(String eventId, String tenantId, String subject, String channel,
                String source, String title, String body) {
            titles.add(title);
        }

        @Override
        public int unreadCount(String tenantId, String subject) {
            return 0;
        }

        @Override
        public java.util.List<InboxMessage> recent(String tenantId, String subject, int limit) {
            return java.util.List.of();
        }

        @Override
        public boolean markRead(String tenantId, String subject, String eventId) {
            return false;
        }

        @Override
        public int markAllRead(String tenantId, String subject) {
            return 0;
        }
    }

    /** The outbound gateway a delivery leaves through; localhost is the test's own receiver. */
    private static io.tesseraql.yaml.http.OutboundGateway gateway() {
        AppConfig outbound = new AppConfig(Map.of("tesseraql", Map.of("http", Map.of("outbound",
                Map.of("allowedHosts", java.util.List.of("localhost", "127.0.0.1"))))),
                name -> null);
        return new io.tesseraql.operations.http.HttpCallClient(
                io.tesseraql.yaml.http.HttpOutbound.load(outbound), outbound,
                io.tesseraql.core.telemetry.NoopTracer.INSTANCE,
                io.tesseraql.core.telemetry.NoopMeter.INSTANCE);
    }

}
