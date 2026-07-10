package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The hot reload's content diff, end to end: an apply bounces only the route whose sources
 * changed — bouncing (stop/re-add on a live endpoint) is the risky part of a reload, so
 * the delta stays minimal — while the manual {@code /_tesseraql/studio/reload} stays the
 * force hammer that rebuilds every kept route. Its own runtime and two-route app, so the
 * assertions cannot couple to the big Studio suite's shared fixture.
 */
@Testcontainers
class ReloadContentDiffIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        runtime = TesseraqlRuntime.start(appHome, port);
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            try (Stream<Path> files = Files.walk(appHome)) {
                files.sorted(Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void applyBouncesOnlyTheChangedRouteAndManualReloadForcesAll() throws Exception {
        // Both routes serve before anything reloads.
        assertThat(get("/api/alpha").statusCode()).isEqualTo(200);
        assertThat(get("/api/beta").statusCode()).isEqualTo(200);

        // Edit ONE route's 2-way SQL (an appended comment — semantically identical).
        assertThat(post("/_tesseraql/studio/drafts?path=" + enc("web/api/alpha/alpha.sql"),
                "select 1 as value\n-- content-diff touch\n").statusCode()).isEqualTo(200);
        HttpResponse<String> apply = post(
                "/_tesseraql/studio/apply?path=" + enc("web/api/alpha/alpha.sql"), "");
        assertThat(apply.statusCode()).isEqualTo(200);
        com.fasterxml.jackson.databind.JsonNode reloaded = MAPPER.readTree(apply.body())
                .get("reloaded");
        // Only the changed route bounced; the untouched neighbor was never stopped.
        assertThat(reloaded).hasSize(1);
        assertThat(reloaded.get(0).asText()).isEqualTo("alpha.list");
        assertThat(get("/api/alpha").body()).contains("value");
        assertThat(get("/api/beta").statusCode()).isEqualTo(200);

        // The manual reload is the recovery hammer: unchanged sources still rebuild.
        HttpResponse<String> force = post("/_tesseraql/studio/reload", "");
        assertThat(force.statusCode()).isEqualTo(200);
        List<String> forced = new java.util.ArrayList<>();
        MAPPER.readTree(force.body()).get("reloaded")
                .forEach(id -> forced.add(id.asText()));
        assertThat(forced).containsExactlyInAnyOrder("alpha.list", "beta.list");

        // And a no-change apply-path reload bounces nothing at all.
        assertThat(post("/_tesseraql/studio/drafts?path=" + enc("web/api/alpha/alpha.sql"),
                "select 1 as value\n-- content-diff touch\n").statusCode()).isEqualTo(200);
        HttpResponse<String> identical = post(
                "/_tesseraql/studio/apply?path=" + enc("web/api/alpha/alpha.sql"), "");
        assertThat(identical.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(identical.body()).get("reloaded")).isEmpty();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Authorization", "Bearer " + token())
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Authorization", "Bearer " + token())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String token() throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(MAPPER.writeValueAsBytes(
                Map.of("sub", "reload-it", "roles", List.of("ADMIN"))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-reload-diff-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: reload-diff-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  security:
                    jwt:
                      secret: dev-only-secret-change-me-in-production
                      rolesClaim: roles
                  studio:
                    enabled: true
                    readOnly: false
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        route(target, "alpha");
        route(target, "beta");
        return target;
    }

    /** One trivial query-json route per directory — the content-diff unit. */
    private static void route(Path home, String name) throws IOException {
        Path dir = home.resolve("web/api/" + name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".sql"), "select 1 as value\n");
        Files.writeString(dir.resolve("get.yml"), """
                version: tesseraql/v1
                id: %s.list
                kind: route
                recipe: query-json
                security:
                  auth: bearer
                sql:
                  file: %s.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """.formatted(name, name));
    }
}
