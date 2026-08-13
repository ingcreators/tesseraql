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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for per-tenant datasource routing (design ch. 30.2). Each tenant maps to its own
 * pool (here, distinct schemas via {@code currentSchema}); the {@code items} table has no tenant
 * column, so isolation is proven at the datasource level. An unknown tenant is rejected with 403.
 */
@Testcontainers
class TenantDataSourceRoutingIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

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
    void eachTenantReadsFromItsOwnDatasource() throws Exception {
        JsonNode acme = get("acme", 200);
        assertThat(acme.get("data")).hasSize(1);
        assertThat(acme.get("data").get(0).get("name").asText()).isEqualTo("acme-only");

        JsonNode globex = get("globex", 200);
        assertThat(globex.get("data")).hasSize(1);
        assertThat(globex.get("data").get(0).get("name").asText()).isEqualTo("globex-only");
    }

    @Test
    void anExplicitConnectorIsNotOverriddenByTenantRouting() throws Exception {
        // The route pins datasource: reporting (roadmap Phase 53); the tenant header still
        // resolves a tenant, but tenant routing replaces only the main connector.
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/report"))
                        .header("X-Tenant-Id", "acme")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("data").get(0).get("name").asText()).isEqualTo("reporting-only");
    }

    @Test
    void aWriteLandsInTheTenantsOwnDatasource() throws Exception {
        assertThat(post("acme", "acme-written").statusCode()).isEqualTo(201);

        // The tenant that wrote owns the row; its neighbour does not have it, and neither does
        // the shared pool the command resolved to before tenant routing reached the write path.
        assertThat(noteCount("acme", "acme-written")).isEqualTo(1);
        assertThat(noteCount("globex", "acme-written")).isZero();
        assertThat(noteCount("public", "acme-written")).isZero();
    }

    @Test
    void anUnknownTenantsWriteIsRejectedAndCommitsNothing() throws Exception {
        HttpResponse<String> response = post("nope", "never-written");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("TQL-TENANT-4031");
        // The read path already refuses this tenant; the write must not slip into the shared
        // pool behind that refusal.
        assertThat(noteCount("public", "never-written")).isZero();
        assertThat(noteCount("acme", "never-written")).isZero();
        assertThat(noteCount("globex", "never-written")).isZero();
    }

    @Test
    void unknownTenantIsRejected() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/items"))
                        .header("X-Tenant-Id", "nope")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("TQL-TENANT-4031");
    }

    private static HttpResponse<String> post(String tenant, String name) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/notes"))
                        .header("X-Tenant-Id", tenant)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"name\": \"" + name + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Reads straight past the framework, so the assertion is about rows on disk. */
    private static int noteCount(String schema, String name) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from " + schema + ".notes where name = ?")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static JsonNode get(String tenant, int expectedStatus) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/items"))
                        .header("X-Tenant-Id", tenant)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        return MAPPER.readTree(response.body());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (String tenant : new String[]{"acme", "globex"}) {
                statement.execute("create schema " + tenant);
                statement.execute("create table " + tenant
                        + ".items (id serial primary key, name varchar(200) not null)");
                statement.execute("insert into " + tenant
                        + ".items (name) values ('" + tenant + "-only')");
            }
            // notes lives in every tenant schema AND in the main pool's default schema, so a
            // write that resolves the wrong datasource lands somewhere observable instead of
            // failing on a missing table — that is the shape of the bug this guards.
            for (String schema : new String[]{"acme", "globex", "public"}) {
                statement.execute("create table " + schema
                        + ".notes (id serial primary key, name varchar(200) not null)");
            }
            // A deployment-shared reporting area, reached by an explicit datasource: only.
            statement.execute("create schema reporting_s");
            statement.execute("create table reporting_s.items"
                    + " (id serial primary key, name varchar(200) not null)");
            statement.execute("insert into reporting_s.items (name) values ('reporting-only')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-tenant-ds-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        String baseUrl = POSTGRES.getJdbcUrl();
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %1$s
                    username: %2$s
                    password: %3$s

                tenancy:
                  enabled: true
                  mode: database-per-tenant
                  required: true
                  resolver:
                    type: header
                    source: X-Tenant-Id
                  datasources:
                    acme:
                      jdbcUrl: %1$s&currentSchema=acme
                      username: %2$s
                      password: %3$s
                    globex:
                      jdbcUrl: %1$s&currentSchema=globex
                      username: %2$s
                      password: %3$s

                tesseraql:
                  datasources:
                    reporting:
                      jdbcUrl: %1$s&currentSchema=reporting_s
                      username: %2$s
                      password: %3$s
                """.formatted(baseUrl, POSTGRES.getUsername(), POSTGRES.getPassword()));

        Path itemsDir = target.resolve("web/api/items");
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

        Path reportDir = target.resolve("web/api/report");
        Files.createDirectories(reportDir);
        Files.writeString(reportDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: report.list
                kind: route
                recipe: query-json
                datasource: reporting

                security:
                  auth: public

                sources:
                  main:
                    sql:
                      file: report.sql
                      mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: main.rows
                """);
        Files.writeString(reportDir.resolve("report.sql"),
                "select id, name from items order by id\n");

        // The write leg: a command must land in the same database its reads come from. It uses
        // its own table so the row it adds cannot perturb the read assertions above.
        Path notesDir = target.resolve("web/api/notes");
        Files.createDirectories(notesDir);
        Files.writeString(notesDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: notes.create
                kind: route
                recipe: command-json

                security:
                  auth: public

                input:
                  name: { type: string, required: true, maxLength: 200 }

                steps:
                  - id: main
                    sql:
                      file: insert.sql
                      mode: update
                      params:
                        name: params.name
                response:
                  json:
                    status: 201
                    body:
                      created: steps.main.affectedRows
                """);
        Files.writeString(notesDir.resolve("insert.sql"),
                "insert into notes (name) values (/* name */ 'sample')\n");
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
