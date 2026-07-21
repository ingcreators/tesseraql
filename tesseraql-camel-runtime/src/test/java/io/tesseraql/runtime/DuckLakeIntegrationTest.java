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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for lake tables (docs/duckdb.md "Lake tables"): a DuckLake attach whose
 * metadata lives in a named schema on {@code main}, its Parquet data under a fence-admitted
 * directory — an ETL job appends a snapshot per run, a route reads current beside time-traveled
 * history, a read-only lake refuses writes, and the fence holds throughout.
 */
@Testcontainers
class DuckLakeIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    TesseraqlRuntime runtime;
    Path appHome;

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
    void appendsSnapshotsReadsHistoryAndHoldsTheFence() throws Exception {
        appHome = prepareApp();

        runtime = TesseraqlRuntime.start(appHome, freePort());

        // Two job runs: each appends to the lake table, each commit a snapshot.
        for (int run = 0; run < 2; run++) {
            assertThat(runtime.runJob("pricing.appendHistory", Map.of()).status().name())
                    .isEqualTo("COMPLETED");
        }

        // Current state holds both runs' rows; the time-traveled read sees only the first.
        HttpResponse<String> history = get("/api/history");
        assertThat(history.statusCode()).isEqualTo(200);
        var body = MAPPER.readTree(history.body());
        assertThat(body.get("current").size()).isEqualTo(2);
        assertThat(body.get("firstRun").size()).isEqualTo(1);

        // The read-only lake on the reporting datasource refuses the same write.
        assertThat(runtime.runJob("pricing.readOnlyProbe", Map.of()).status().name())
                .isEqualTo("FAILED");

        // The metadata landed on main, confined to the ducklake schema.
        try (Connection pg = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = pg.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select count(*) from information_schema.tables"
                                + " where table_schema = 'ducklake'")) {
            rs.next();
            assertThat(rs.getInt(1)).isGreaterThan(10);
        }

        // The fence still holds on the lake datasource.
        assertThat(get("/api/outside").statusCode()).isEqualTo(500);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Path prepareApp() throws Exception {
        Path home = Files.createTempDirectory("tesseraql-ducklake");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s

                tesseraql:
                  app:
                    name: ducklake-app
                  datasources:
                    main:
                      jdbcUrl: ${db.main.url}
                      username: ${db.main.username}
                      password: ${db.main.password}
                    analytics:
                      jdbcUrl: "jdbc:duckdb:"
                      duckdb:
                        extensions: [ducklake, postgres]
                        lake:
                          catalog: main
                          data: data/lake
                          mode: readwrite
                        fileScopes:
                          drops:
                            root: data/drops
                    reporting:
                      jdbcUrl: "jdbc:duckdb:"
                      duckdb:
                        extensions: [ducklake, postgres]
                        lake:
                          catalog: main
                          data: data/lake
                          mode: readonly
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        // Offline extension cache, provisioned ahead of boot as the command would.
        Path cache = home.resolve("work/duckdb-extensions");
        Files.createDirectories(cache);
        Properties props = new Properties();
        props.setProperty("extension_directory", cache.toString());
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:", props);
                Statement statement = duck.createStatement()) {
            statement.execute("INSTALL ducklake");
            statement.execute("INSTALL postgres");
        }

        Path drops = home.resolve("data/drops");
        Files.createDirectories(drops);
        Files.createDirectories(home.resolve("data/lake"));
        Files.writeString(drops.resolve("prices.csv"), "sku,price\nW-1,12.5\nG-7,7.25\n");

        Path job = home.resolve("batch/pricing");
        Files.createDirectories(job);
        Files.writeString(job.resolve("append-history.yml"), """
                version: tesseraql/v1
                id: pricing.appendHistory
                kind: job
                recipe: batch-pipeline
                datasource: analytics
                trigger:
                  schedule:
                    cron: "0 0 4 1 1 ? 2099"
                pipeline:
                  - id: ensure
                    sql: { file: ensure-history.sql, mode: update }
                  - id: append
                    sql: { file: append-history.sql, mode: update }
                """);
        Files.writeString(job.resolve("ensure-history.sql"),
                "create table if not exists lake.price_history"
                        + " (sku varchar, best_price double, loaded_at timestamp)\n");
        Files.writeString(job.resolve("append-history.sql"), """
                insert into lake.price_history
                select sku, min(price) as best_price, now()
                from read_csv(/* ${scope.drops}/prices.csv */ 'dummy.csv')
                group by sku
                order by sku
                limit 1
                """);
        Files.writeString(job.resolve("read-only-probe.yml"), """
                version: tesseraql/v1
                id: pricing.readOnlyProbe
                kind: job
                recipe: batch-tasklet
                datasource: reporting
                trigger:
                  schedule:
                    cron: "0 0 4 1 1 ? 2099"
                sql:
                  file: read-only-probe.sql
                  mode: update
                """);
        Files.writeString(job.resolve("read-only-probe.sql"),
                "insert into lake.price_history values ('X-0', 1.0, now())\n");

        Path route = home.resolve("web/api/history");
        Files.createDirectories(route);
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: history.read
                kind: route
                recipe: query-json
                datasource: analytics
                sql:
                  file: current.sql
                  mode: query
                queries:
                  firstRun:
                    file: first-run.sql
                response:
                  json:
                    body:
                      current: sql.rows
                      firstRun: firstRun.rows
                """);
        Files.writeString(route.resolve("current.sql"),
                "select sku, best_price from lake.price_history order by loaded_at\n");
        // Snapshot numbering: 0 schema, 1 table creation, 2 first append (see the probe);
        // the second run's ensure is a no-op, its append is snapshot 3.
        Files.writeString(route.resolve("first-run.sql"),
                "select sku, best_price from lake.price_history at (version => 2)\n");

        Path outside = home.resolve("web/api/outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("get.yml"), """
                version: tesseraql/v1
                id: outside.read
                kind: route
                recipe: query-json
                datasource: analytics
                sql:
                  file: outside.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(outside.resolve("outside.sql"),
                "select * from read_csv('/etc/hostname')\n");
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
