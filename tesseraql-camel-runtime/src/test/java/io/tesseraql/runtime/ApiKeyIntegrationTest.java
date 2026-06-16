package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for API-key authentication of service callers (roadmap Phase 25): a route
 * declaring {@code auth: apiKey} accepts a valid key (in the configured header or as
 * {@code Authorization: ApiKey}), denies an invalid or missing key (401), and applies the same
 * authorization policy so an under-privileged key is forbidden (403).
 */
@Testcontainers
class ApiKeyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String BILLING_KEY = "billing-raw-key-001";
    private static final String READONLY_KEY = "readonly-raw-key-002";

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        seedDatabase();
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
    void acceptsValidApiKeyInHeader() throws Exception {
        HttpResponse<String> response = get("X-API-Key", BILLING_KEY);
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void acceptsValidApiKeyAsAuthorizationScheme() throws Exception {
        HttpResponse<String> response = get("Authorization", "ApiKey " + BILLING_KEY);
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsInvalidApiKey() throws Exception {
        HttpResponse<String> response = get("X-API-Key", "not-a-real-key");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsMissingApiKey() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest
                        .newBuilder(URI.create("http://localhost:" + runtime.port() + "/api/svc"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void forbidsKeyWithoutRequiredRole() throws Exception {
        // The readonly client authenticates but lacks USER_READ, so the policy denies it (403).
        HttpResponse<String> response = get("X-API-Key", READONLY_KEY);
        assertThat(response.statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> get(String header, String value) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest
                        .newBuilder(URI.create("http://localhost:" + runtime.port() + "/api/svc"))
                        .header(header, value).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("truncate table users restart identity");
            statement.execute("insert into users (name, status) values ('a','ACTIVE')");
        }
    }

    private static Path prepareAppHome() throws Exception {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-apikey-it");
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
        // Add an API-key block (the billing client carries USER_READ; readonly carries nothing) by
        // injecting it ahead of the existing jwt block under tesseraql.security.
        String apiKeys = "    apiKeys:\n"
                + "      header: X-API-Key\n"
                + "      clients:\n"
                + "        billing:\n"
                + "          secretHash: " + sha256Hex(BILLING_KEY) + "\n"
                + "          subject: svc:billing\n"
                + "          roles: [USER_READ]\n"
                + "        readonly:\n"
                + "          secretHash: " + sha256Hex(READONLY_KEY) + "\n"
                + "          subject: svc:readonly\n"
                + "    jwt:\n";
        Path config = target.resolve("config/tesseraql.yml");
        Files.writeString(config, Files.readString(config).replace("    jwt:\n", apiKeys));
        // A service route protected by an API key, reusing the example's users.read policy.
        Files.createDirectories(target.resolve("web/api/svc"));
        Files.writeString(target.resolve("web/api/svc/list.sql"),
                "select id, name from users order by id\n");
        Files.writeString(target.resolve("web/api/svc/get.yml"), """
                version: tesseraql/v1
                id: svc.list
                kind: route
                recipe: query-json
                security:
                  auth: apiKey
                  policy: users.read
                sql:
                  file: list.sql
                  mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: sql.rows
                """);
        return target;
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
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
