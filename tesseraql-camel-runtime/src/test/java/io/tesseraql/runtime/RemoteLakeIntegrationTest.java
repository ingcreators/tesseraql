package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
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
 * Integration test for remote lakes (docs/duckdb.md "Remote data paths"): the lake's Parquet data
 * on an S3-compatible store (Adobe S3Mock, path-style endpoint), metadata still on {@code main} —
 * writes land as objects under the declared prefix from separate engine instances, time travel
 * works, retire + expire + cleanup strictly reduces the object count, and the prefix-scoped
 * secret refuses an out-of-scope bucket.
 */
@Testcontainers
class RemoteLakeIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final S3MockContainer S3MOCK = new S3MockContainer("4.12.4")
            .withInitialBuckets("lake,other");

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
    void writesTheLakeOnObjectStorageAndScopesTheCredentials() throws Exception {
        appHome = prepareApp();

        runtime = TesseraqlRuntime.start(appHome, freePort());

        // Two job runs land history snapshots as objects under the declared prefix.
        for (int run = 0; run < 2; run++) {
            assertThat(runtime.runJob("pricing.appendHistory", Map.of()).status().name())
                    .isEqualTo("COMPLETED");
        }
        HttpResponse<String> board = get("/lakeboard");
        assertThat(board.statusCode()).isEqualTo(200);
        var body = MAPPER.readTree(board.body());
        assertThat(body.get("current").size()).isEqualTo(4);
        assertThat(body.get("firstRun").size()).isEqualTo(2);
        assertThat(storedObjects()).isGreaterThanOrEqualTo(2);

        // Concurrent writers on two separate engine instances, objects on the same store.
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
                                + " ('CONC', 1.0, now())");
                    }
                    return null;
                });
            }
            ready.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
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

        // The ad-hoc ${remote.*} read: a Parquet object PUT into the declared prefix is
        // queryable through the placeholder, under its own scoped secret.
        HttpResponse<String> adhoc = get("/api/drop");
        assertThat(adhoc.statusCode()).isEqualTo(200);
        assertThat(adhoc.body()).contains("widgets");

        // The prefix-scoped secret answers only for the declared prefix.
        assertThat(get("/api/outofscope").statusCode()).isEqualTo(500);

        // Retire + expire + cleanup strictly reduces the object count under the prefix.
        long objectsBefore = storedObjects();
        assertThat(runtime.runJob("pricing.pruneNow", Map.of()).status().name())
                .isEqualTo("COMPLETED");
        assertThat(storedObjects()).isLessThan(objectsBefore);
    }

    private static void assertThatPut(int status) {
        assertThat(status).isBetween(200, 299);
    }

    /** Objects under the lake prefix, counted straight off the store's list API. */
    private static long storedObjects() throws Exception {
        HttpResponse<String> listing = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(S3MOCK.getHttpEndpoint()
                        + "/lake?list-type=2&prefix=history/")).build(),
                HttpResponse.BodyHandlers.ofString());
        return java.util.regex.Pattern.compile("<Key>").matcher(listing.body()).results().count();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Path prepareApp() throws Exception {
        Path home = Files.createTempDirectory("tesseraql-remote-lake");
        String endpoint = S3MOCK.getHttpEndpoint().replaceFirst("^https?://", "");
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
                    name: remote-lake-app
                  datasources:
                    main:
                      jdbcUrl: ${db.main.url}
                      username: ${db.main.username}
                      password: ${db.main.password}
                    analytics:
                      jdbcUrl: "jdbc:duckdb:"
                      duckdb:
                        extensions: [ducklake, postgres, httpfs]
                        lake:
                          catalog: main
                          data: s3://lake/history/
                          region: us-east-1
                          endpoint: %s
                          urlStyle: path
                          useSsl: false
                          credentials:
                            keyId: test-key
                            secret: test-secret
                          mode: readwrite
                        remotes:
                          drops:
                            url: s3://lake/drops/
                            region: us-east-1
                            endpoint: %s
                            urlStyle: path
                            useSsl: false
                            credentials:
                              keyId: test-key
                              secret: test-secret
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), endpoint, endpoint));

        Path cache = home.resolve("work/duckdb-extensions");
        Files.createDirectories(cache);
        Properties props = new Properties();
        props.setProperty("extension_directory", cache.toString());
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:", props);
                Statement statement = duck.createStatement()) {
            statement.execute("INSTALL ducklake");
            statement.execute("INSTALL postgres");
            statement.execute("INSTALL httpfs");
        }

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
                select * from (values ('W-1', 12.5, now()), ('G-7', 7.25, now()))
                """);
        Files.writeString(job.resolve("prune-now.yml"), """
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
        Files.writeString(job.resolve("retire-history.sql"),
                "delete from lake.price_history where loaded_at < now()\n");
        Files.writeString(job.resolve("expire-now.sql"),
                "call ducklake_expire_snapshots('lake', older_than => now())\n");
        Files.writeString(job.resolve("cleanup-files.sql"),
                "call ducklake_cleanup_old_files('lake', cleanup_all => true)\n");

        Path board = home.resolve("web/lakeboard");
        Files.createDirectories(board);
        Files.writeString(board.resolve("get.yml"), """
                version: tesseraql/v1
                id: lakeboard.read
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
        Files.writeString(board.resolve("current.sql"),
                "select sku, best_price from lake.price_history order by loaded_at, sku\n");
        Files.writeString(board.resolve("first-run.sql"),
                "select sku, best_price from lake.price_history at (version => 2) order by sku\n");

        // An ad-hoc drop the ${remote.*} channel reads back: a Parquet object PUT straight
        // into the store (no engine involved on the write side).
        Path drop = Files.createTempFile("drop", ".parquet");
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = duck.createStatement()) {
            Files.delete(drop);
            statement.execute("""
                    COPY (SELECT * FROM (VALUES ('widgets', 300), ('gadgets', 120))
                          AS t(category, total))
                    TO '%s' (FORMAT parquet)
                    """.formatted(drop));
        }
        HttpResponse<String> put = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(S3MOCK.getHttpEndpoint()
                        + "/lake/drops/monthly.parquet"))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(Files.readAllBytes(drop)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThatPut(put.statusCode());
        Files.delete(drop);

        Path adhoc = home.resolve("web/api/drop");
        Files.createDirectories(adhoc);
        Files.writeString(adhoc.resolve("get.yml"), """
                version: tesseraql/v1
                id: drop.read
                kind: route
                recipe: query-json
                datasource: analytics
                sql:
                  file: drop.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(adhoc.resolve("drop.sql"), """
                select category, total
                from read_parquet(/* ${remote.drops}/monthly.parquet */ 'dummy.parquet')
                order by total desc
                """);

        Path outOfScope = home.resolve("web/api/outofscope");
        Files.createDirectories(outOfScope);
        Files.writeString(outOfScope.resolve("get.yml"), """
                version: tesseraql/v1
                id: outofscope.read
                kind: route
                recipe: query-json
                datasource: analytics
                sql:
                  file: outofscope.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(outOfScope.resolve("outofscope.sql"),
                "select count(*) as n from glob('s3://other/**')\n");
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
