package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.runtime.TesseraqlRuntime;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Row 5 of docs/deployment-maturity.md as an assertion: against a booted runtime whose in-flight
 * bound is four, eight workers see {@code TQL-RATE-4293} refusals and two see none — and the
 * scrape read before and after says the same. The variant that leaves refusals uncounted is red
 * on the first case.
 */
@Testcontainers
class BenchIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            try (Stream<Path> files = Files.walk(appHome)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void eightWorkersAgainstABoundOfFourAreRefusedAndTwoAreNot() throws Exception {
        String url = "http://localhost:" + runtime.port();

        Captured saturated = execute("bench", "--app", appHome.toString(), "--url", url,
                "--route", "nap", "--concurrency", "8", "--duration", "3s", "--format", "json");
        assertThat(saturated.exitCode()).as(saturated.stderr()).isZero();
        JsonNode report = MAPPER.readTree(saturated.stdout());
        assertThat(report.get("refused").get("TQL-RATE-4293").asLong())
                .as("eight workers against maxInFlight 4: %s", saturated.stdout())
                .isGreaterThan(0);
        assertThat(report.get("statuses").get("200").asLong()).isGreaterThan(0);
        // The percentiles are what every caller saw, refusals included: at saturation the
        // instant refusals pull the median down, and the nap shows in the max.
        assertThat(report.get("latencyMillis").get("max").asDouble())
                .as("an answered nap takes its 200 ms").isGreaterThan(150.0);
        assertThat(report.get("scrape").get("refused").get("TQL-RATE-4293").asLong())
                .as("the runtime's own counter moved by the refusals the harness saw")
                .isEqualTo(report.get("refused").get("TQL-RATE-4293").asLong());

        Captured inside = execute("bench", "--app", appHome.toString(), "--url", url,
                "--route", "nap", "--concurrency", "2", "--duration", "2s", "--format", "json",
                "--expect", "refused<=0,errors<=0");
        assertThat(inside.exitCode()).as(inside.stdout() + inside.stderr()).isZero();
        JsonNode quiet = MAPPER.readTree(inside.stdout());
        assertThat(quiet.get("refused").size()).isZero();
        assertThat(quiet.get("latencyMillis").get("p50").asDouble())
                .as("nothing refused: every answer is a 200 ms nap").isGreaterThan(150.0);
        assertThat(quiet.get("expect").get("pass").asBoolean()).isTrue();
    }

    private record Captured(int exitCode, String stdout, String stderr) {
    }

    private static Captured execute(String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            int exitCode = new CommandLine(new TesseraqlCli()).execute(args);
            return new Captured(exitCode, stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-bench-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: bench-it
                  http:
                    workerThreads: 2
                    maxInFlight: 4
                  metrics:
                    enabled: true
                    unauthenticated: true
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path nap = target.resolve("web/api/nap");
        Files.createDirectories(nap);
        Files.writeString(nap.resolve("get.yml"), """
                version: tesseraql/v1
                id: nap
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: nap.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.writeString(nap.resolve("nap.sql"), "select pg_sleep(0.2) as nap\n");
        return target;
    }
}
