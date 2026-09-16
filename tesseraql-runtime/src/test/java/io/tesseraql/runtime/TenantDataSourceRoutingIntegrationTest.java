package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /**
     * A route-triggered export runs on the request's tenant pool (docs/multi-tenancy.md): the
     * file holds that tenant's rows, and the {@code after:} statement a first download fires —
     * on a later request — runs on the same pool. Until docs/audit-low-leads.md slice 3a both
     * ran on the main pool: every tenant, and an unknown one, was served the shared rows.
     */
    @Test
    void anExportServesTheTenantsOwnRowsAndItsAfterStatementLandsThere() throws Exception {
        String transferId = startTransfer("acme", "/api/items/export", "");
        assertThat(awaitTerminal("acme", "/api/items/export/" + transferId)
                .get("status").asText()).isEqualTo("COMPLETED");
        HttpResponse<String> file = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port()
                        + "/api/items/export/" + transferId + "/file"))
                        .header("X-Tenant-Id", "acme")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).contains("acme-only").doesNotContain("globex-only");

        // The after-download statement wrote its mark in acme's schema, nowhere else.
        assertThat(noteCount("acme", "exported-mark")).isEqualTo(1);
        assertThat(noteCount("globex", "exported-mark")).isZero();
        assertThat(noteCount("public", "exported-mark")).isZero();
    }

    @Test
    void anImportLandsInTheTenantsOwnDatasource() throws Exception {
        String transferId = startTransfer("globex", "/api/notes/import",
                "name\nglobex-imported\n");
        assertThat(awaitTerminal("globex", "/api/notes/import/" + transferId)
                .get("status").asText()).isEqualTo("COMPLETED");

        assertThat(noteCount("globex", "globex-imported")).isEqualTo(1);
        assertThat(noteCount("acme", "globex-imported")).isZero();
        assertThat(noteCount("public", "globex-imported")).isZero();
    }

    @Test
    void anUnknownTenantsTransfersAreRefusedBeforeAnyRow() throws Exception {
        HttpResponse<String> export = transferRequest("nope", "/api/items/export", "");
        assertThat(export.statusCode()).isEqualTo(403);
        assertThat(export.body()).contains("TQL-TENANT-4031");

        HttpResponse<String> imported = transferRequest("nope", "/api/notes/import",
                "name\nnope-imported\n");
        assertThat(imported.statusCode()).isEqualTo(403);
        assertThat(imported.body()).contains("TQL-TENANT-4031");
        assertThat(noteCount("public", "nope-imported")).isZero();
        assertThat(noteCount("acme", "nope-imported")).isZero();
        assertThat(noteCount("globex", "nope-imported")).isZero();
    }

    /**
     * A transfer's subtree is scoped by tenant (docs/audit-low-leads.md G31): another tenant
     * holding the link reads it as unknown — status, file and cancel alike — and the file leg,
     * which used to carry security alone, now resolves the tenant like the other legs, so a
     * request naming none is refused under {@code required: true}.
     */
    @Test
    void anotherTenantCannotSeeFetchOrCancelATransferAndTheFileLegResolvesTheTenant()
            throws Exception {
        String transferId = startTransfer("acme", "/api/items/export", "");
        assertThat(awaitTerminal("acme", "/api/items/export/" + transferId)
                .get("status").asText()).isEqualTo("COMPLETED");
        String base = "http://localhost:" + runtime.port() + "/api/items/export/" + transferId;

        HttpResponse<String> foreignStatus = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base)).header("X-Tenant-Id", "globex").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(foreignStatus.statusCode()).isEqualTo(404);
        assertThat(foreignStatus.body()).contains("TQL-LD-2822");
        HttpResponse<String> foreignFile = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + "/file"))
                        .header("X-Tenant-Id", "globex").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(foreignFile.statusCode()).isEqualTo(404);
        HttpResponse<String> foreignCancel = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + "/cancel"))
                        .header("X-Tenant-Id", "globex")
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(foreignCancel.statusCode()).isEqualTo(404);

        HttpResponse<String> noTenant = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + "/file")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(noTenant.statusCode()).isEqualTo(400);
        assertThat(noTenant.body()).contains("TQL-TENANT-4001");

        HttpResponse<String> own = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + "/file"))
                        .header("X-Tenant-Id", "acme").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(own.statusCode()).isEqualTo(200);
        assertThat(own.body()).contains("acme-only");
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

    private static HttpResponse<String> transferRequest(String tenant, String path, String body)
            throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("X-Tenant-Id", tenant)
                        .header("Content-Type", "text/csv")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String startTransfer(String tenant, String path, String body)
            throws Exception {
        HttpResponse<String> response = transferRequest(tenant, path, body);
        assertThat(response.statusCode()).isEqualTo(202);
        return MAPPER.readTree(response.body()).get("transferId").asText();
    }

    private static JsonNode awaitTerminal(String tenant, String statusPath) throws Exception {
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(20);
        while (true) {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + runtime.port() + statusPath))
                            .header("X-Tenant-Id", tenant)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode status = MAPPER.readTree(response.body());
            String state = status.get("status").asText();
            if (!"PENDING".equals(state) && !"RUNNING".equals(state)) {
                return status;
            }
            assertThat(java.time.Instant.now()).as("transfer " + statusPath + " terminal in time")
                    .isBefore(deadline);
            Thread.sleep(100);
        }
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
        UserAdminAppCopy.prepare(target);
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
                  app:
                    name: tenant-datasource-routing
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

        // The transfer legs: an export of items with an after-download mark into notes, and an
        // import into notes — both must run on the tenant's pool, and both must be refused for
        // a tenant the reads refuse.
        Path exportDir = target.resolve("web/api/items/export");
        Files.createDirectories(exportDir);
        Files.writeString(exportDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.export
                kind: route
                recipe: file-export

                security:
                  auth: public

                export:
                  format: csv
                  filename: items.csv
                  after:
                    timing: download
                    sql:
                      file: mark.sql
                sources:
                  main:
                    sql:
                      file: export.sql
                """);
        Files.writeString(exportDir.resolve("export.sql"),
                "select id, name from items order by id\n");
        Files.writeString(exportDir.resolve("mark.sql"),
                "insert into notes (name) values ('exported-mark')\n");

        Path importDir = target.resolve("web/api/notes/import");
        Files.createDirectories(importDir);
        Files.writeString(importDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: notes.import
                kind: route
                recipe: file-import

                security:
                  auth: public

                import:
                  format: csv
                  columns:
                    - name
                steps:
                  - id: row
                    sql:
                      file: insert-note.sql
                """);
        Files.writeString(importDir.resolve("insert-note.sql"),
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

}
