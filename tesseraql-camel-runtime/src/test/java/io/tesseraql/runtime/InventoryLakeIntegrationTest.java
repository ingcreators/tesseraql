package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Milestone M24 (docs/roadmap.md, Phase 59) against the REAL inventory gallery app: the nightly
 * pricing job lands each run in {@code lake.price_history} as a snapshot; a dashboard reads
 * current history beside a time-traveled prior version; concurrent writers on separate engine
 * instances both commit, serialized by the catalog on {@code main}; the {@code ducklake} schema
 * coexists with Flyway across a runtime restart; and an expiry job prunes snapshots and removes
 * the Parquet files no surviving snapshot references — with the fence intact throughout.
 */
@Testcontainers
class InventoryLakeIntegrationTest {

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
    void meetsMilestoneM24OnTheInventoryApp() throws Exception {
        appHome = prepareApp();

        runtime = TesseraqlRuntime.start(appHome, freePort());

        // The nightly job, twice: summary converges on main, history gains a snapshot per run.
        for (int run = 0; run < 2; run++) {
            assertThat(runtime.runJob("pricing.loadSummary", Map.of()).status().name())
                    .isEqualTo("COMPLETED");
        }

        // Current history holds both runs; the time-traveled read sees only the first
        // (snapshots: 0 schema, 1 table, 2 the first append — proven shape).
        HttpResponse<String> board = get("/lakeboard");
        assertThat(board.statusCode()).isEqualTo(200);
        var body = MAPPER.readTree(board.body());
        assertThat(body.get("current").size()).isEqualTo(4);
        assertThat(body.get("firstRun").size()).isEqualTo(2);

        // Concurrent writers on two SEPARATE engine instances (two fresh pools over the same
        // catalog — the multi-node story), while the runtime's own pool stays up.
        var config = new ManifestLoader().load(appHome).config();
        Map<String, HikariDataSource> poolsA = DataSources.createAll(config, null, appHome);
        Map<String, HikariDataSource> poolsB = DataSources.createAll(config, null, appHome);
        try {
            CountDownLatch ready = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            for (HikariDataSource pool : new HikariDataSource[]{
                    poolsA.get("analytics"), poolsB.get("analytics")}) {
                executor.submit(() -> {
                    ready.await();
                    try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
                        s.execute("insert into lake.price_history values"
                                + " ('CONC', 1.0, 1, now())");
                    }
                    return null;
                });
            }
            ready.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
            try (Connection c = poolsA.get("analytics").getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(
                            "select count(*) from lake.price_history where sku = 'CONC'")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(2);
            }
        } finally {
            poolsA.values().forEach(HikariDataSource::close);
            poolsB.values().forEach(HikariDataSource::close);
        }

        // The metadata lives on main in the self-managed schema — and survives a full runtime
        // restart beside Flyway (re-attach, re-migrate, still serving).
        runtime.close();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        assertThat(get("/lakeboard").statusCode()).isEqualTo(200);

        // The expiry leg: retire the old rows, expire the snapshots that carried them, and
        // remove the files nothing references any more — parquet count strictly drops.
        long filesBefore = parquetCount();
        assertThat(runtime.runJob("pricing.pruneNow", Map.of()).status().name())
                .isEqualTo("COMPLETED");
        assertThat(parquetCount()).isLessThan(filesBefore);

        // The fence holds to the end.
        assertThat(get("/api/outside-lake").statusCode()).isEqualTo(500);
    }

    private long parquetCount() throws IOException {
        try (Stream<Path> files = Files.walk(appHome.resolve("data/lake"))) {
            return files.filter(f -> f.toString().endsWith(".parquet")).count();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Path prepareApp() throws Exception {
        Path home = Files.createTempDirectory("tesseraql-inventory-m24");
        Path gallery = Path.of("..", "examples", "inventory-app").toAbsolutePath().normalize();
        copyRecursively(gallery, home);

        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s
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

        // Overlays the gallery app does not carry: the lake dashboard, an aggressive prune
        // variant of the app's weekly job (observable within one test run), and a fence probe.
        Path board = home.resolve("web/lakeboard");
        Files.createDirectories(board);
        Files.writeString(board.resolve("get.yml"), """
                version: tesseraql/v1
                id: lakeboard.read
                kind: route
                recipe: query-json
                # Deliberately open: this fixture tests analytics reads, not authentication,
                # and the gallery config now declares security defaults that would otherwise
                # require a session here.
                security:
                  auth: public
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
        Files.writeString(board.resolve("current.sql"),
                "select sku, best_price from lake.price_history order by loaded_at, sku\n");
        Files.writeString(board.resolve("first-run.sql"),
                "select sku, best_price from lake.price_history at (version => 2) order by sku\n");

        Path prune = home.resolve("batch/pricing");
        Files.writeString(prune.resolve("prune-now.yml"), """
                version: tesseraql/v1
                id: pricing.pruneNow
                kind: job
                recipe: batch-pipeline
                datasource: analytics
                trigger:
                  schedule:
                    cron: "0 0 4 1 1 ? 2099"
                pipeline:
                  - id: retire
                    sql: { file: retire-history.sql, mode: update }
                  - id: expire
                    sql: { file: expire-now.sql, mode: query }
                  - id: cleanup
                    sql: { file: cleanup-files.sql, mode: query }
                """);
        Files.writeString(prune.resolve("retire-history.sql"),
                "delete from lake.price_history where loaded_at < now()\n");
        Files.writeString(prune.resolve("expire-now.sql"),
                "call ducklake_expire_snapshots('lake', older_than => now())\n");

        Path outside = home.resolve("web/api/outside-lake");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("get.yml"), """
                version: tesseraql/v1
                id: outsideLake.read
                kind: route
                recipe: query-json
                # Deliberately open: this fixture tests analytics reads, not authentication,
                # and the gallery config now declares security defaults that would otherwise
                # require a session here.
                security:
                  auth: public
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

    private static void copyRecursively(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path source : files.toList()) {
                Path relative = from.relativize(source);
                if (relative.startsWith("work") || relative.startsWith("tests")) {
                    continue; // the module cache and the app's own sql suite stay behind
                }
                Path target = to.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
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
