package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for the route concurrency limit (design ch. 36.1): with maxInFlight=1, only one
 * request runs at a time and concurrent requests are rejected with 429.
 */
@Testcontainers
class ConcurrencyLimitIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
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
    void concurrentRequestsBeyondLimitAreRejected() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                calls.add(() -> get("/api/slow").statusCode());
            }
            List<Future<Integer>> futures = pool.invokeAll(calls);
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            assertThat(statuses).contains(200).contains(429);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void requestsBeyondRateLimitAreRejected() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(() -> get("/api/rated").statusCode());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : pool.invokeAll(calls)) {
                statuses.add(future.get());
            }
            assertThat(statuses).contains(200).contains(429);
        } finally {
            pool.shutdownNow();
        }
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-rate-it");
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

        // A public, deliberately slow route limited to one in-flight request.
        Path slowDir = target.resolve("web/api/slow");
        Files.createDirectories(slowDir);
        Files.writeString(slowDir.resolve("get.yml"),
                """
                        version: tesseraql/v1
                        id: slow.query
                        kind: route
                        recipe: query-json
                        # Deliberately open: this fixture tests concurrency limits, not authentication, and the
                        # copied gallery config now declares security defaults that would otherwise
                        # require a session here.
                        security:
                          auth: public
                        policy:
                          concurrency:
                            maxInFlight: 1
                        sql:
                          file: slow.sql
                          mode: query
                        response:
                          json:
                            status: 200
                            body:
                              ok: sql.rowCount
                        """);
        Files.writeString(slowDir.resolve("slow.sql"), "select pg_sleep(0.5) as slept\n");

        // A public route rate-limited to one request per second (bucket capacity 1).
        Path ratedDir = target.resolve("web/api/rated");
        Files.createDirectories(ratedDir);
        Files.writeString(ratedDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: rated.query
                kind: route
                recipe: query-json
                # Deliberately open: this fixture tests rate limits, not authentication, and the
                # copied gallery config now declares security defaults that would otherwise
                # require a session here.
                security:
                  auth: public
                policy:
                  rateLimit:
                    requestsPerSecond: 1
                    burst: 1
                sql:
                  file: rated.sql
                  mode: query
                response:
                  json:
                    status: 200
                    body:
                      ok: sql.rowCount
                """);
        Files.writeString(ratedDir.resolve("rated.sql"), "select 1 as ok\n");
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
