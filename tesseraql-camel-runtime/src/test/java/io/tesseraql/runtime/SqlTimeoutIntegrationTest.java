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
     * A batch step's own {@code timeoutSeconds:} raises the bound it runs under.
     *
     * <p>It did not. The declaration parsed, the schema documented it as a "per-binding SQL
     * statement timeout override", and {@code JobExecutor} then built every step's bounds from
     * the app-wide value alone — so a nightly extract that legitimately takes minutes could only
     * be given room by loosening the bound every request in the application ran under.
     */
    @Test
    void aBatchStepRaisesTheBoundItDeclares() {
        // 5s declared against the 1s app-wide default; the statement sleeps 2s. Before the fix
        // the app default cancelled it at 1s and the job reported FAILED.
        var execution = runtime.runJob("patient.job", java.util.Map.of());

        // The reason travels with the status: a bound that is ignored and a fixture
        // that is wrong both report FAILED, and only one of them is this test's subject.
        assertThat(String.valueOf(execution.status()))
                .as("exit: %s", execution.exitMessage())
                .isEqualTo("COMPLETED");
    }

    /** A chunk reader is a binding of its own, and carries its own bound. */
    @Test
    void aChunkReaderRaisesTheBoundItDeclares() {
        var execution = runtime.runJob("patient.chunk", java.util.Map.of());

        // The reason travels with the status: a bound that is ignored and a fixture
        // that is wrong both report FAILED, and only one of them is this test's subject.
        assertThat(String.valueOf(execution.status()))
                .as("exit: %s", execution.exitMessage())
                .isEqualTo("COMPLETED");
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

    /**
     * A row-scope directive in batch SQL is refused, not quietly ignored.
     *
     * <p>Matrix 2 of docs/route-governance-parity.md records this cell as absent, which reads
     * like a hole. It is not: the executor passes {@code ScopeResolver.UNSUPPORTED}, so the
     * statement fails with {@code TQL-SQL-2106} rather than rendering unscoped and returning
     * every tenant's rows. Pinned because "absent" and "fail-safe" look the same in a matrix and
     * only one of them is safe to leave alone.
     *
     * <p>Making it work is a design question, not a wiring one: a batch job runs with no caller,
     * so there is no principal for a scope to narrow by.
     */
    @Test
    void aScopeDirectiveInBatchSqlIsRefused() {
        var execution = runtime.runJob("scoped.job", java.util.Map.of());

        assertThat(String.valueOf(execution.status())).isEqualTo("FAILED");
        // The reason matters as much as the status: three earlier drafts of this test also
        // reported FAILED, twice for a malformed directive of my own and once for the wrong SQL
        // mode. A failure is evidence only once it is the failure you meant.
        assertThat(String.valueOf(execution.exitMessage())).contains("TQL-SQL-2106");
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
                sources:
                  main:
                    sql:
                      file: slow.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
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
                recipe: batch-pipeline
                pipeline:
                  - id: main
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
                recipe: batch-pipeline
                pipeline:
                  - id: main
                    sql:
                      file: pick.sql
                      mode: query
                """);
        // The generic file references a table that does not exist; the variant is valid. Which
        // one ran is therefore the job's outcome, not something the test has to introspect.
        Files.writeString(variantJob.resolve("pick.sql"),
                "select this_column_does_not_exist from nowhere\n");
        Files.writeString(variantJob.resolve("pick.postgresql.sql"), "select 1 as ok\n");

        // A job whose SQL carries a row-scope directive. A batch job runs with no caller, so
        // there is no principal for a scope to narrow by; what matters is that this is refused
        // rather than quietly rendered unscoped.
        Path scopedJob = target.resolve("batch/scoped");
        Files.createDirectories(scopedJob);
        Files.writeString(scopedJob.resolve("job.yml"), """
                version: tesseraql/v1
                id: scoped.job
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: main
                    sql:
                      file: scoped.sql
                      mode: query
                """);
        Files.writeString(scopedJob.resolve("scoped.sql"),
                "select 1 as ok from (select 1) t where /*%scope orders on t */ (1=1)\n");

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
                  - id: nap
                    sql:
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
                  - id: rows
                    sql:
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
                sources:
                  main:
                    sql:
                      file: patient.sql
                      mode: query
                      timeoutSeconds: 0
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.writeString(patient.resolve("patient.sql"),
                "select 'done' as answer from pg_sleep(2)\n");

        // The batch counterparts of the route above: a step, and a chunk reader, each declaring
        // more room than the app-wide 1s. Both statements sleep 2s, so either bound being
        // ignored shows up as a FAILED execution rather than a slow one.
        Path patientJob = target.resolve("batch/patient");
        Files.createDirectories(patientJob);
        Files.writeString(patientJob.resolve("job.yml"), """
                version: tesseraql/v1
                id: patient.job
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: main
                    sql:
                      file: nap.sql
                      mode: query
                      timeoutSeconds: 5
                """);
        Files.writeString(patientJob.resolve("nap.sql"),
                "select 'done' as answer from pg_sleep(2)\n");

        Path patientChunk = target.resolve("batch/patient-chunk");
        Files.createDirectories(patientChunk);
        Files.writeString(patientChunk.resolve("job.yml"), """
                version: tesseraql/v1
                id: patient.chunk
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: load
                    chunk:
                      reader:
                        sql:
                          file: reader.sql
                          timeoutSeconds: 5
                      writer:
                        sql:
                          file: writer.sql
                      key: item_key
                      commitEvery: 5
                """);
        // One sleep for the whole cursor, not one per row: the nap is its own single-row source
        // joined to the keys, so the 2s is the statement's, not the keyset's.
        Files.writeString(patientChunk.resolve("reader.sql"),
                "select k.item_key from (select generate_series(1, 10) as item_key) k,"
                        + " (select pg_sleep(2)) nap order by k.item_key\n");
        Files.writeString(patientChunk.resolve("writer.sql"),
                "insert into chunk_target (item_key) values (/* row.item_key */0)\n");
        Files.createDirectories(target.resolve("db/migration"));
        Files.writeString(target.resolve("db/migration/V1__chunk_target.sql"),
                "create table chunk_target (item_key integer primary key);\n");

        return target;
    }
}
