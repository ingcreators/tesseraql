package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for Phase 53 slice 2 (docs/multi-datasource.md): a read route picks a named
 * connector with {@code datasource:}, a page-style route mixes connectors per named query, and
 * the named connector's {@code db/<name>/migration} tree migrated the second database — two real
 * PostgreSQL databases, one runtime.
 */
@Testcontainers
class MultiDatasourceReadIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    TesseraqlRuntime runtime;
    Path appHome;

    @BeforeAll
    static void createReportingDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create database reporting");
        }
    }

    @AfterEach
    void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void readsRunOnTheRouteAndQueryLevelConnector() throws Exception {
        appHome = prepareApp();

        runtime = TesseraqlRuntime.start(appHome, freePort());

        // The route-level connector: the whole read runs on the reporting database, whose table
        // exists only there (db/reporting/migration created and seeded it).
        HttpResponse<String> summary = get("/api/sales/summary");
        assertThat(summary.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(summary.body()).get("data").get(0).get("region").asText())
                .isEqualTo("reporting-only");

        // The per-query override: one response composed from both connectors.
        HttpResponse<String> dashboard = get("/api/dashboard");
        assertThat(dashboard.statusCode()).isEqualTo(200);
        var body = MAPPER.readTree(dashboard.body());
        assertThat(body.get("open").get(0).get("name").asText()).isEqualTo("main-only");
        assertThat(body.get("turnover").get(0).get("region").asText())
                .isEqualTo("reporting-only");
    }

    private static String reportingUrl() {
        // The container URL points at the default database; the reporting connector swaps the
        // database name on the same server.
        String url = POSTGRES.getJdbcUrl();
        return url.replace("/" + POSTGRES.getDatabaseName(), "/reporting");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Path prepareApp() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-multi-datasource");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s
                  reporting:
                    url: %s

                tesseraql:
                  app:
                    name: multi-ds-app
                  datasources:
                    main:
                      jdbcUrl: ${db.main.url}
                      username: ${db.main.username}
                      password: ${db.main.password}
                    reporting:
                      jdbcUrl: ${db.reporting.url}
                      username: ${db.main.username}
                      password: ${db.main.password}
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), reportingUrl()));

        Path mainMigrations = home.resolve("db/migration");
        Files.createDirectories(mainMigrations);
        Files.writeString(mainMigrations.resolve("V1__create_orders.sql"),
                "create table open_orders (id serial primary key, name varchar(100) not null);\n"
                        + "insert into open_orders (name) values ('main-only');\n");
        Path reportingMigrations = home.resolve("db/reporting/migration");
        Files.createDirectories(reportingMigrations);
        Files.writeString(reportingMigrations.resolve("V1__create_turnover.sql"),
                "create table turnover (id serial primary key, region varchar(100) not null);\n"
                        + "insert into turnover (region) values ('reporting-only');\n");

        Path summaryRoute = home.resolve("web/api/sales/summary");
        Files.createDirectories(summaryRoute);
        Files.writeString(summaryRoute.resolve("get.yml"), """
                version: tesseraql/v1
                id: sales.summary
                kind: route
                recipe: query-json
                datasource: reporting
                sql:
                  file: summary.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(summaryRoute.resolve("summary.sql"),
                "select region from turnover order by id\n;\n");

        Path dashboardRoute = home.resolve("web/api/dashboard");
        Files.createDirectories(dashboardRoute);
        Files.writeString(dashboardRoute.resolve("get.yml"), """
                version: tesseraql/v1
                id: dashboard.view
                kind: route
                recipe: query-json
                sql:
                  file: open.sql
                  mode: query
                queries:
                  turnover:
                    file: turnover.sql
                    mode: query
                    datasource: reporting
                response:
                  json:
                    body:
                      open: sql.rows
                      turnover: turnover.rows
                """);
        Files.writeString(dashboardRoute.resolve("open.sql"),
                "select name from open_orders order by id\n;\n");
        Files.writeString(dashboardRoute.resolve("turnover.sql"),
                "select region from turnover order by id\n;\n");
        return home;
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
