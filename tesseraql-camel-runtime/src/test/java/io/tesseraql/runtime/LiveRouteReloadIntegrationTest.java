package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for live route hot-reload (design ch. 16.8). After Studio applies an edit to an
 * existing route's SQL and reloads, the running endpoint serves the new behavior without a restart.
 */
@Testcontainers
class LiveRouteReloadIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

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
    void editApplyReloadChangesLiveBehavior() throws Exception {
        assertThat(pingVersion()).isEqualTo("v1");

        // Edit the route's SQL via Studio, apply it, and hot-reload.
        String path = "web/api/ping/ping.sql";
        assertThat(studioPost("/_tesseraql/studio/drafts?path=" + enc(path),
                "select 'v2' as version\n").statusCode()).isEqualTo(200);
        assertThat(studioPost("/_tesseraql/studio/apply?path=" + enc(path), "").statusCode())
                .isEqualTo(200);
        assertThat(studioPost("/_tesseraql/studio/reload", "").statusCode()).isEqualTo(200);

        assertThat(pingVersion()).isEqualTo("v2");
    }

    @Test
    void aBrokenDefinitionIsolatesToItsOwnRouteAsA500() throws Exception {
        String before = pingVersion();
        String original = Files.readString(appHome.resolve("web/api/ping/get.yml"));
        String broken = original.replace("recipe: query-json", "recipe: no-such-recipe");

        // Per-route isolation (roadmap Phase 42): the reload succeeds, the broken route serves
        // a clear 500 carrying its compile error, and every other route keeps serving.
        assertThat(studioPost("/_tesseraql/studio/drafts?path=" + enc("web/api/ping/get.yml"),
                broken).statusCode()).isEqualTo(200);
        assertThat(studioPost("/_tesseraql/studio/apply?path=" + enc("web/api/ping/get.yml"),
                "").statusCode()).isEqualTo(200);
        HttpResponse<String> reload = studioPost("/_tesseraql/studio/reload", "");
        assertThat(reload.statusCode()).isEqualTo(200);
        assertThat(reload.body()).contains("\"failed\"").contains("no-such-recipe");

        HttpResponse<String> stub = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/ping")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(stub.statusCode()).isEqualTo(500);
        assertThat(stub.body()).contains("TQL-CAMEL-3103").contains("no-such-recipe");
        // A neighbor route is untouched by the failure.
        assertThat(get("/users?q=sato").statusCode()).isNotEqualTo(500);

        // Fixing the definition brings the route back on the next reload.
        assertThat(studioPost("/_tesseraql/studio/drafts?path=" + enc("web/api/ping/get.yml"),
                original).statusCode()).isEqualTo(200);
        assertThat(studioPost("/_tesseraql/studio/apply?path=" + enc("web/api/ping/get.yml"),
                "").statusCode()).isEqualTo(200);
        assertThat(studioPost("/_tesseraql/studio/reload", "").statusCode()).isEqualTo(200);
        assertThat(pingVersion()).isEqualTo(before);
    }

    @Test
    void aNewRouteMountsAndARemovedRouteUnmountsWithoutARestart() throws Exception {
        // The instant loop (roadmap Phase 42): a brand-new route file serves after a reload.
        Path dir = appHome.resolve("web/api/pong");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pong.sql"), "select 'pong' as answer\n");
        Files.writeString(dir.resolve("get.yml"), """
                version: tesseraql/v1
                id: pong
                kind: route
                recipe: query-json
                security:
                  auth: public
                sql:
                  file: pong.sql
                  mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: sql.rows
                """);
        HttpResponse<String> reload = studioPost("/_tesseraql/studio/reload", "");
        assertThat(reload.statusCode()).isEqualTo(200);
        assertThat(reload.body()).contains("\"added\":[\"pong\"]");
        assertThat(get("/api/pong").statusCode()).isEqualTo(200);
        assertThat(get("/api/pong").body()).contains("pong");

        // Removing the file un-mounts the endpoint on the next reload.
        Files.delete(dir.resolve("get.yml"));
        Files.delete(dir.resolve("pong.sql"));
        HttpResponse<String> second = studioPost("/_tesseraql/studio/reload", "");
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).contains("\"removed\":[\"pong\"]");
        assertThat(get("/api/pong").statusCode()).isEqualTo(404);
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String pingVersion() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/ping")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body()).get("data").get(0).get("version").asText();
    }

    private static HttpResponse<String> studioPost(String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("Authorization", "Bearer " + token())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String token() throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(
                MAPPER.writeValueAsBytes(Map.of("sub", "studio", "roles", List.of("ADMIN"))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-reload-it");
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
                  studio:
                    readOnly: false
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

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
        Files.writeString(pingDir.resolve("ping.sql"), "select 'v1' as version\n");
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
