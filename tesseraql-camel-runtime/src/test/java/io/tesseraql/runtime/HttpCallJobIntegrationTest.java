package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 26 end-to-end: a batch-pipeline job whose {@code http-call} step fetches JSON from an
 * (allow-listed) endpoint, and whose next SQL step persists the parsed response — proving the
 * response flows into the step context ({@code step.fetch.body.rate}) and the egress is governed
 * by {@code tesseraql.http.outbound}.
 */
@Testcontainers
class HttpCallJobIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static HttpServer endpoint;
    static int endpointPort;
    static final AtomicInteger calls = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        startEndpoint();
        seedDatabase();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (endpoint != null) {
            endpoint.stop(0);
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void theFetchedRateIsPersistedByASubsequentSqlStep() throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        Integer rate = null;
        Integer status = null;
        while (System.currentTimeMillis() < deadline && rate == null) {
            try (Connection connection = connect();
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery(
                            "select status, rate from rate_log order by id limit 1")) {
                if (rs.next()) {
                    status = rs.getInt("status");
                    rate = rs.getInt("rate");
                }
            }
            if (rate == null) {
                Thread.sleep(250);
            }
        }

        assertThat(calls.get()).as("the http-call step should have reached the endpoint")
                .isPositive();
        assertThat(status).isEqualTo(200);
        assertThat(rate).isEqualTo(142);
        assertThat(runtime.jobRepository().listExecutions(50))
                .anyMatch(execution -> "ratesync".equals(execution.jobId()));
    }

    private static void startEndpoint() throws IOException {
        endpoint = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        endpoint.createContext("/rates", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"rate\": 142}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        endpoint.start();
        endpointPort = endpoint.getAddress().getPort();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement
                    .execute("create table rate_log (id serial primary key, status int, rate int)");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-httpcall-it");
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
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        // The deny-by-default egress allow-list goes in the highest-precedence overlay so it
        // replaces the example app's own allowedHosts: localhost (the test endpoint) is the only
        // reachable host for this run.
        Files.writeString(target.resolve("config/overlay.yml"), """
                tesseraql:
                  http:
                    outbound:
                      allowedHosts:
                        - localhost
                """);

        Path jobDir = target.resolve("batch/ratesync");
        Files.createDirectories(jobDir);
        Files.writeString(jobDir.resolve("job.yml"), """
                version: tesseraql/v1
                id: ratesync
                kind: job
                recipe: batch-pipeline
                trigger:
                  schedule:
                    fixedDelay: 1s
                pipeline:
                  - id: fetch
                    http-call:
                      method: GET
                      url: http://localhost:%d/rates
                  - id: record
                    sql:
                      file: record.sql
                      mode: update
                      params:
                        status: step.fetch.status
                        rate: step.fetch.body.rate
                """.formatted(endpointPort));
        Files.writeString(jobDir.resolve("record.sql"),
                "insert into rate_log (status, rate) values (/* status */ 200, /* rate */ 0)\n");
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
