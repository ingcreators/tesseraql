package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for milestone M3 scheduling: a job with a {@code fixedDelay} trigger runs
 * automatically and records executions (design ch. 6.5, 26).
 */
@Testcontainers
class SchedulingIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        seedDatabase();
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
    void fixedDelayJobRunsAutomatically() throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
        boolean ran = false;
        while (System.currentTimeMillis() < deadline) {
            ran = runtime.jobRepository().listExecutions(50).stream()
                    .anyMatch(execution -> "ping".equals(execution.jobId()));
            if (ran) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(ran).as("scheduled job 'ping' should have run").isTrue();
    }

    @Test
    void cronJobRunsAutomatically() throws InterruptedException {
        long deadline = System.currentTimeMillis() + java.time.Duration.ofSeconds(12).toMillis();
        boolean ran = false;
        while (System.currentTimeMillis() < deadline) {
            ran = runtime.jobRepository().listExecutions(200).stream()
                    .anyMatch(execution -> "cronping".equals(execution.jobId()));
            if (ran) {
                break;
            }
            Thread.sleep(250);
        }
        assertThat(ran).as("cron-scheduled job 'cronping' should have run").isTrue();
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("truncate table users restart identity");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-sched-it");
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

        // A fast-firing, side-effect-free scheduled job added only for this test.
        Path pingDir = target.resolve("batch/ping");
        Files.createDirectories(pingDir);
        Files.writeString(pingDir.resolve("job.yml"), """
                version: tesseraql/v1
                id: ping
                kind: job
                recipe: batch-pipeline
                trigger:
                  schedule:
                    fixedDelay: 1s
                pipeline:
                  - id: ping
                    sql:
                      file: ping.sql
                      mode: query
                """);
        Files.writeString(pingDir.resolve("ping.sql"), "select 1\n");

        // A cron-scheduled job that fires every second (quartz 6-field cron).
        Path cronDir = target.resolve("batch/cronping");
        Files.createDirectories(cronDir);
        Files.writeString(cronDir.resolve("job.yml"), """
                version: tesseraql/v1
                id: cronping
                kind: job
                recipe: batch-pipeline
                trigger:
                  schedule:
                    cron: "0/1 * * * * ?"
                pipeline:
                  - id: ping
                    sql:
                      file: ping.sql
                      mode: query
                """);
        Files.writeString(cronDir.resolve("ping.sql"), "select 1\n");
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
