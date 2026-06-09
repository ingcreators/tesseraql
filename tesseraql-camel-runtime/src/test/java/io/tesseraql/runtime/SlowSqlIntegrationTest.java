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
 * Integration test for in-process slow-SQL collection (design ch. 26.11). With the slow threshold
 * lowered, executing a route's SQL records it in the ring, surfaced at /_tesseraql/ops/slow-sql.
 */
@Testcontainers
class SlowSqlIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
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
    void executedRouteSqlIsRecordedInSlowSqlLog() throws Exception {
        // Trigger a route that runs SQL (public, no token needed).
        assertThat(HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/ping")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

        HttpResponse<String> slow = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + runtime.port() + "/_tesseraql/ops/slow-sql"))
                        .header("Authorization", "Bearer " + token())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(slow.statusCode()).isEqualTo(200);

        JsonNode entries = MAPPER.readTree(slow.body());
        assertThat(entries.isArray()).isTrue();
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.get("sqlId").asText()).endsWith("ping.sql");
            assertThat(entry.get("mode").asText()).isEqualTo("query");
            assertThat(entry.get("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        });
    }

    @Test
    void recentSpansAreCollectedInProcess() throws Exception {
        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/ping")).build(),
                HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> traces = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + runtime.port() + "/_tesseraql/ops/traces"))
                        .header("Authorization", "Bearer " + token())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(traces.statusCode()).isEqualTo(200);

        JsonNode spans = MAPPER.readTree(traces.body());
        assertThat(spans.isArray()).isTrue();
        assertThat(spans).anySatisfy(span ->
                assertThat(span.get("name").asText()).isEqualTo("tesseraql.sql.execute"));
        assertThat(spans).anySatisfy(span ->
                assertThat(span.get("name").asText()).isEqualTo("tesseraql.route"));
    }

    @Test
    void traceTreeNestsSqlSpanUnderRouteSpan() throws Exception {
        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/ping")).build(),
                HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> tree = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + runtime.port() + "/_tesseraql/ops/traces/tree"))
                        .header("Authorization", "Bearer " + token())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(tree.statusCode()).isEqualTo(200);

        JsonNode roots = MAPPER.readTree(tree.body());
        assertThat(roots).anySatisfy(root -> {
            assertThat(root.get("span").get("name").asText()).isEqualTo("tesseraql.route");
            assertThat(root.get("children")).anySatisfy(child ->
                    assertThat(child.get("span").get("name").asText()).isEqualTo("tesseraql.sql.execute"));
        });
    }

    private static String token() throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(
                MAPPER.writeValueAsBytes(Map.of("sub", "ops", "roles", List.of("BATCH_OPERATOR"))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-slowsql-it");
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

                tesseraql:
                  diagnostics:
                    slowSqlMillis: 0
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

        Path pingDir = target.resolve("web/api/ping");
        Files.createDirectories(pingDir);
        Files.writeString(pingDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: ping
                kind: route
                recipe: query-json
                security:
                  auth: public
                sql:
                  file: ping.sql
                  mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: sql.rows
                """);
        Files.writeString(pingDir.resolve("ping.sql"), "select 1 as ok\n");
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
