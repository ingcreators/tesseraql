package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.operations.batch.JobExecution;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A failure leaves its throwable (docs/audit-medium-leads.md slice 7, F106): a job run and a
 * file import that die log their WARN line with the stack under it — which class failed, and
 * where. The execution row keeps the message alone, so the log line was the only place a stack
 * could exist, and it carried {@code ex.getMessage()} without {@code ex}: a
 * {@code NullPointerException} in a step class recorded {@code null} and nothing else anywhere.
 */
@Testcontainers
class FailureThrowableIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

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
            try (var files = Files.walk(appHome)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void aFailedJobRunLogsItsStack() throws Exception {
        String log = captureStderr(() -> {
            JobExecution execution = runtime.runJob("broken.job", Map.of());
            assertThat(String.valueOf(execution.status())).isEqualTo("FAILED");
        });

        List<String> lines = log.lines().toList();
        int at = indexOf(lines, "Job broken.job execution");
        assertThat(at).as("the WARN line:\n" + log).isNotNegative();
        assertThat(lines.get(at)).contains("failed:");
        // The frames follow the line, and name the executor the step failed inside.
        String below = String.join("\n", lines.subList(at, Math.min(at + 40, lines.size())));
        assertThat(below).contains("\tat io.tesseraql.operations.batch.JobExecutor");
    }

    @Test
    void aFailedFileImportLogsItsStack() throws Exception {
        // An import declared as a workbook, fed text: the codec throws before any row, which is
        // the outer catch — the one that used to log the message alone.
        String log = captureStderr(() -> {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + runtime.port() + "/api/items/import"))
                    .header("Content-Type", "text/csv")
                    .POST(HttpRequest.BodyPublishers.ofString("name,qty\nalpha,1\n",
                            StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(202);
            String transferId = MAPPER.readTree(response.body()).get("transferId").asString();
            JsonNode status = awaitTerminal("/api/items/import/" + transferId);
            assertThat(status.get("status").asString()).isEqualTo("FAILED");
        });

        List<String> lines = log.lines().toList();
        int at = indexOf(lines, "File import ");
        assertThat(at).as("the WARN line:\n" + log).isNotNegative();
        assertThat(lines.get(at)).contains("failed:");
        String below = String.join("\n", lines.subList(at, Math.min(at + 40, lines.size())));
        assertThat(below).contains("\tat io.tesseraql.operations.files.JdbcFileTransferService");
    }

    private static int indexOf(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static JsonNode awaitTerminal(String statusPath) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (true) {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + runtime.port() + statusPath)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode status = MAPPER.readTree(response.body());
            String value = status.get("status").asString();
            if (!"RUNNING".equals(value) && !"STARTED".equals(value)) {
                return status;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Transfer did not finish: " + status);
            }
            Thread.sleep(100);
        }
    }

    private static String captureStderr(ThrowingRunnable body) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            body.run();
            Thread.sleep(300);
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-failure-throwable-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: failure-throwable-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__tables.sql"),
                "create table items (name varchar(100) primary key, qty integer not null);\n");

        Path job = home.resolve("batch/broken");
        Files.createDirectories(job);
        Files.writeString(job.resolve("job.yml"), """
                version: tesseraql/v1
                id: broken.job
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: main
                    sql:
                      file: divide.sql
                      mode: query
                """);
        Files.writeString(job.resolve("divide.sql"), "select 1 / 0 as boom\n");

        Path route = home.resolve("web/api/items/import");
        Files.createDirectories(route);
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.import
                kind: route
                recipe: file-import
                import:
                  format: excel
                  columns: [name, qty]
                  onError: rollback
                steps:
                  - id: row
                    sql:
                      file: insert-item.sql
                """);
        Files.writeString(route.resolve("insert-item.sql"), """
                insert into items (name, qty)
                values ( /* name */ 'sample', cast( /* qty */ '1' as integer) )
                ;
                """);
        return home;
    }
}
