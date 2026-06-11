package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpServer;
import io.tesseraql.core.notify.HmacSignatures;
import io.tesseraql.core.outbox.OutboxEvent;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Phase 20 acceptance flow against the example app: a command's {@code notify:} block
 * delivers a templated confirmation mail and an HMAC-signed audit webhook through the outbox; a
 * rejected webhook retries and dead-letters at the configured ceiling, stays visible in the
 * operations API, and an operator redelivers it; a job's notify step and a failing job's alert
 * ride the same channels.
 */
@Testcontainers
class NotificationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(ServerSetupTest.SMTP);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WEBHOOK_SECRET = "dev-only-webhook-secret";

    record Delivery(String body, String signature, String timestamp) {
    }

    static final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    static volatile int receiverStatus = 200;

    static HttpServer receiver;
    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        receiver = HttpServer.create(new InetSocketAddress(0), 0);
        receiver.createContext("/hook", exchange -> {
            deliveries.add(new Delivery(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst(HmacSignatures.SIGNATURE_HEADER),
                    exchange.getRequestHeaders().getFirst(HmacSignatures.TIMESTAMP_HEADER)));
            exchange.sendResponseHeaders(receiverStatus, -1);
            exchange.close();
        });
        receiver.start();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (receiver != null) {
            receiver.stop(0);
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void provisioningDeliversTemplatedMailAndSignedWebhook() throws Exception {
        receiverStatus = 200;
        HttpResponse<String> response = post("/api/users/provision",
                "{\"userName\":\"suzuki\",\"givenName\":\"Ichiro\",\"active\":true}");
        assertThat(response.statusCode()).isEqualTo(200);

        // Both notifications committed with the command, riding the outbox.
        List<OutboxEvent> pending = runtime.outboxStore().listPending(50);
        assertThat(pending.stream().filter(e -> "NOTIFICATION".equals(e.eventType())))
                .hasSize(2);

        runtime.dispatchOutboxOnce();

        MAIL.waitForIncomingEmail(1);
        MimeMessage message = MAIL.getReceivedMessages()[0];
        assertThat(message.getSubject()).isEqualTo("Account provisioned: suzuki");
        assertThat(GreenMailUtil.getBody(message))
                .contains("Hello Ichiro,")
                .contains("\"suzuki\" has been provisioned");

        Delivery audit = deliveries.stream()
                .filter(d -> d.body().contains("users.apiProvision.audit"))
                .findFirst().orElseThrow();
        assertThat(audit.body()).contains("\"userName\":\"suzuki\"")
                .contains("\"actor\":\"admin\"").contains("\"app\":\"user-admin\"");
        // The documented scheme: HMAC-SHA256 over timestamp.body with the channel secret.
        assertThat(HmacSignatures.verify(WEBHOOK_SECRET, audit.timestamp(),
                audit.body().getBytes(StandardCharsets.UTF_8), audit.signature())).isTrue();
    }

    @Test
    void rejectedWebhookDeadLettersAndAnOperatorRedelivers() throws Exception {
        receiverStatus = 500;
        // active=false: only the audit webhook notification fires (the when: guard skips mail).
        HttpResponse<String> provision = post("/api/users/provision",
                "{\"userName\":\"tanaka\",\"active\":false}");
        assertThat(provision.statusCode()).isEqualTo(200);
        String eventId = MAPPER.readTree(provision.body()).path("auditEventId").asText();
        assertThat(eventId).isNotBlank();

        // maxAttempts is 2 in this app's config: two failing dispatches dead-letter the event.
        runtime.dispatchOutboxOnce();
        runtime.dispatchOutboxOnce();

        // The delivery log shows the dead letter with its attempts and last error.
        JsonNode log = MAPPER.readTree(get("/_tesseraql/ops/outbox").body());
        JsonNode dead = find(log, eventId);
        assertThat(dead.path("status").asText()).isEqualTo("DEAD");
        assertThat(dead.path("attempts").asInt()).isEqualTo(2);
        assertThat(dead.path("lastError").asText()).contains("HTTP 500");

        // Dead letters raise an operational alert (TQL-OPS-9006).
        assertThat(runtime.opsDashboard().alerts())
                .anyMatch(alert -> "TQL-OPS-9006".equals(alert.code()));

        // The receiver recovers; the operator requeues the event and delivery completes.
        receiverStatus = 200;
        JsonNode redelivered = MAPPER.readTree(
                post("/_tesseraql/ops/outbox/" + eventId + "/redeliver", "").body());
        assertThat(redelivered.path("redelivered").asBoolean()).isTrue();
        runtime.dispatchOutboxOnce();
        JsonNode after = find(MAPPER.readTree(get("/_tesseraql/ops/outbox").body()), eventId);
        assertThat(after.path("status").asText()).isEqualTo("SENT");
        assertThat(deliveries.stream()
                .filter(d -> d.body().contains("\"userName\":\"tanaka\""))).isNotEmpty();
    }

    @Test
    void jobNotifyStepAndJobFailureAlertRideTheSameChannels() throws Exception {
        receiverStatus = 200;
        // The maintenance job's notify step reports the run through the audit webhook.
        runtime.runJob("user.dailyMaintenance", Map.of());
        runtime.dispatchOutboxOnce();
        assertThat(deliveries.stream()
                .filter(d -> d.body().contains("user.dailyMaintenance.report"))
                .findFirst().orElseThrow().body()).contains("\"deactivated\":");

        // A failing job raises an ops.jobFailure alert through the configured channel.
        runtime.runJob("user.broken", Map.of());
        runtime.dispatchOutboxOnce();
        Delivery alert = deliveries.stream()
                .filter(d -> d.body().contains("ops.jobFailure"))
                .findFirst().orElseThrow();
        assertThat(alert.body()).contains("\"jobId\":\"user.broken\"");
    }

    private static JsonNode find(JsonNode log, String eventId) {
        for (JsonNode event : log) {
            if (eventId.equals(event.path("id").asText())) {
                return event;
            }
        }
        throw new AssertionError("Event " + eventId + " not in the delivery log: " + log);
    }

    private static HttpResponse<String> post(String path, String json) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("Authorization", "Bearer " + token())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("Authorization", "Bearer " + token())
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(
                Map.of("sub", "u1", "preferred_username", "admin",
                        "roles", List.of("USER_WRITE", "ADMIN"),
                        // The ops endpoints additionally scope per app (design ch. 26.11).
                        "permissions", List.of("ops.app.*"))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table users (id serial primary key, name varchar(200), "
                    + "status varchar(32) not null, created_at timestamp default now())");
            statement.execute("insert into users (name, status) values "
                    + "('suzuki','PENDING'),('tanaka','PENDING')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-notify-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        // Point the channels at this test's SMTP server and webhook receiver (the placeholders
        // resolve top-level config keys after environment variables).
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                MAIL_PORT: %d
                AUDIT_WEBHOOK_URL: http://localhost:%d/hook

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s
                """.formatted(ServerSetupTest.SMTP.getPort(), receiver.getAddress().getPort(),
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

        Path config = target.resolve("config/tesseraql.yml");
        String yaml = Files.readString(config);
        // Enable the operations alerts channel (commented in the example) and a low
        // dead-letter ceiling so the retry path is quick to observe.
        yaml = yaml.replace("    # alerts:\n    #   channel: audit-webhook",
                "    alerts:\n      channel: audit-webhook");
        yaml += """

                  outbox:
                    dispatch:
                      maxAttempts: 2
                """;
        Files.writeString(config, yaml);

        // The provision route additionally exposes the audit notification's event id, so the
        // dead-letter test can follow that exact event through the operations API.
        Path provision = target.resolve("web/api/users/provision/post.yml");
        Files.writeString(provision, Files.readString(provision).replace(
                "      eventId: sql.eventId",
                "      eventId: sql.eventId\n      auditEventId: notify.audit.eventId"));

        // A job whose SQL fails, to assert the job-failure alert.
        Path broken = target.resolve("batch/broken");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("job.yml"), """
                version: tesseraql/v1
                id: user.broken
                kind: job
                recipe: batch-tasklet
                sql:
                  file: broken.sql
                  mode: update
                """);
        Files.writeString(broken.resolve("broken.sql"),
                "update no_such_table set x = 1\n");
        return target;
    }

    private static void copy(Path source, Path target, Path path) {
        try {
            Path destination = target.resolve(source.relativize(path).toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
            } else {
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
    }
}
