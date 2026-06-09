package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for the transactional outbox (design ch. 39.2): the command and its event commit
 * atomically, the dispatcher delivers pending events, and a failing command rolls back both the
 * change and the event.
 */
@Testcontainers
class OutboxIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
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
    void commandWritesOutboxEventAtomicallyAndDispatches() throws Exception {
        HttpResponse<String> response = post("/api/users/deactivate", "{\"name\":\"suzuki\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.path("affected").asInt()).isEqualTo(1);
        assertThat(body.path("eventId").asText()).isNotBlank();

        assertThat(runtime.outboxStore().listPending(50))
                .anyMatch(event -> "USER_DEACTIVATED".equals(event.eventType()));

        int delivered = runtime.dispatchOutboxOnce();
        assertThat(delivered).isGreaterThanOrEqualTo(1);
        assertThat(runtime.outboxStore().listPending(50)).isEmpty();
        assertThat(statusOf("suzuki")).isEqualTo("INACTIVE");
    }

    @Test
    void failingCommandRollsBackChangeAndEvent() throws Exception {
        String before = statusOf("tanaka");
        HttpResponse<String> response = post("/api/users/break", "{\"name\":\"tanaka\"}");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(statusOf("tanaka")).isEqualTo(before); // unchanged
        assertThat(runtime.outboxStore().listPending(50))
                .noneMatch(event -> "USER_BROKEN".equals(event.eventType()));
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

    private static String token() throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(
                Map.of("sub", "u1", "preferred_username", "admin", "roles", List.of("USER_WRITE"))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static String statusOf(String name) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select status from users where name = '" + name + "'")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table users (id serial primary key, name varchar(200), "
                    + "status varchar(32) not null, created_at timestamp default now())");
            statement.execute("insert into users (name, status) values "
                    + "('suzuki','ACTIVE'),('tanaka','ACTIVE')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-outbox-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

        // A route whose SQL violates NOT NULL, used to assert transactional rollback.
        Path breakDir = target.resolve("web/api/users/break");
        Files.createDirectories(breakDir);
        Files.writeString(breakDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: users.break
                kind: route
                recipe: command-json
                input:
                  name:
                    type: string
                    required: true
                security:
                  auth: bearer
                  policy: users.write
                outbox:
                  eventType: USER_BROKEN
                  aggregateType: User
                  aggregateId: body.name
                  payload:
                    name: body.name
                sql:
                  file: break.sql
                  mode: update
                  params:
                    name: body.name
                response:
                  json:
                    status: 200
                    body:
                      affected: sql.affectedRows
                """);
        Files.writeString(breakDir.resolve("break.sql"),
                "update users set status = null where name = /* name */ 'x'\n");
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
