package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import org.testcontainers.mysql.MySQLContainer;

/**
 * Phase 27 portability: the messaging channel works on MySQL via the {@code db-poll} transport — the
 * same durable {@code tql_event} queue and {@code publish:}/{@code consume:} YAML, claimed with
 * MySQL's {@code LIMIT … FOR UPDATE SKIP LOCKED} and swept by the backstop poll (no LISTEN/NOTIFY).
 * Proves the broker-free messaging is not PostgreSQL-only.
 */
@Testcontainers
class MySqlMessagingIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static int port;
    static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
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
    void aPublishedEventIsConsumedAndProjectedThroughDbPollOnMySql() throws Exception {
        byte[] body = "{\"orderId\":\"O-1\",\"total\":1250}".getBytes(StandardCharsets.UTF_8);
        assertThat(post(body).statusCode()).isEqualTo(202);

        Integer projected = await(() -> projectedTotal("O-1"));
        assertThat(projected).isEqualTo(1250);
        assertThat(projectionCount()).isEqualTo(1);
    }

    private static HttpResponse<String> post(byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Integer await(SqlSupplier supplier) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
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

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static Integer projectedTotal(String orderId) throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select total from order_projection where id = '" + orderId + "'")) {
            return rs.next() ? rs.getInt("total") : null;
        }
    }

    private static int projectionCount() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select count(*) from order_projection")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("create table orders (id varchar(64) primary key, total int)");
            statement.execute(
                    "create table order_projection (id varchar(64) primary key, total int)");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-mysql-messaging-it");
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
                  outbox:
                    dispatch:
                      fixedDelay: 500ms
                  messaging:
                    backstop: 1s
                    channels:
                      events:
                        transport: db-poll
                """.formatted(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));

        Path orderDir = target.resolve("web/api/orders");
        Files.createDirectories(orderDir);
        Files.writeString(orderDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.create
                kind: route
                recipe: command-json
                # Deliberately open: this fixture tests messaging, not authentication, and the
                # copied gallery config now declares security defaults that would otherwise
                # require a session here.
                security:
                  auth: public
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

        Path consumeDir = target.resolve("consume/orders");
        Files.createDirectories(consumeDir);
        Files.writeString(consumeDir.resolve("project.yml"), """
                version: tesseraql/v1
                id: orders.project
                kind: route
                recipe: queue-consume
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
                        + " values (/* orderId */ 'x', /* total */ 0)\n");
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
