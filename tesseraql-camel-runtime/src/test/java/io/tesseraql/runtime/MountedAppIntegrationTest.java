package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for mounted apps (design ch. 32): a second app listed under
 * {@code tesseraql.apps.<name>.path} is compiled by the same route compiler and served alongside
 * the main app, sharing its datasources.
 */
@Testcontainers
class MountedAppIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;
    static Path mountedHome;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        mountedHome = prepareMountedApp();
        appHome = prepareAppHome(mountedHome);
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        for (Path root : new Path[] {appHome, mountedHome}) {
            if (root != null) {
                deleteRecursively(root);
            }
        }
    }

    @Test
    void mountedAppRouteIsServed() throws Exception {
        HttpResponse<String> response = get("/sysapp/ping");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).get("data").get(0).get("ok").asInt())
                .isEqualTo(1);
    }

    @Test
    void namedQueriesBindEachResultSet() throws Exception {
        HttpResponse<String> response = get("/sysapp/multi?n=42");

        assertThat(response.statusCode()).isEqualTo(200);
        var json = MAPPER.readTree(response.body());
        assertThat(json.get("first").get(0).get("a").asInt()).isEqualTo(1);
        // The named query received its bind from the request input.
        assertThat(json.get("second").get(0).get("b").asInt()).isEqualTo(42);
    }

    @Test
    void mainAppRoutesStillWork() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + runtime.port() + "/api/users"))
                .header("Authorization", "Bearer " + token())
                .build();
        HttpResponse<String> response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        java.util.Base64.Encoder encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(
                "{\"alg\":\"HS256\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(MAPPER.writeValueAsBytes(
                java.util.Map.of("sub", "tester", "roles", java.util.List.of("USER_READ"))));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                "dev-only-secret-change-me-in-production"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static void seedDatabase() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("create table users (id serial primary key, name varchar(200), "
                    + "status varchar(32) not null, created_at timestamp default now())");
            statement.execute("insert into users (name, status) values ('sato','ACTIVE')");
        }
    }

    private static Path prepareMountedApp() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-mounted-app");
        Path routeDir = home.resolve("web/sysapp/ping");
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: sysapp.ping
                kind: route
                recipe: query-json
                sql:
                  file: ping.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(routeDir.resolve("ping.sql"), "select 1 as ok\n;\n");

        // A route with an additional named query (the queries: block) whose params come from
        // the request, rendering two result sets in one response.
        Path multiDir = home.resolve("web/sysapp/multi");
        Files.createDirectories(multiDir);
        Files.writeString(multiDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: sysapp.multi
                kind: route
                recipe: query-json
                input:
                  n:
                    type: integer
                    default: 7
                sql:
                  file: one.sql
                  mode: query
                queries:
                  second:
                    file: two.sql
                    mode: query
                    params:
                      n: query.n
                response:
                  json:
                    body:
                      first: sql.rows
                      second: second.rows
                """);
        Files.writeString(multiDir.resolve("one.sql"), "select 1 as a\n;\n");
        Files.writeString(multiDir.resolve("two.sql"), "select /* n */ 0 as b\n;\n");
        return home;
    }

    private static Path prepareAppHome(Path mounted) throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-mounted-it");
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
                  apps:
                    sysapp:
                      path: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), mounted));
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
