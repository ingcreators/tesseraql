package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.operations.app.AppCatalog;
import io.tesseraql.operations.app.InstalledApp;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for single-port multi-app routing (design ch. 32.7). Two installed apps are
 * fronted by one gateway port and routed by the {@code /apps/<appId>/} prefix; each reaches only
 * its own isolated app, and an unknown app returns 404.
 */
@Testcontainers
class MultiAppGatewayIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static MultiAppGateway gateway;
    static Path installRoot;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        installRoot = Files.createTempDirectory("tesseraql-gateway-it");
        installApp("shop-a", "a", List.of());
        installApp("shop-b", "b", List.of("tenant-b"));
        gateway = MultiAppGateway.start(installRoot, 0);
    }

    @AfterAll
    static void stop() throws IOException {
        if (gateway != null) {
            gateway.close();
        }
        if (installRoot != null) {
            deleteRecursively(installRoot);
        }
    }

    @Test
    void routesByAppPrefixOnOnePort() throws Exception {
        assertThat(gateway.appIds()).containsExactlyInAnyOrder("shop-a", "shop-b");

        assertThat(itemName("shop-a")).isEqualTo("from-a");
        assertThat(itemName("shop-b")).isEqualTo("from-b");
    }

    @Test
    void unknownAppReturns404() throws Exception {
        HttpResponse<String> response = get("/apps/nope/api/items");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("TQL-APP-4040");
    }

    @Test
    void enforcesTenantEntitlementAtTheFrontDoor() throws Exception {
        // shop-b is entitled to tenant-b only; a request declaring another tenant is refused
        // before it reaches the app, while the entitled tenant passes through.
        HttpResponse<String> denied = getWithTenant("/apps/shop-b/api/items", "tenant-x");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("TQL-APP-4030");

        HttpResponse<String> allowed = getWithTenant("/apps/shop-b/api/items", "tenant-b");
        assertThat(allowed.statusCode()).isEqualTo(200);

        // shop-a has no entitlement list, so every tenant is served.
        assertThat(getWithTenant("/apps/shop-a/api/items", "tenant-x").statusCode()).isEqualTo(200);
    }

    private static HttpResponse<String> getWithTenant(String path, String tenantId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + gateway.port() + path))
                .header("X-Tenant-Id", tenantId)
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void routesByHostHeader() throws Exception {
        // The same path on different hosts reaches different apps (no /apps prefix).
        assertThat(itemNameForHost("shop-a.localhost")).isEqualTo("from-a");
        assertThat(itemNameForHost("shop-b.localhost")).isEqualTo("from-b");
    }

    private static String itemNameForHost(String hostName) throws Exception {
        String body = rawGet("/api/items", hostName);
        JsonNode data = MAPPER.readTree(body).get("data");
        assertThat(data).hasSize(1);
        return data.get(0).get("name").asText();
    }

    /** Sends a raw HTTP/1.1 GET so a custom Host header can be set (the HTTP client forbids it). */
    private static String rawGet(String path, String hostName) throws IOException {
        try (java.net.Socket socket = new java.net.Socket("localhost", gateway.port())) {
            String request = "GET " + path + " HTTP/1.1\r\nHost: " + hostName
                    + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertThat(response).startsWith("HTTP/1.1 200");
            int split = response.indexOf("\r\n\r\n");
            return response.substring(split + 4);
        }
    }

    private static String itemName(String appId) throws Exception {
        HttpResponse<String> response = get("/apps/" + appId + "/api/items");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = MAPPER.readTree(response.body()).get("data");
        assertThat(data).hasSize(1);
        return data.get(0).get("name").asText();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + gateway.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (String schema : new String[] {"a", "b"}) {
                statement.execute("create schema " + schema);
                statement.execute("create table " + schema
                        + ".items (id serial primary key, name varchar(200) not null)");
                statement.execute("insert into " + schema + ".items (name) values ('from-" + schema + "')");
            }
        }
    }

    private static void installApp(String appId, String schema, List<String> entitledTenants)
            throws IOException {
        Path appHome = installRoot.resolve(appId).resolve("1.0.0");
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, appHome, path));
        }
        Files.writeString(appHome.resolve("config/application.yml"), """
                server:
                  port: 0
                db:
                  main:
                    url: %s&currentSchema=%s
                    username: %s
                    password: %s
                """.formatted(POSTGRES.getJdbcUrl(), schema,
                POSTGRES.getUsername(), POSTGRES.getPassword()));

        Path itemsDir = appHome.resolve("web/api/items");
        Files.createDirectories(itemsDir);
        Files.writeString(itemsDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                security:
                  auth: public
                sql:
                  file: list.sql
                  mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: sql.rows
                """);
        Files.writeString(itemsDir.resolve("list.sql"), "select id, name from items order by id\n");

        new AppCatalog(installRoot).register(new InstalledApp(
                appId, "1.0.0", appId + "/1.0.0", entitledTenants, List.of(appId + ".localhost")));
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
