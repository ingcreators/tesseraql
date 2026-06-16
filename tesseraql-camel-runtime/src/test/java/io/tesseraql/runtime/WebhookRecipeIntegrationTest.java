package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.notify.HmacSignatures;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Phase 26 end-to-end: the inbound webhook recipe verifies an HMAC-signed delivery, runs its SQL
 * pipeline, and rejects a replay (409), a tampered signature (401), and a stale timestamp (401).
 */
@Testcontainers
class WebhookRecipeIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final String SECRET = "test-secret";

    static TesseraqlRuntime runtime;
    static Path appHome;
    static int port;
    static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        appHome = prepareAppHome();
        port = freePort();
        runtime = TesseraqlRuntime.start(appHome, port);
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void verifiesSignedDeliveriesAndRejectsReplaysTamperingAndStaleTimestamps() throws Exception {
        byte[] body = "{\"eventId\":\"E-1\",\"amount\":42}".getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = HmacSignatures.sign(SECRET, timestamp, body);

        // A valid signed delivery runs the SQL pipeline and the row is written.
        assertThat(post(body, timestamp, signature).statusCode()).isEqualTo(202);
        assertThat(amountFor("E-1")).isEqualTo(42);

        // The identical delivery is a replay (the signature is the replay key).
        assertThat(post(body, timestamp, signature).statusCode()).isEqualTo(409);

        // A tampered signature does not verify.
        assertThat(post(body, timestamp, "sha256=deadbeef").statusCode()).isEqualTo(401);

        // A timestamp outside the default 5m tolerance is rejected even with a valid signature.
        String stale = String.valueOf(Instant.now().getEpochSecond() - 3600);
        assertThat(post(body, stale, HmacSignatures.sign(SECRET, stale, body)).statusCode())
                .isEqualTo(401);

        // Only the first delivery wrote a row.
        assertThat(rowCount()).isEqualTo(1);
    }

    private static HttpResponse<String> post(byte[] body, String timestamp, String signature)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/hooks/events"))
                .header("Content-Type", "application/json")
                .header(HmacSignatures.SIGNATURE_HEADER, signature)
                .header(HmacSignatures.TIMESTAMP_HEADER, timestamp)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Integer amountFor(String eventId) throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select amount from webhook_event where event_id = '" + eventId + "'")) {
            return rs.next() ? rs.getInt("amount") : null;
        }
    }

    private static int rowCount() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select count(*) from webhook_event")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "create table webhook_event (event_id varchar(64) primary key, amount int)");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-webhook-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        // db config plus the webhook verifier secret; the example declares no connectors block, so
        // this deep-merges cleanly.
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s

                tesseraql:
                  connectors:
                    webhooks:
                      partner:
                        secret: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), SECRET));

        Path routeDir = target.resolve("web/hooks/events");
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: events.receive
                kind: route
                recipe: webhook
                webhook:
                  provider: partner
                input:
                  eventId:
                    type: string
                    required: true
                  amount:
                    type: number
                    required: false
                sql:
                  file: insert-event.sql
                  mode: update
                  params:
                    eventId: body.eventId
                    amount: body.amount
                response:
                  json:
                    status: 202
                """);
        Files.writeString(routeDir.resolve("insert-event.sql"),
                "insert into webhook_event (event_id, amount)"
                        + " values (/* eventId */ 'x', /* amount */ 0)\n");
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

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
