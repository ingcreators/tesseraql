package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobStatus;
import io.tesseraql.operations.batch.StepExecution;
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
