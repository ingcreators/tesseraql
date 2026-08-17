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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for runtime multi-app hosting (design ch. 32.7). Two installed apps catalogued
 * under one install root are hosted simultaneously, each isolated in its own runtime, port, and
 * database schema; each app serves only its own data.
 */
@Testcontainers
class MultiAppHostIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static MultiAppHost host;
    static Path installRoot;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        installRoot = Files.createTempDirectory("tesseraql-multiapp-it");
        installApp("shop-a", "a", null);
        // shop-b declares a base path of its own, which the derived address has to outrank —
        // an application's address is its name, and its own configuration cannot move it.
        installApp("shop-b", "b", "/legacy");
        // Business data is isolated by schema, so the main coordinates differ; the stack
        // supplies the framework connection (docs/stack-architecture.md decision 22).
        Files.writeString(installRoot.resolve(
                io.tesseraql.operations.app.StackSettings.FILE_NAME),
                """
                        framework:
                          datasource:
                            jdbcUrl: %s
                            username: %s
                            password: %s
                        """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                        POSTGRES.getPassword()));
        host = MultiAppHost.start(installRoot);
    }

    @AfterAll
    static void stop() throws IOException {
        if (host != null) {
            host.close();
        }
        if (installRoot != null) {
            deleteRecursively(installRoot);
        }
    }

    @Test
    void hostsBothAppsEachServingOwnData() throws Exception {
        assertThat(host.appNames()).containsExactlyInAnyOrder("shop-a", "shop-b");

        assertThat(itemName("shop-a", "/shop-a")).isEqualTo("from-a");
        assertThat(itemName("shop-b", "/shop-b")).isEqualTo("from-b");
    }

    /**
     * The address is derived from the name and the host starts the runtime serving it, so the app
     * answers on its own port at the same path the gateway forwards (docs/base-path.md decision 5).
     * Hosting used to leave the prefix to the caller, and this entry point passed none at all.
     */
    @Test
    void anAppIsStartedServingTheAddressDerivedFromItsName() throws Exception {
        assertThat(get("shop-a", "/api/items").statusCode()).isEqualTo(404);
        assertThat(get("shop-a", "/shop-a/api/items").statusCode()).isEqualTo(200);
    }

    /**
     * The derived address outranks the application's own {@code tesseraql.http.basePath}
     * (docs/stack-architecture.md Decision 25): an application's configuration cannot move where
     * it answers, or the gateway would forward it paths it does not serve — a 404 on every
     * request, invisibly.
     */
    @Test
    void theDerivedAddressOutranksTheApplicationsOwnBasePath() throws Exception {
        assertThat(get("shop-b", "/legacy/api/items").statusCode()).isEqualTo(404);
        assertThat(get("shop-b", "/shop-b/api/items").statusCode()).isEqualTo(200);
    }

    private static String itemName(String appId, String prefix) throws Exception {
        HttpResponse<String> response = get(appId, prefix + "/api/items");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = MAPPER.readTree(response.body()).get("data");
        assertThat(data).hasSize(1);
        return data.get(0).get("name").asText();
    }

    private static HttpResponse<String> get(String appId, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + host.port(appId) + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (String schema : new String[]{"a", "b"}) {
                statement.execute("create schema " + schema);
                statement.execute("create table " + schema
                        + ".items (id serial primary key, name varchar(200) not null)");
                statement.execute(
                        "insert into " + schema + ".items (name) values ('from-" + schema + "')");
            }
        }
    }

    /**
     * Installs a copy of the example app under {@code appId}, bound to the given DB schema.
     *
     * @param ownBasePath the prefix the application's own configuration names, or null — the
     *                    derived address outranks it either way
     */
    private static void installApp(String appId, String schema, String ownBasePath)
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
                POSTGRES.getUsername(), POSTGRES.getPassword())
                + (ownBasePath == null ? "" : """
                        tesseraql:
                          http:
                            basePath: %s
                        """.formatted(ownBasePath)));

        Path itemsDir = appHome.resolve("web/api/items");
        Files.createDirectories(itemsDir);
        Files.writeString(itemsDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: list.sql
                      mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: main.rows
                """);
        Files.writeString(itemsDir.resolve("list.sql"), "select id, name from items order by id\n");

        new AppCatalog(installRoot).register(
                new InstalledApp(appId, "1.0.0", appId + "/1.0.0", List.of()));
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
