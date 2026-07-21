package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Integration test for the duckdb extension + attach + ETL leg (docs/duckdb.md): the {@code
 * postgres} extension loads from a pre-provisioned offline cache (autoinstall/autoload off), the
 * framework-managed {@code attach:} surfaces {@code main} with injected credentials, a batch job
 * joins a Parquet drop against the engine and lands the result durably on PostgreSQL — re-runnable
 * — while a read-only attach refuses writes and the post-init fence still refuses INSTALL.
 */
@Testcontainers
class DuckDbEtlIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

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
    void loadsExtensionOfflineAttachesMainAndRunsTheEtlJob() throws Exception {
        appHome = prepareApp();

        runtime = TesseraqlRuntime.start(appHome, freePort());

        // The pull-ETL job: Parquet × engine SQL, landed on the attached main — replace-the-window
        // shaped, so a second run converges instead of doubling.
        for (int run = 0; run < 2; run++) {
            assertThat(runtime.runJob("sales.loadSummary", Map.of()).status().name())
                    .isEqualTo("COMPLETED");
        }
        try (Connection pg = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = pg.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select category, total from sales_summary order by total desc")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("widgets");
            assertThat(rs.getLong(2)).isEqualTo(300);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("gadgets");
            assertThat(rs.next()).isFalse();
        }

        // The read-only attach: the same write against mode: readonly fails the execution.
        assertThat(runtime.runJob("sales.readOnlyProbe", Map.of()).status().name())
                .isEqualTo("FAILED");

        // The fence outlives init: with the extension loaded and the attach established,
        // app SQL still cannot INSTALL anything.
        HttpResponse<String> install = get("/api/install");
        assertThat(install.statusCode()).isEqualTo(500);

        // And the attached main is queryable from a read route through the alias.
        HttpResponse<String> joined = get("/api/joined");
        assertThat(joined.statusCode()).isEqualTo(200);
        assertThat(joined.body()).contains("widgets");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Path prepareApp() throws Exception {
        Path home = Files.createTempDirectory("tesseraql-duckdb-etl");
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
                    name: duckdb-etl-app
                  datasources:
                    main:
                      jdbcUrl: ${db.main.url}
                      username: ${db.main.username}
                      password: ${db.main.password}
                    analytics:
                      jdbcUrl: "jdbc:duckdb:"
                      duckdb:
                        extensions: [postgres]
                        attach:
                          - { datasource: main, as: app, mode: readwrite }
                        fileScopes:
                          sales:
                            root: data/sales
                    analyticsRo:
                      jdbcUrl: "jdbc:duckdb:"
                      duckdb:
                        extensions: [postgres]
                        attach:
                          - { datasource: main, as: app, mode: readonly }
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        Path mainMigrations = home.resolve("db/migration");
        Files.createDirectories(mainMigrations);
        Files.writeString(mainMigrations.resolve("V1__create_summary.sql"),
                "create table sales_summary (category varchar(100) primary key,"
                        + " total bigint not null);\n");

        // The offline extension cache: provisioned ahead of boot, exactly as
        // `tesseraql duckdb install-extensions` would; the runtime itself never downloads.
        Path extensionCache = home.resolve("work/duckdb-extensions");
        Files.createDirectories(extensionCache);
        Properties props = new Properties();
        props.setProperty("extension_directory", extensionCache.toString());
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:", props);
                Statement statement = duck.createStatement()) {
            statement.execute("INSTALL postgres");
        }

        Path drops = home.resolve("data/sales");
        Files.createDirectories(drops);
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = duck.createStatement()) {
            statement.execute("""
                    COPY (
                      SELECT * FROM (VALUES
                        ('widgets', 300), ('gadgets', 120)
                      ) AS t(category, total)
                    ) TO '%s' (FORMAT parquet)
                    """.formatted(drops.resolve("monthly.parquet")));
        }

        Path job = home.resolve("batch/sales");
        Files.createDirectories(job);
        Files.writeString(job.resolve("load-summary.yml"), """
                version: tesseraql/v1
                id: sales.loadSummary
                kind: job
                recipe: batch-pipeline
                datasource: analytics
                trigger:
                  schedule:
                    cron: "0 0 4 1 1 ? 2099"
                pipeline:
                  - id: clear
                    sql:
                      file: clear-summary.sql
                      mode: update
                  - id: load
                    sql:
                      file: load-summary.sql
                      mode: update
                """);
        Files.writeString(job.resolve("clear-summary.sql"),
                "delete from app.sales_summary\n");
        Files.writeString(job.resolve("load-summary.sql"), """
                insert into app.sales_summary (category, total)
                select category, sum(total) as total
                from read_parquet(/* ${scope.sales}/monthly.parquet */ 'dummy.parquet')
                group by category
                """);
        Files.writeString(job.resolve("read-only-probe.yml"), """
                version: tesseraql/v1
                id: sales.readOnlyProbe
                kind: job
                recipe: batch-tasklet
                datasource: analyticsRo
                trigger:
                  schedule:
                    cron: "0 0 4 1 1 ? 2099"
                sql:
                  file: read-only-probe.sql
                  mode: update
                """);
        Files.writeString(job.resolve("read-only-probe.sql"),
                "insert into app.sales_summary (category, total) values ('ghost', 1)\n");

        Path installRoute = home.resolve("web/api/install");
        Files.createDirectories(installRoute);
        Files.writeString(installRoute.resolve("get.yml"), """
                version: tesseraql/v1
                id: fence.install
                kind: route
                recipe: query-json
                datasource: analytics
                sql:
                  file: install.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(installRoute.resolve("install.sql"), "INSTALL httpfs\n");

        Path joinedRoute = home.resolve("web/api/joined");
        Files.createDirectories(joinedRoute);
        Files.writeString(joinedRoute.resolve("get.yml"), """
                version: tesseraql/v1
                id: joined.read
                kind: route
                recipe: query-json
                datasource: analytics
                sql:
                  file: joined.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(joinedRoute.resolve("joined.sql"), """
                select s.category, s.total, p.total as landed
                from read_parquet(/* ${scope.sales}/monthly.parquet */ 'dummy.parquet') s
                left join app.sales_summary p on p.category = s.category
                where s.category = 'widgets'
                group by all
                """);
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
