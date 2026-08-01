package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobStatus;
import io.tesseraql.operations.batch.StepExecution;
import io.tesseraql.operations.batch.StepStatus;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for milestone M3: a batch job runs its pipeline, mutates the database, and
 * records job/step executions in the repository (design ch. 6.5, 26).
 */
@Testcontainers
class BatchJobIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    void runsBatchJobRecordsExecutionAndMutatesData() {
        // Reset to PENDING so the assertion is independent of other tests that run the same job.
        setStatus("pending-user", "PENDING");
        JobExecution execution = runtime.runJob("user.dailyMaintenance",
                Map.of("businessDate", "2026-06-08"));

        assertThat(execution.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(execution.jobId()).isEqualTo("user.dailyMaintenance");
        assertThat(execution.endTime()).isNotNull();

        List<StepExecution> steps = runtime.jobRepository().findSteps(execution.id());
        // Phase 20: the job's pipeline also reports the run through a notify step.
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).stepId()).isEqualTo("deactivatePending");
        assertThat(steps.get(0).affectedRows()).isEqualTo(1);
        assertThat(steps.get(1).stepId()).isEqualTo("report");
        assertThat(steps.get(1).affectedRows()).isEqualTo(1); // one notification enqueued

        assertThat(statusOf("pending-user")).isEqualTo("INACTIVE");
        assertThat(runtime.jobRepository().listExecutions(10)).isNotEmpty();
    }

    @Test
    void querySpoolStepStreamsRowsToTempStore() throws Exception {
        JobExecution execution = runtime.runJob("user.exportActive", Map.of());

        assertThat(execution.status()).isEqualTo(JobStatus.COMPLETED);
        StepExecution step = runtime.jobRepository().findSteps(execution.id()).get(0);
        assertThat(step.stepId()).isEqualTo("extract");
        assertThat(step.affectedRows()).isEqualTo(2); // sato + pending-user

        // The rows were spooled to a JSONL file on disk rather than materialized in memory.
        Path spoolDir = appHome.resolve("work/tmp/tesseraql");
        try (Stream<Path> files = Files.walk(spoolDir)) {
            long jsonlLines = files.filter(p -> p.toString().endsWith(".jsonl"))
                    .flatMap(BatchJobIntegrationTest::lines)
                    .filter(line -> !line.isBlank())
                    .count();
            assertThat(jsonlLines).isGreaterThanOrEqualTo(2);
        }
    }

    private static Stream<String> lines(Path file) {
        try {
            return Files.readAllLines(file).stream();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    void operationsApiRunsJobAndListsExecutions() throws Exception {
        String token = token(List.of("BATCH_OPERATOR"));

        HttpResponse<String> run = send("POST",
                "/_tesseraql/ops/batch/jobs/user.dailyMaintenance/run", token, "{}");
        assertThat(run.statusCode()).isEqualTo(200);
        JsonNode runBody = MAPPER.readTree(run.body());
        assertThat(runBody.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(runBody.path("executionId").asText()).isNotBlank();

        HttpResponse<String> list = send("GET", "/_tesseraql/ops/batch/executions", token, null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(list.body()).isArray()).isTrue();
        assertThat(MAPPER.readTree(list.body())).isNotEmpty();
    }

    @Test
    void theBusinessDateDefaultsIsOverridableAndRefusesGarbage() throws Exception {
        String token = token(List.of("BATCH_OPERATOR"));

        // Defaulted: the firing's local date, recorded on the execution.
        HttpResponse<String> defaulted = send("POST",
                "/_tesseraql/ops/batch/jobs/user.dailyMaintenance/run", token, "{}");
        String executionId = MAPPER.readTree(defaulted.body()).path("executionId").asText();
        JsonNode recorded = MAPPER.readTree(send("GET",
                "/_tesseraql/ops/batch/executions/" + executionId, token, null).body());
        assertThat(recorded.path("businessDate").asText())
                .isEqualTo(java.time.LocalDate.now().toString());

        // Overridden: the reserved businessDate parameter — running the 31st's close later.
        HttpResponse<String> overridden = send("POST",
                "/_tesseraql/ops/batch/jobs/user.dailyMaintenance/run", token,
                "{\"businessDate\": \"2026-07-31\"}");
        String overriddenId = MAPPER.readTree(overridden.body()).path("executionId").asText();
        JsonNode overriddenRun = MAPPER.readTree(send("GET",
                "/_tesseraql/ops/batch/executions/" + overriddenId, token, null).body());
        assertThat(overriddenRun.path("businessDate").asText()).isEqualTo("2026-07-31");

        // The ambient bind reaches step SQL: the stamped row carries the date the run
        // was for, not the date it ran on.
        HttpResponse<String> stamped = send("POST",
                "/_tesseraql/ops/batch/jobs/user.stampBusinessDate/run", token,
                "{\"businessDate\": \"2026-07-31\"}");
        assertThat(stamped.body()).contains("COMPLETED");
        assertThat(statusOf("pending-user")).isEqualTo("ASOF-2026-07-31");

        // Garbage refuses before anything executes.
        HttpResponse<String> garbage = send("POST",
                "/_tesseraql/ops/batch/jobs/user.dailyMaintenance/run", token,
                "{\"businessDate\": \"not-a-date\"}");
        assertThat(garbage.statusCode()).isEqualTo(400);
        assertThat(garbage.body()).contains("TQL-BATCH-4041");
    }

    @Test
    void businessDayCalendarsFilterScheduledFirings() throws Exception {
        // The control job — a calendar where every day counts — proves the scheduler fires.
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && executionsOf("user.calControl") < 3) {
            Thread.sleep(250);
        }
        assertThat(executionsOf("user.calControl")).isGreaterThanOrEqualTo(3);

        // Today is a holiday for both gated siblings — once as a fixed dates: entry, once as
        // a table row read at fire time. Their firings were considered, claimed, and filtered:
        // not a run, so no execution is ever recorded.
        assertThat(executionsOf("user.calStaticGated")).isZero();
        assertThat(executionsOf("user.calTableGated")).isZero();
    }

    private static long executionsOf(String jobId) {
        return runtime.jobRepository().listExecutions(500).stream()
                .filter(execution -> jobId.equals(execution.jobId()))
                .count();
    }

    @Test
    void chunkStepSkipsPoisonRowsWithinTheLimit() throws Exception {
        JobExecution execution = runtime.runJob("user.chunkSkips",
                Map.of("businessDate", "2026-08-01"));

        assertThat(execution.status()).isEqualTo(JobStatus.COMPLETED);
        StepExecution step = runtime.jobRepository().findSteps(execution.id()).get(0);
        assertThat(step.affectedRows()).isEqualTo(10); // 12 rows, 2 poison
        assertThat(step.skippedRows()).isEqualTo(2);
        assertThat(countOf("chunk_results_a")).isEqualTo(10);

        // The skips are recorded per execution and served by the operations API.
        String token = token(List.of("BATCH_OPERATOR"));
        JsonNode detail = MAPPER.readTree(send("GET",
                "/_tesseraql/ops/batch/executions/" + execution.id(), token, null).body());
        assertThat(detail.path("skips")).hasSize(2);
        List<String> skippedKeys = new java.util.ArrayList<>();
        detail.path("skips").forEach(skip -> skippedKeys.add(skip.path("rowKey").asText()));
        assertThat(skippedKeys).containsExactlyInAnyOrder("a04", "a08");

        // A completed step clears its checkpoint: the next run reads from the top.
        assertThat(runtime.jobRepository().findCheckpoint("user.chunkSkips", "load",
                java.time.LocalDate.parse("2026-08-01"))).isEmpty();
    }

    @Test
    void chunkStepResumesFromTheCheckpointAfterAFailure() throws Exception {
        // skipLimit 0 (the default): the poison row at b08 fails the step after the first
        // committed chunk (b01..b05); the uncommitted b06/b07 roll back with the chunk.
        JobExecution failed = runtime.runJob("user.chunkRestart",
                Map.of("businessDate", "2026-08-02"));
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(countOf("chunk_results_b")).isEqualTo(5);
        assertThat(runtime.jobRepository().findCheckpoint("user.chunkRestart", "load",
                java.time.LocalDate.parse("2026-08-02"))).contains("b05");

        // Fix the data and rerun the same business date: the reader binds chunk.after and
        // resumes at b06 — the primary key on the results table proves nothing reprocessed.
        execSql("update chunk_items_b set payload = '1' where item_key = 'b08'");
        JobExecution rerun = runtime.runJob("user.chunkRestart",
                Map.of("businessDate", "2026-08-02"));
        assertThat(rerun.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(countOf("chunk_results_b")).isEqualTo(15);
        StepExecution step = runtime.jobRepository().findSteps(rerun.id()).get(0);
        assertThat(step.affectedRows()).isEqualTo(10);
        assertThat(runtime.jobRepository().findCheckpoint("user.chunkRestart", "load",
                java.time.LocalDate.parse("2026-08-02"))).isEmpty();
    }

    private static long countOf(String table) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select count(*) from " + table)) {
            return rs.next() ? rs.getLong(1) : -1;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void execSql(String sql) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void anAfterTriggerChainsOnCompletionCarryingTheBusinessDate() throws Exception {
        String token = token(List.of("BATCH_OPERATOR"));

        // The ops manual run goes through the runner the scheduler uses, so the chain fires.
        HttpResponse<String> run = send("POST",
                "/_tesseraql/ops/batch/jobs/user.chainExtract/run", token,
                "{\"businessDate\": \"2026-08-03\"}");
        assertThat(run.statusCode()).isEqualTo(200);
        assertThat(run.body()).contains("COMPLETED");

        JobExecution chained = runtime.jobRepository().listExecutions(500).stream()
                .filter(execution -> "user.chainSend".equals(execution.jobId()))
                .findFirst().orElseThrow();
        assertThat(chained.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(chained.triggerType()).isEqualTo("after");
        // The chain runs the same fact: the parent's business date, not today's default.
        assertThat(chained.businessDate()).isEqualTo(java.time.LocalDate.parse("2026-08-03"));
    }

    @Test
    void aShiftedNominalDayFiringRunsForTheNominalDate() throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && executionsOf("user.calShifted") < 1) {
            Thread.sleep(250);
        }
        JobExecution shifted = runtime.jobRepository().listExecutions(500).stream()
                .filter(execution -> "user.calShifted".equals(execution.jobId()))
                .findFirst().orElseThrow();

        // Yesterday was the nominal day and a holiday: today's firing counts, and the run is
        // FOR yesterday — batch.businessDate records the nominal date, not the fire date.
        assertThat(shifted.businessDate())
                .isEqualTo(java.time.LocalDate.now().minusDays(1));
        // The sibling's nominal day is tomorrow: today's firings never count.
        assertThat(executionsOf("user.calShiftedGated")).isZero();
    }

    @Test
    void overlapSkipRecordsASkippedExecutionNamingTheRunningOne() {
        // A previous execution is still RUNNING (recorded directly - a real one would be).
        String runningId = runtime.jobRepository().startExecution("user.overlapSkip",
                "user-admin", "manual", null, java.time.LocalDate.parse("2026-08-04"));
        try {
            JobExecution skipped = runtime.runJob("user.overlapSkip", Map.of());
            assertThat(skipped.status()).isEqualTo(JobStatus.SKIPPED);
            assertThat(skipped.exitMessage()).contains(runningId).contains("overlap: skip");
            // Skipped means recorded, not run: no steps.
            assertThat(runtime.jobRepository().findSteps(skipped.id())).isEmpty();
        } finally {
            runtime.jobRepository().completeExecution(runningId);
        }
        // With the previous run finished, the same firing runs normally.
        assertThat(runtime.runJob("user.overlapSkip", Map.of()).status())
                .isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void slaSweepAlertsOnceOnTooLongAndMissedDeadline() throws Exception {
        // A job expecting completion by midnight (already past) and runs under a second.
        Path slaDir = Files.createDirectories(appHome.resolve("batch/sla"));
        Files.writeString(slaDir.resolve("job.yml"), """
                version: tesseraql/v1
                id: user.slaWatch
                kind: job
                recipe: batch-tasklet
                sla: { completeBy: "00:00", runningLongerThan: 1s }
                sql: { file: noop.sql, mode: update }
                """);
        Files.writeString(slaDir.resolve("noop.sql"),
                "update users set name = name where name = '___none___'\n");
        io.tesseraql.yaml.manifest.JobFile job = new io.tesseraql.yaml.manifest.JobFile(
                slaDir.resolve("job.yml"),
                new io.tesseraql.yaml.SimpleYamlParser().parseJob(slaDir.resolve("job.yml")));
        String runningId = runtime.jobRepository().startExecution("user.slaWatch",
                "user-admin", "schedule", null, java.time.LocalDate.now());
        try {
            execSql("update tql_job_execution set start_time = start_time - interval '1 hour'"
                    + " where job_execution_id = '" + runningId + "'");
            List<Map<String, Object>> alerts = new java.util.ArrayList<>();
            JobSlaSweeper sweeper = new JobSlaSweeper(List.of(job),
                    Map.of("user.slaWatch", "user-admin"), "user-admin",
                    runtime.jobRepository(), (payload, app) -> alerts.add(payload),
                    java.time.Clock.systemDefaultZone());

            assertThat(sweeper.sweep()).isEqualTo(2); // too-long + missed deadline
            assertThat(alerts).extracting(alert -> alert.get("kind"))
                    .containsExactlyInAnyOrder("runningLongerThan", "completeBy");
            // The claims make every alert cluster-unique and once-only: a second sweep is quiet.
            assertThat(sweeper.sweep()).isZero();
        } finally {
            runtime.jobRepository().completeExecution(runningId);
        }
    }

    @Test
    void aCooperativeStopEndsAtTheStepBoundary() {
        // Step one cancels its own execution through the ambient batch.executionId bind;
        // the boundary check stops the run before step two ever starts.
        JobExecution execution = runtime.runJob("user.stopMidway",
                Map.of("businessDate", "2026-08-05"));

        assertThat(execution.status()).isEqualTo(JobStatus.STOPPED);
        assertThat(execution.exitMessage()).contains("stopped by operator");
        List<StepExecution> steps = runtime.jobRepository().findSteps(execution.id());
        assertThat(steps).hasSize(1); // "never" was never started
        assertThat(steps.get(0).stepId()).isEqualTo("selfCancel");
        assertThat(steps.get(0).status()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void aCooperativeStopLandsOnAChunkCheckpointAndTheRerunResumes() throws Exception {
        java.util.concurrent.CompletableFuture<JobExecution> run = java.util.concurrent.CompletableFuture
                .supplyAsync(() -> runtime.runJob("user.chunkSlow",
                        Map.of("businessDate", "2026-08-06")));
        // Wait for the chunk to be mid-stream, then ask it to stop.
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && countOf("chunk_results_c") < 3) {
            Thread.sleep(50);
        }
        String executionId = runtime.jobRepository().listExecutions(500).stream()
                .filter(execution -> "user.chunkSlow".equals(execution.jobId()))
                .filter(execution -> execution.status() == JobStatus.RUNNING)
                .findFirst().orElseThrow().id();
        assertThat(runtime.jobRepository().requestCancel(executionId)).isTrue();

        JobExecution stopped = run.get(60, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(stopped.status()).isEqualTo(JobStatus.STOPPED);
        long committed = countOf("chunk_results_c");
        // The stop landed exactly on a committed chunk: a multiple of commitEvery, not all.
        assertThat(committed % 5).isZero();
        assertThat(committed).isLessThan(60);
        StepExecution step = runtime.jobRepository().findSteps(stopped.id()).get(0);
        assertThat(step.status()).isEqualTo(StepStatus.STOPPED);
        assertThat(runtime.jobRepository().findCheckpoint("user.chunkSlow", "load",
                java.time.LocalDate.parse("2026-08-06"))).isPresent();

        // The rerun for the same business date resumes at the checkpoint: the results
        // table's primary key proves nothing reprocessed, and everything arrives.
        JobExecution rerun = runtime.runJob("user.chunkSlow",
                Map.of("businessDate", "2026-08-06"));
        assertThat(rerun.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(countOf("chunk_results_c")).isEqualTo(60);
        assertThat(runtime.jobRepository().findCheckpoint("user.chunkSlow", "load",
                java.time.LocalDate.parse("2026-08-06"))).isEmpty();
    }

    @Test
    void theCancelEndpointGatesOnScopeStatusAndExistence() throws Exception {
        String token = token(List.of("BATCH_OPERATOR"));
        String runningId = runtime.jobRepository().startExecution("user.dailyMaintenance",
                "user-admin", "manual", null, java.time.LocalDate.now());
        try {
            HttpResponse<String> accepted = send("POST",
                    "/_tesseraql/ops/batch/executions/" + runningId + "/cancel", token, "{}");
            assertThat(accepted.statusCode()).isEqualTo(200);
            assertThat(MAPPER.readTree(accepted.body()).path("cancelRequested").asBoolean())
                    .isTrue();
        } finally {
            runtime.jobRepository().completeExecution(runningId);
        }
        // A finished run has nothing left to stop: 409 with its own code.
        HttpResponse<String> conflict = send("POST",
                "/_tesseraql/ops/batch/executions/" + runningId + "/cancel", token, "{}");
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("TQL-BATCH-4042");
        // Unknown - or out of scope - reads as Not Found, like every ops surface.
        HttpResponse<String> unknown = send("POST",
                "/_tesseraql/ops/batch/executions/no-such/cancel", token, "{}");
        assertThat(unknown.body()).contains("TQL-BATCH-4040");
    }

    @Test
    void operationsOverviewReportsBatchAndLanes() throws Exception {
        String token = token(List.of("BATCH_OPERATOR"));
        send("POST", "/_tesseraql/ops/batch/jobs/user.dailyMaintenance/run", token, "{}");

        HttpResponse<String> overview = send("GET", "/_tesseraql/ops/overview", token, null);
        assertThat(overview.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(overview.body());
        assertThat(body.path("batch").path("total").asInt()).isGreaterThan(0);
        assertThat(body.path("batch").path("byStatus")).isNotEmpty();
        assertThat(body.path("lanes").isArray()).isTrue();
        // The batch step's SQL is captured in the slow-SQL log (threshold lowered to 0).
        assertThat(body.path("slowSql")).isNotEmpty();
        // Trace metrics report retained spans/traces and error rates.
        assertThat(body.path("traceMetrics").path("spans").asInt()).isGreaterThan(0);
        assertThat(body.path("traceMetrics").path("traces").asInt()).isGreaterThan(0);
        assertThat(body.path("traceMetrics").has("traceErrorRate")).isTrue();
        // The overview exposes a warning flag and alert list (threshold logic covered by unit tests).
        assertThat(body.path("warning").isBoolean()).isTrue();
        assertThat(body.path("alerts").isArray()).isTrue();
    }

    @Test
    void batchJobAppearsInTraceTreeWithSqlChildren() throws Exception {
        String token = token(List.of("BATCH_OPERATOR"));
        send("POST", "/_tesseraql/ops/batch/jobs/user.dailyMaintenance/run", token, "{}");

        HttpResponse<String> tree = send("GET", "/_tesseraql/ops/traces/tree", token, null);
        assertThat(tree.statusCode()).isEqualTo(200);
        JsonNode roots = MAPPER.readTree(tree.body());
        assertThat(roots).anySatisfy(root -> {
            assertThat(root.get("span").get("name").asText()).isEqualTo("tesseraql.job");
            // job -> step -> sql (three levels).
            assertThat(root.get("children")).anySatisfy(step -> {
                assertThat(step.get("span").get("name").asText()).isEqualTo("tesseraql.job.step");
                assertThat(step.get("children"))
                        .anySatisfy(sql -> assertThat(sql.get("span").get("name").asText())
                                .isEqualTo("tesseraql.sql.execute"));
            });
        });
    }

    @Test
    void operationsApiDeniesDataOutsideTheCallersAppScope() throws Exception {
        // Seed at least one execution and trace owned by user-admin.
        send("POST", "/_tesseraql/ops/batch/jobs/user.dailyMaintenance/run",
                token(List.of("BATCH_OPERATOR")), "{}");

        // A grant for a different app sees nothing: deny by default (design ch. 26.11).
        String scoped = token(List.of("BATCH_OPERATOR"), List.of("ops.app.other-app"));
        assertThat(MAPPER.readTree(
                send("GET", "/_tesseraql/ops/batch/jobs", scoped, null).body())).isEmpty();
        assertThat(MAPPER.readTree(
                send("GET", "/_tesseraql/ops/batch/executions", scoped, null).body())).isEmpty();
        assertThat(MAPPER.readTree(
                send("GET", "/_tesseraql/ops/traces/tree", scoped, null).body())).isEmpty();
        // Running a job outside the scope is indistinguishable from an unknown job.
        JsonNode denied = MAPPER.readTree(send("POST",
                "/_tesseraql/ops/batch/jobs/user.dailyMaintenance/run", scoped, "{}").body());
        assertThat(denied.path("error").path("code").asText()).isEqualTo("TQL-BATCH-4040");

        // The matching per-app grant restores visibility, and executions carry their app.
        String granted = token(List.of("BATCH_OPERATOR"), List.of("ops.app.user-admin"));
        JsonNode executions = MAPPER.readTree(
                send("GET", "/_tesseraql/ops/batch/executions", granted, null).body());
        assertThat(executions).isNotEmpty();
        assertThat(executions.get(0).path("app").asText()).isEqualTo("user-admin");
        JsonNode tree = MAPPER.readTree(
                send("GET", "/_tesseraql/ops/traces/tree", granted, null).body());
        assertThat(tree).anySatisfy(root -> assertThat(
                root.get("span").get("attributes").path("app").asText()).isEqualTo("user-admin"));
    }

    @Test
    void operationsApiRequiresAuthentication() throws Exception {
        HttpResponse<String> response = send("GET", "/_tesseraql/ops/batch/executions", null, null);
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void operationsApiForbidsInsufficientRole() throws Exception {
        HttpResponse<String> response = send("POST",
                "/_tesseraql/ops/batch/jobs/user.dailyMaintenance/run",
                token(List.of("SOME_OTHER_ROLE")), "{}");
        assertThat(response.statusCode()).isEqualTo(403);
    }

    private static HttpResponse<String> send(String method, String path, String bearer, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        } else {
            request.GET();
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** An operator token with full per-app visibility ({@code ops.app.*}). */
    private static String token(List<String> roles) throws Exception {
        return token(roles, List.of("ops.app.*"));
    }

    private static String token(List<String> roles, List<String> permissions) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(
                Map.of("sub", "ops", "roles", roles, "permissions", permissions)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static void setStatus(String name, String status) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "update users set status = '" + status + "' where name = '" + name + "'");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String statusOf(String name) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select status from users where name = '" + name + "'")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("truncate table users restart identity");
            statement.execute("""
                    insert into users (name, status) values
                      ('sato', 'ACTIVE'),
                      ('pending-user', 'PENDING')""");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-batch-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        // A job binding the batch.* ambient namespace (docs/batch-platform.md track A):
        // the business date the run is FOR lands in the row, provably.
        Files.createDirectories(target.resolve("batch/stamp"));
        Files.writeString(target.resolve("batch/stamp/job.yml"), """
                version: tesseraql/v1
                id: user.stampBusinessDate
                kind: job
                recipe: batch-tasklet
                sql: { file: stamp.sql, mode: update }
                """);
        Files.writeString(target.resolve("batch/stamp/stamp.sql"),
                "update users set status = 'ASOF-' || cast(cast(/* batch.businessDate */"
                        + " '2026-01-01' as date) as varchar) where name = 'pending-user'\n");
        // Business-day calendars (docs/batch-platform.md track B): a control calendar where
        // every day counts, a static holiday list naming today, and a table-backed source the
        // migration seeds with today's row — the gated siblings must never record a run.
        Files.createDirectories(target.resolve("calendars"));
        Files.writeString(target.resolve("calendars/test.yml"), """
                version: tesseraql/v1
                calendars:
                  open-cal:
                    weekend: []
                  static-cal:
                    weekend: []
                    holidays:
                      dates: [%s]
                  table-cal:
                    weekend: []
                    holidays:
                      source: { table: holidays, date: holiday_date, calendar: calendar_id }
                """.formatted(java.time.LocalDate.now()));
        Files.createDirectories(target.resolve("batch/calendar"));
        for (Map.Entry<String, String> job : Map.of(
                "user.calControl", "open-cal",
                "user.calStaticGated", "static-cal",
                "user.calTableGated", "table-cal").entrySet()) {
            Files.writeString(target.resolve("batch/calendar/"
                    + job.getKey().substring("user.".length()) + ".yml"), """
                            version: tesseraql/v1
                            id: %s
                            kind: job
                            recipe: batch-tasklet
                            trigger:
                              schedule: { fixedDelay: 1s, calendar: %s }
                            sql: { file: noop.sql, mode: update }
                            """.formatted(job.getKey(), job.getValue()));
        }
        Files.writeString(target.resolve("batch/calendar/noop.sql"),
                "update users set name = name where name = '___none___'\n");
        // The chunk step (docs/batch-platform.md track C): a skip-tolerant load and a
        // checkpoint-restart load, each over its own keyset-ordered source. The poison rows
        // carry a payload that refuses to cast to integer inside the writer.
        Files.createDirectories(target.resolve("batch/chunk"));
        Files.writeString(target.resolve("batch/chunk/skips.yml"), """
                version: tesseraql/v1
                id: user.chunkSkips
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: load
                    chunk:
                      reader: { file: reader-a.sql }
                      writer: { file: writer-a.sql }
                      key: item_key
                      commitEvery: 5
                      onError: { skipLimit: 2 }
                """);
        Files.writeString(target.resolve("batch/chunk/restart.yml"), """
                version: tesseraql/v1
                id: user.chunkRestart
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: load
                    chunk:
                      reader: { file: reader-b.sql }
                      writer: { file: writer-b.sql }
                      key: item_key
                      commitEvery: 5
                """);
        for (String set : List.of("a", "b")) {
            Files.writeString(target.resolve("batch/chunk/reader-" + set + ".sql"), """
                    select item_key, payload
                    from chunk_items_%s
                    /*%%if chunk.after != null */
                    where item_key > /* chunk.after */ 'x00'
                    /*%%end*/
                    order by item_key
                    """.formatted(set));
            Files.writeString(target.resolve("batch/chunk/writer-" + set + ".sql"), """
                    insert into chunk_results_%s (item_key, val)
                    values (/* row.item_key */ 'x01', cast(/* row.payload */ '1' as integer))
                    """.formatted(set));
        }
        // The shifted nominal-day rule (docs/batch-platform.md track B follow-up): yesterday
        // is the nominal day and a declared holiday, so today's firing counts and runs FOR
        // yesterday; the gated sibling's nominal day is tomorrow, so today never counts.
        java.time.LocalDate nominal = java.time.LocalDate.now().minusDays(1);
        Files.writeString(target.resolve("calendars/shift.yml"), """
                version: tesseraql/v1
                calendars:
                  shift-cal:
                    weekend: []
                    holidays:
                      dates: [%s]
                """.formatted(nominal));
        Files.createDirectories(target.resolve("batch/shifted"));
        Files.writeString(target.resolve("batch/shifted/job.yml"), """
                version: tesseraql/v1
                id: user.calShifted
                kind: job
                recipe: batch-tasklet
                trigger:
                  schedule: { fixedDelay: 1s, calendar: shift-cal, dayOfMonth: %d }
                sql: { file: noop.sql, mode: update }
                """.formatted(nominal.getDayOfMonth()));
        Files.writeString(target.resolve("batch/shifted/gated.yml"), """
                version: tesseraql/v1
                id: user.calShiftedGated
                kind: job
                recipe: batch-tasklet
                trigger:
                  schedule: { fixedDelay: 1s, calendar: shift-cal, dayOfMonth: %d }
                sql: { file: noop.sql, mode: update }
                """.formatted(java.time.LocalDate.now().plusDays(1).getDayOfMonth()));
        Files.writeString(target.resolve("batch/shifted/noop.sql"),
                "update users set name = name where name = '___none___'\n");
        // Light chaining (docs/batch-platform.md track D): send fires after extract completes,
        // carrying the business date.
        Files.createDirectories(target.resolve("batch/chain"));
        Files.writeString(target.resolve("batch/chain/extract.yml"), """
                version: tesseraql/v1
                id: user.chainExtract
                kind: job
                recipe: batch-tasklet
                sql: { file: noop.sql, mode: update }
                """);
        Files.writeString(target.resolve("batch/chain/send.yml"), """
                version: tesseraql/v1
                id: user.chainSend
                kind: job
                recipe: batch-tasklet
                trigger:
                  after: user.chainExtract
                sql: { file: noop.sql, mode: update }
                """);
        Files.writeString(target.resolve("batch/chain/noop.sql"),
                "update users set name = name where name = '___none___'\n");
        // Overlap policy (docs/batch-platform.md track E): while the previous execution is
        // still RUNNING, a firing is recorded SKIPPED naming it.
        Files.createDirectories(target.resolve("batch/overlap"));
        Files.writeString(target.resolve("batch/overlap/job.yml"), """
                version: tesseraql/v1
                id: user.overlapSkip
                kind: job
                recipe: batch-tasklet
                overlap: skip
                sql: { file: noop.sql, mode: update }
                """);
        Files.writeString(target.resolve("batch/overlap/noop.sql"),
                "update users set name = name where name = '___none___'\n");
        StringBuilder chunkFixtures = new StringBuilder();
        for (String set : List.of("a", "b")) {
            chunkFixtures.append("create table chunk_items_").append(set)
                    .append(" (item_key varchar(32) primary key, payload varchar(32) not null);\n")
                    .append("create table chunk_results_").append(set)
                    .append(" (item_key varchar(32) primary key, val integer not null);\n");
        }
        for (int i = 1; i <= 12; i++) {
            chunkFixtures.append("insert into chunk_items_a values ('a%02d', '%s');%n"
                    .formatted(i, i == 4 || i == 8 ? "oops" : "1"));
        }
        for (int i = 1; i <= 15; i++) {
            chunkFixtures.append("insert into chunk_items_b values ('b%02d', '%s');%n"
                    .formatted(i, i == 8 ? "oops" : "1"));
        }
        // The cooperative-stop chunk (docs/jobs.md "Stopping a run"): each row sleeps a
        // little, so the test can request the cancel while the chunk is mid-stream.
        chunkFixtures.append("create table chunk_items_c"
                + " (item_key varchar(32) primary key, payload varchar(32) not null);\n")
                .append("create table chunk_results_c"
                        + " (item_key varchar(32) primary key, val integer not null);\n");
        for (int i = 1; i <= 60; i++) {
            chunkFixtures.append("insert into chunk_items_c values ('c%02d', '1');%n"
                    .formatted(i));
        }
        Files.writeString(target.resolve("db/migration/V3__chunk_fixtures.sql"),
                chunkFixtures.toString());
        Files.writeString(target.resolve("batch/chunk/slow.yml"), """
                version: tesseraql/v1
                id: user.chunkSlow
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: load
                    chunk:
                      reader: { file: reader-c.sql }
                      writer: { file: writer-c.sql }
                      key: item_key
                      commitEvery: 5
                """);
        Files.writeString(target.resolve("batch/chunk/reader-c.sql"), """
                select item_key, payload
                from chunk_items_c
                /*%if chunk.after != null */
                where item_key > /* chunk.after */ 'x00'
                /*%end*/
                order by item_key
                """);
        Files.writeString(target.resolve("batch/chunk/writer-c.sql"),
                "insert into chunk_results_c (item_key, val)"
                        + " select /* row.item_key */ 'x00', 1 from pg_sleep(0.05)\n");
        // A pipeline whose first step cancels its own execution through the ambient
        // batch.executionId bind: the step boundary must stop the run.
        Files.createDirectories(target.resolve("batch/stop"));
        Files.writeString(target.resolve("batch/stop/job.yml"), """
                version: tesseraql/v1
                id: user.stopMidway
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: selfCancel
                    sql: { file: self-cancel.sql, mode: update }
                  - id: never
                    sql: { file: never.sql, mode: update }
                """);
        Files.writeString(target.resolve("batch/stop/self-cancel.sql"),
                "update tql_job_execution set cancel_requested = now()"
                        + " where job_execution_id = /* batch.executionId */ 'x'\n");
        Files.writeString(target.resolve("batch/stop/never.sql"),
                "update users set name = name where name = '___none___'\n");
        Files.writeString(target.resolve("db/migration/V2__holidays.sql"), """
                create table holidays (
                  calendar_id  varchar(64) not null,
                  holiday_date date not null
                );
                insert into holidays (calendar_id, holiday_date) values ('table-cal', current_date);
                """);
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s

                tesseraql:
                  diagnostics:
                    slowSqlMillis: 0
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
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
