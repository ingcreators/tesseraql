package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The milestone M18 proof (roadmap Phase 53, docs/multi-datasource.md): a command commits on
 * {@code main} and publishes; a {@code queue-consume} route with {@code datasource: reporting}
 * idempotently upserts the projection into a second real PostgreSQL database. The channel, its
 * claim, and the dedup records stay on {@code main} — only the apply transaction moves. A
 * rolled-back command projects nothing, and a republished business key never doubles or reorders
 * the projection.
 */
@Testcontainers
class MultiDatasourceProjectionIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static int port;
    static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        try (Connection connection = connectMain();
                Statement statement = connection.createStatement()) {
            statement.execute("create database reporting");
            statement.execute("create table orders (id varchar(64) primary key,"
                    + " total int check (total >= 0))");
        }
        try (Connection connection = connectReporting();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "create table order_projection (id varchar(64) primary key, total int)");
        }
        appHome = prepareAppHome();
        port = freePort();
        runtime = TesseraqlRuntime.start(appHome, port);
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
    void projectsAcrossDatabasesIdempotentlyAndNeverFromARollback() throws Exception {
        // Happy path: the command commits on main, the projection lands in the reporting database.
        assertThat(post("{\"orderId\":\"O-1\",\"total\":1250}").statusCode()).isEqualTo(202);
        assertThat(await(() -> projectedTotal("O-1"))).isEqualTo(1250);

        // The projection table exists only in the reporting database - the consumer really wrote
        // across the connector, not into main.
        assertThat(tableCountOnMain("order_projection")).isZero();

        // A rolled-back command publishes nothing: the check constraint rejects the write, the
        // outbox event dies with the transaction.
        assertThat(post("{\"orderId\":\"O-3\",\"total\":-5}").statusCode())
                .isGreaterThanOrEqualTo(400);

        // Republishing the same business key is deduplicated on main's consumed-key records: the
        // upsert would overwrite total if the second event were applied, so the surviving value
        // proves the dedup, not just the row count.
        assertThat(post("{\"orderId\":\"O-2\",\"total\":100}").statusCode()).isEqualTo(202);
        assertThat(await(() -> projectedTotal("O-2"))).isEqualTo(100);
        assertThat(postUpsert("{\"orderId\":\"O-2\",\"total\":999}").statusCode()).isEqualTo(202);
        await(() -> consumedEventsForKey("O-2") == 2 ? 1 : null);
        assertThat(projectedTotal("O-2")).isEqualTo(100);
        assertThat(projectionCount()).isEqualTo(2);
        assertThat(projectedTotal("O-3")).isNull();
    }

    private static HttpResponse<String> post(String body) throws Exception {
        return send("/api/orders", body);
    }

    private static HttpResponse<String> postUpsert(String body) throws Exception {
        return send("/api/orders/upsert", body);
    }

    private static HttpResponse<String> send(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        body.getBytes(StandardCharsets.UTF_8)))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Polls until the supplier returns non-null or a generous timeout lapses (async delivery). */
    private static Integer await(SqlSupplier supplier) throws Exception {
        Duration timeout = Duration.ofSeconds(30);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Integer value = supplier.get();
            if (value != null) {
                return value;
            }
            Thread.sleep(200);
        }
        return supplier.get();
    }

    @FunctionalInterface
    private interface SqlSupplier {
        Integer get() throws Exception;
    }

    private static Connection connectMain() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection connectReporting() throws Exception {
        return DriverManager.getConnection(
                reportingUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String reportingUrl() {
        return POSTGRES.getJdbcUrl()
                .replace("/" + POSTGRES.getDatabaseName(), "/reporting");
    }

    private static Integer projectedTotal(String orderId) throws Exception {
        try (Connection connection = connectReporting();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select total from order_projection where id = '" + orderId + "'")) {
            return rs.next() ? rs.getInt("total") : null;
        }
    }

    private static int projectionCount() throws Exception {
        try (Connection connection = connectReporting();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select count(*) from order_projection")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int tableCountOnMain(String table) throws Exception {
        try (Connection connection = connectMain();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select count(*) from information_schema.tables where table_name = '"
                                + table + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Both O-2 events acknowledged on main's durable log — the dedup verdict is final. */
    private static int consumedEventsForKey(String key) throws Exception {
        try (Connection connection = connectMain();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select count(*) from tql_event where msg_key = '" + key
                                + "' and consumed_at is not null")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-multi-ds-projection");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %1$s
                    username: %2$s
                    password: %3$s
                  reporting:
                    url: %4$s

                tesseraql:
                  app:
                    name: projection-app
                  datasources:
                    main:
                      jdbcUrl: ${db.main.url}
                      username: ${db.main.username}
                      password: ${db.main.password}
                    reporting:
                      jdbcUrl: ${db.reporting.url}
                      username: ${db.main.username}
                      password: ${db.main.password}
                  outbox:
                    dispatch:
                      fixedDelay: 500ms
                  messaging:
                    backstop: 1s
                    channels:
                      events:
                        transport: pg-notify
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), reportingUrl()));

        // The producer: a command writes the order on main and publishes on the same outbox.
        Path orderDir = target.resolve("web/api/orders");
        Files.createDirectories(orderDir);
        Files.writeString(orderDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.create
                kind: route
                recipe: command-json
                input:
                  orderId:
                    type: string
                    required: true
                  total:
                    type: number
                sql:
                  file: insert-order.sql
                  mode: update
                  params:
                    orderId: body.orderId
                    total: body.total
                publish:
                  channel: events
                  topic: orders.created
                  key: body.orderId
                  payload:
                    orderId: body.orderId
                    total: body.total
                response:
                  json:
                    status: 202
                """);
        Files.writeString(orderDir.resolve("insert-order.sql"),
                "insert into orders (id, total) values (/* orderId */ 'x', /* total */ 0)\n");

        // A second producer upserting the same business key, so one key can publish twice.
        Path upsertDir = target.resolve("web/api/orders/upsert");
        Files.createDirectories(upsertDir);
        Files.writeString(upsertDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.upsert
                kind: route
                recipe: command-json
                input:
                  orderId:
                    type: string
                    required: true
                  total:
                    type: number
                sql:
                  file: upsert-order.sql
                  mode: update
                  params:
                    orderId: body.orderId
                    total: body.total
                publish:
                  channel: events
                  topic: orders.created
                  key: body.orderId
                  payload:
                    orderId: body.orderId
                    total: body.total
                response:
                  json:
                    status: 202
                """);
        Files.writeString(upsertDir.resolve("upsert-order.sql"),
                "insert into orders (id, total) values (/* orderId */ 'x', /* total */ 0)"
                        + " on conflict (id) do update set total = excluded.total\n");

        // The consumer: the apply transaction runs on the reporting connector (Phase 53).
        Path consumeDir = target.resolve("consume/orders");
        Files.createDirectories(consumeDir);
        Files.writeString(consumeDir.resolve("project.yml"), """
                version: tesseraql/v1
                id: orders.project
                kind: route
                recipe: queue-consume
                datasource: reporting
                consume:
                  channel: events
                  topic: orders.created
                  idempotencyKey: body.orderId
                input:
                  orderId:
                    type: string
                    required: true
                  total:
                    type: number
                sql:
                  file: project-order.sql
                  mode: update
                  params:
                    orderId: body.orderId
                    total: body.total
                """);
        Files.writeString(consumeDir.resolve("project-order.sql"),
                "insert into order_projection (id, total)"
                        + " values (/* orderId */ 'x', /* total */ 0)"
                        + " on conflict (id) do update set total = excluded.total\n");
        return target;
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
