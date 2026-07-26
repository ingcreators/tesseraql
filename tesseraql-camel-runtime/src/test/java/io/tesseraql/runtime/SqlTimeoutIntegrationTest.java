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
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The default SQL statement timeout (roadmap Phase 45): a runaway query is cancelled by the
 * driver — bounded BY DEFAULT via the app-wide {@code tesseraql.sql.timeoutSeconds}, overridden
 * per binding, and disabled only by an explicit {@code timeoutSeconds: 0}.
 */
@Testcontainers
class SqlTimeoutIntegrationTest {

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
            try (Stream<Path> files = Files.walk(appHome)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void aRunawayQueryIsCancelledByTheAppWideDefault() throws Exception {
        // The app default is 1s; the query sleeps 10s — the driver cancels well before that.
        long started = System.nanoTime();
        HttpResponse<String> response = get("/api/slow");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(elapsedMs).isLessThan(8_000);
    }

    /**
     * A batch step reads the same app-wide bound.
     *
     * <p>It read nothing: `JobExecutor` prepared its statement and never set a query timeout, so
     * a step's SQL ran for as long as the driver allowed while holding a pooled connection. The
     * route and command paths have been bounded by {@code tesseraql.sql.timeoutSeconds} since it
     * was introduced.
     */
    @Test
    void aRunawayBatchStepIsCancelledByTheSameDefault() {
        long startedAt = System.currentTimeMillis();

        // A job records its failure rather than throwing, so the status is the observable.
        var execution = runtime.runJob("slow.job", java.util.Map.of());
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(String.valueOf(execution.status())).isEqualTo("FAILED");
        // The 1s app-wide default, not the statement's 10s sleep.
        assertThat(elapsed).isLessThan(8_000);
    }

    /**
     * A batch step runs the dialect variant beside its file.
     *
     * <p>It ran the generic one. This executor resolved the SQL path itself and never asked the
     * datasource its vendor, so an {@code x.postgresql.sql} sitting next to {@code x.sql} was
     * never opened — silently, which is the failure a dialect variant exists to prevent.
     */
    @Test
    void aBatchStepRunsTheDialectVariant() {
        assertThat(String.valueOf(runtime.runJob("variant.job", java.util.Map.of()).status()))
                .isEqualTo("COMPLETED");
    }

    @Test
    void anExplicitZeroOptsALongRunningStatementOut() throws Exception {
        // timeoutSeconds: 0 disables the guard; a 2s sleep outlives the 1s app default.
        HttpResponse<String> response = get("/api/patient");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("done");
    }

    @Test
    void aRunawayCommandStepIsCancelledByTheSameDefault() throws Exception {
        long start = System.currentTimeMillis();
        HttpResponse<String> response = post("/api/slow-command");
        long elapsedMs = System.currentTimeMillis() - start;

        // Without the bound this step would hold the command's transaction — and its pool
        // connection — for the full ten seconds.
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(elapsedMs).isLessThan(8_000);
    }

    @Test
    void aCommandStepThatOverMaterializesIsRefused() throws Exception {
        HttpResponse<String> response = post("/api/big-command");

        // 50,000 rows against a 100-row cap: the same TQL-LD-0001 the read path raises, rather
        // than materializing the lot inside an open write transaction.
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("TQL-LD-0001");
    }

    private static HttpResponse<String> post(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-sql-timeout-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: timeout-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  sql:
                    timeoutSeconds: 1
                  resultMaterialization:
                    maxRows: 100
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        Path slow = target.resolve("web/api/slow");
        Files.createDirectories(slow);
        Files.writeString(slow.resolve("get.yml"), """
                version: tesseraql/v1
                id: slow
                kind: route
                recipe: query-json
                security:
                  auth: public
                sql:
                  file: slow.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(slow.resolve("slow.sql"), "select pg_sleep(10) as nap\n");

        // The batch path: a job step ran with whatever the driver defaults to — usually forever —
        // holding a pooled connection, where the same statement on a route has been bounded all
        // along. A job is where a runaway statement goes unnoticed longest: nobody is waiting.
        Path slowJob = target.resolve("batch/slow");
        Files.createDirectories(slowJob);
        Files.writeString(slowJob.resolve("job.yml"), """
                version: tesseraql/v1
                id: slow.job
                kind: job
                recipe: batch-tasklet
                sql:
                  file: nap.sql
                  mode: query
                """);
        Files.writeString(slowJob.resolve("nap.sql"), "select pg_sleep(10) as nap\n");

        // A job whose SQL has a PostgreSQL variant beside it: the batch executor resolved the
        // path itself and never asked the datasource, so the generic file ran and the variant sat
        // there unread.
        Path variantJob = target.resolve("batch/variant");
        Files.createDirectories(variantJob);
        Files.writeString(variantJob.resolve("job.yml"), """
                version: tesseraql/v1
                id: variant.job
                kind: job
                recipe: batch-tasklet
                sql:
                  file: pick.sql
                  mode: query
                """);
        // The generic file references a table that does not exist; the variant is valid. Which
        // one ran is therefore the job's outcome, not something the test has to introspect.
        Files.writeString(variantJob.resolve("pick.sql"),
                "select this_column_does_not_exist from nowhere\n");
        Files.writeString(variantJob.resolve("pick.postgresql.sql"), "select 1 as ok\n");

        // The command path: a step opens its own transaction with no transaction manager to
        // bound it, so it has to read the same timeout the route path does.
        Path slowCommand = target.resolve("web/api/slow-command");
        Files.createDirectories(slowCommand);
        Files.writeString(slowCommand.resolve("post.yml"), """
                version: tesseraql/v1
                id: slow.command
                kind: route
                recipe: command-json
                security:
                  auth: public
                steps:
                  nap:
                    file: nap.sql
                    mode: query
                response:
                  json:
                    status: 200
                    body:
                      ok: "true"
                """);
        Files.writeString(slowCommand.resolve("nap.sql"), "select pg_sleep(10) as nap\n");

        Path bigCommand = target.resolve("web/api/big-command");
        Files.createDirectories(bigCommand);
        Files.writeString(bigCommand.resolve("post.yml"), """
                version: tesseraql/v1
                id: big.command
                kind: route
                recipe: command-json
                security:
                  auth: public
                steps:
                  rows:
                    file: many.sql
                    mode: query
                response:
                  json:
                    status: 200
                    body:
                      ok: "true"
                """);
        Files.writeString(bigCommand.resolve("many.sql"),
                "select g from generate_series(1, 50000) as g\n");

        Path patient = target.resolve("web/api/patient");
        Files.createDirectories(patient);
        Files.writeString(patient.resolve("get.yml"), """
                version: tesseraql/v1
                id: patient
                kind: route
                recipe: query-json
                security:
                  auth: public
                sql:
                  file: patient.sql
                  mode: query
                  timeoutSeconds: 0
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(patient.resolve("patient.sql"),
                "select 'done' as answer from pg_sleep(2)\n");
        return target;
    }
}
