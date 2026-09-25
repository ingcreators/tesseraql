package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration test for in-process slow-SQL collection (design ch. 26.11). With the slow threshold
 * lowered, executing a route's SQL records it in the ring, surfaced at /_tesseraql/ops/slow-sql.
 */
@Testcontainers
class SlowSqlIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
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
            assertThat(entry.get("sqlId").asString()).endsWith("ping.sql");
            assertThat(entry.get("mode").asString()).isEqualTo("query");
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
        assertThat(spans).anySatisfy(
                span -> assertThat(span.get("name").asString()).isEqualTo("tesseraql.sql.execute"));
        assertThat(spans).anySatisfy(
                span -> assertThat(span.get("name").asString()).isEqualTo("tesseraql.route"));
    }

    @Test
    void securedRouteRecordsSecuritySpans() throws Exception {
        // Hit the bearer-protected /api/users so authenticate/authorize spans are recorded.
        HttpResponse<String> secure = HttpClient.newHttpClient().send(
                HttpRequest
                        .newBuilder(
                                URI.create("http://localhost:" + runtime.port() + "/api/secure"))
                        .header("Authorization", "Bearer " + token(List.of("USER_READ")))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(secure.statusCode()).isEqualTo(200);

        HttpResponse<String> tree = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/_tesseraql/ops/traces/tree"))
                        .header("Authorization", "Bearer " + token()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(tree.statusCode()).isEqualTo(200);

        JsonNode roots = MAPPER.readTree(tree.body());
        assertThat(roots).anySatisfy(root -> {
            assertThat(root.get("span").get("attributes").path("routeId").asString())
                    .isEqualTo("secure.ping");
            assertThat(root.get("children"))
                    .anySatisfy(child -> assertThat(child.get("span").get("name").asString())
                            .isEqualTo("tesseraql.security.authenticate"));
            assertThat(root.get("children"))
                    .anySatisfy(child -> assertThat(child.get("span").get("name").asString())
                            .isEqualTo("tesseraql.security.authorize"));
        });
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
            assertThat(root.get("span").get("name").asString()).isEqualTo("tesseraql.route");
            // UI fields: formatted start time, duration, and the slow highlight (threshold 0).
            assertThat(root.get("startedAt").asString()).isNotBlank();
            assertThat(root.get("durationMs").isNumber()).isTrue();
            assertThat(root.get("selfMs").isNumber()).isTrue();
            assertThat(root.get("slow").asBoolean()).isTrue();
            // The route span now has intermediate children for binding and SQL execution.
            assertThat(root.get("children"))
                    .anySatisfy(child -> assertThat(child.get("span").get("name").asString())
                            .isEqualTo("tesseraql.request.bind"));
            assertThat(root.get("children"))
                    .anySatisfy(child -> assertThat(child.get("span").get("name").asString())
                            .isEqualTo("tesseraql.sql.execute"));
        });

        HttpResponse<String> summary = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/_tesseraql/ops/traces/summary"))
                        .header("Authorization", "Bearer " + token()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(summary.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(summary.body())).anySatisfy(trace -> {
            assertThat(trace.get("rootSpan").asString()).isEqualTo("tesseraql.route");
            assertThat(trace.get("spanCount").asInt()).isGreaterThanOrEqualTo(2);
            assertThat(trace.get("slowestSpan").asString()).isNotBlank();
            assertThat(trace.get("errorCount").isNumber()).isTrue();
            assertThat(trace.get("slowCount").asInt()).isGreaterThan(0); // threshold is 0
        });

        // The slow filter keeps traces with at least one slow span (all of them at threshold 0).
        HttpResponse<String> slowOnly = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port()
                        + "/_tesseraql/ops/traces/summary?filter=slow"))
                        .header("Authorization", "Bearer " + token()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(slowOnly.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(slowOnly.body())).isNotEmpty();
    }

    /**
     * A transfer's span carries its app (docs/audit-low-leads.md slice 16, XH-11): the span the
     * transfer service starts is its trace's root — a transfer runs on no request — and a root
     * with no {@code app} attribute is invisible to every per-app reader of the traces API, so
     * the {@code surface=transfer} span existed and no {@code tql.ops.view.<app>} holder could
     * list it. Asserted under the per-app grant, the one the attribute decides. (The copied
     * example keeps its own name, {@code user-admin}, in {@code config/tesseraql.yml}.)
     */
    @Test
    void aTransferSpanIsListedUnderThePerAppViewGrant() throws Exception {
        HttpResponse<String> started = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/export"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(started.statusCode()).isEqualTo(202);
        String transferId = MAPPER.readTree(started.body()).get("transferId").asString();
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(20);
        while (true) {
            JsonNode status = MAPPER.readTree(HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port()
                            + "/api/export/" + transferId)).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            if (!"RUNNING".equals(status.get("status").asString())) {
                assertThat(status.get("status").asString()).isEqualTo("COMPLETED");
                break;
            }
            assertThat(java.time.Instant.now()).isBefore(deadline);
            Thread.sleep(100);
        }

        HttpResponse<String> traces = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/_tesseraql/ops/traces"))
                        .header("Authorization", "Bearer "
                                + token(List.of("BATCH_OPERATOR"),
                                        List.of("tql.ops.view.user-admin")))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(traces.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(traces.body()))
                .as("the transfer's span under tql.ops.view.user-admin")
                .anySatisfy(span -> {
                    assertThat(span.get("attributes").path("surface").asString())
                            .isEqualTo("transfer");
                    assertThat(span.get("attributes").path("app").asString())
                            .isEqualTo("user-admin");
                });
    }

    private static String token() throws Exception {
        return token(List.of("BATCH_OPERATOR"));
    }

    private static String token(List<String> roles) throws Exception {
        // tql.ops.view.* keeps full trace visibility under the per-app scope.
        return token(roles, List.of("tql.ops.view.*"));
    }

    private static String token(List<String> roles, List<String> permissions) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder
                .encodeToString(MAPPER.writeValueAsBytes(TestClaims.addressed(Map.of(
                        "sub", "ops", "roles", roles, "permissions", permissions))));
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
        Path target = Files.createTempDirectory("tesseraql-slowsql-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        UserAdminAppCopy.prepare(target);
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s

                tesseraql:
                  app:
                    name: slow-sql
                  diagnostics:
                    slowSqlMillis: 0
                    slowSpanMillis: 0
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
                sources:
                  main:
                    sql:
                      file: ping.sql
                      mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: main.rows
                """);
        Files.writeString(pingDir.resolve("ping.sql"), "select 1 as ok\n");

        Path secureDir = target.resolve("web/api/secure");
        Files.createDirectories(secureDir);
        Files.writeString(secureDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: secure.ping
                kind: route
                recipe: query-json
                security:
                  auth: bearer
                  policy: users.read
                sources:
                  main:
                    sql:
                      file: secure.sql
                      mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: main.rows
                """);
        Files.writeString(secureDir.resolve("secure.sql"), "select 1 as ok\n");

        // A public file-export route: its run is a transfer, and its span a root of its own.
        Path exportDir = target.resolve("web/api/export");
        Files.createDirectories(exportDir);
        Files.writeString(exportDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: export
                kind: route
                recipe: file-export
                security:
                  auth: public
                export:
                  format: csv
                  filename: ok.csv
                sources:
                  main:
                    sql:
                      file: export.sql
                """);
        Files.writeString(exportDir.resolve("export.sql"), "select 1 as ok\n;\n");
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

}
