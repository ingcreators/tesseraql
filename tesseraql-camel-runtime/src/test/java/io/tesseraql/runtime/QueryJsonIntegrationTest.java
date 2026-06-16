package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import java.io.IOException;
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
 * End-to-end acceptance test for milestone M1 plus Phase 4a security: {@code GET /api/users}
 * served from PostgreSQL via Simple YAML -&gt; authenticate -&gt; authorize -&gt; 2-way SQL -&gt; JSON
 * (design ch. 22 completion conditions 1, 4, 5, 11).
 */
@Testcontainers
class QueryJsonIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JWT_SECRET = "dev-only-secret-change-me-in-production";

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void startRuntime() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        seedDatabase();
    }

    @AfterAll
    static void stopRuntime() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void searchFiltersByQueryParameter() throws Exception {
        JsonNode body = getJson("/api/users?q=sato&limit=10", token(List.of("USER_READ")));

        assertThat(body.path("data")).hasSize(1);
        assertThat(body.path("data").get(0).path("name").asText()).isEqualTo("sato");
        // created_at is masked by the response field policy (design ch. 34).
        assertThat(body.path("data").get(0).path("created_at").asText()).isEqualTo("[MASKED]");
        assertThat(body.path("meta").path("count").asInt()).isEqualTo(1);
        assertThat(body.path("meta").path("limit").asInt()).isEqualTo(10);
        assertThat(body.path("meta").path("offset").asInt()).isZero();
    }

    @Test
    void searchReturnsAllWhenNoQuery() throws Exception {
        JsonNode body = getJson("/api/users", token(List.of("USER_READ")));

        assertThat(body.path("data").size()).isEqualTo(3);
        assertThat(body.path("meta").path("limit").asInt()).isEqualTo(50); // default applied
    }

    @Test
    void rejectsMissingToken() throws Exception {
        HttpResponse<String> response = get("/api/users", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(MAPPER.readTree(response.body()).path("error").path("code").asText())
                .isEqualTo("TQL-SEC-4011");
    }

    @Test
    void rejectsInsufficientRole() throws Exception {
        HttpResponse<String> response = get("/api/users", token(List.of("SOMETHING_ELSE")));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(MAPPER.readTree(response.body()).path("error").path("code").asText())
                .isEqualTo("TQL-SEC-4031");
    }

    @Test
    void htmxFragmentRendersTableWithSession() throws Exception {
        SessionStore sessions = runtime.camelContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal("u001", "sato", "Sato", "tenant-a",
                List.of(), List.of("USER_READ"), List.of(), Map.of()));

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port()
                        + "/users/fragments/table"))
                        .header("Cookie", sessions.cookieName() + "=" + sid)
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/html");
        assertThat(response.body()).contains("<table").contains("sato").contains("suzuki");
    }

    @Test
    void htmxFragmentRejectsWithoutSession() throws Exception {
        HttpResponse<String> response = get("/users/fragments/table", null);
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void commandSucceedsWithValidCsrf() throws Exception {
        SessionStore sessions = sessionStore();
        String sid = sessions.create(writer());

        HttpResponse<String> response = postJson("/users/deactivate",
                sessions.cookieName() + "=" + sid, sessions.csrfToken(sid),
                "{\"name\":\"suzuki\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).path("affected").asInt()).isEqualTo(1);
    }

    @Test
    void commandRejectedWithoutCsrfToken() throws Exception {
        SessionStore sessions = sessionStore();
        String sid = sessions.create(writer());

        HttpResponse<String> response = postJson("/users/deactivate",
                sessions.cookieName() + "=" + sid, null, "{\"name\":\"suzuki\"}");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(MAPPER.readTree(response.body()).path("error").path("code").asText())
                .isEqualTo("TQL-SEC-4032");
    }

    @Test
    void commandRejectsUnknownInputField() throws Exception {
        SessionStore sessions = sessionStore();
        String sid = sessions.create(writer());

        HttpResponse<String> response = postJson("/users/deactivate",
                sessions.cookieName() + "=" + sid, sessions.csrfToken(sid),
                "{\"name\":\"tanaka\",\"role\":\"ADMIN\"}");

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void commandIsIdempotentPerKey() throws Exception {
        SessionStore sessions = sessionStore();
        String sid = sessions.create(writer());
        String cookie = sessions.cookieName() + "=" + sid;
        String csrf = sessions.csrfToken(sid);
        String key = "idem-key-001";

        HttpResponse<String> first = postIdem("/users/deactivate", cookie, csrf, key,
                "{\"name\":\"suzuki\"}");
        assertThat(first.statusCode()).isEqualTo(200);

        // Same key, different request body -> conflict (409).
        HttpResponse<String> conflict = postIdem("/users/deactivate", cookie, csrf, key,
                "{\"name\":\"tanaka\"}");
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(conflict.body()).path("error").path("code").asText())
                .isEqualTo("TQL-IDEM-4090");

        // Same key, same request body -> replay of the original response.
        HttpResponse<String> replay = postIdem("/users/deactivate", cookie, csrf, key,
                "{\"name\":\"suzuki\"}");
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
    }

    private HttpResponse<String> postIdem(String path, String cookie, String csrf, String key,
            String body)
            throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("Content-Type", "application/json")
                        .header("Cookie", cookie)
                        .header("X-CSRF-Token", csrf)
                        .header("Idempotency-Key", key)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private SessionStore sessionStore() {
        return runtime.camelContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
    }

    private static Principal writer() {
        return new Principal("u001", "sato", "Sato", "tenant-a",
                List.of(), List.of("USER_READ", "USER_WRITE"), List.of(), Map.of());
    }

    private HttpResponse<String> postJson(String path, String cookie, String csrf, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        if (csrf != null) {
            request.header("X-CSRF-Token", csrf);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void exportStreamsCsv() throws Exception {
        HttpResponse<String> response = get("/api/users/export", token(List.of("USER_READ")));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/csv");
        assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
                .contains("users.csv");
        assertThat(response.body()).startsWith("id,name,status").contains("sato");
    }

    private JsonNode getJson(String path, String bearer) throws Exception {
        HttpResponse<String> response = get(path, bearer);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("application/json");
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + runtime.port() + path));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token(List<String> roles) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(
                Map.of("sub", "u001", "preferred_username", "sato", "roles", roles)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("truncate table users restart identity");
            statement.execute("""
                    insert into users (name, status) values
                      ('sato', 'ACTIVE'),
                      ('suzuki', 'ACTIVE'),
                      ('tanaka', 'INACTIVE')""");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-it-app");
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
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
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
            throw new java.io.UncheckedIOException(ex);
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
