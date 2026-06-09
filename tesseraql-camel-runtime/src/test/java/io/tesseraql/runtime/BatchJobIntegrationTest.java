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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for milestone M3: a batch job runs its pipeline, mutates the database, and
 * records job/step executions in the repository (design ch. 6.5, 26).
 */
@Testcontainers
class BatchJobIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
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
    void runsBatchJobRecordsExecutionAndMutatesData() {
        // Reset to PENDING so the assertion is independent of other tests that run the same job.
        setStatus("pending-user", "PENDING");
        JobExecution execution = runtime.runJob("user.dailyMaintenance",
                Map.of("businessDate", "2026-06-08"));

        assertThat(execution.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(execution.jobId()).isEqualTo("user.dailyMaintenance");
        assertThat(execution.endTime()).isNotNull();

        List<StepExecution> steps = runtime.jobRepository().findSteps(execution.id());
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).stepId()).isEqualTo("deactivatePending");
        assertThat(steps.get(0).affectedRows()).isEqualTo(1);

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
    void operationsOverviewReportsBatchAndLanes() throws Exception {
        String token = token(List.of("BATCH_OPERATOR"));
        send("POST", "/_tesseraql/ops/batch/jobs/user.dailyMaintenance/run", token, "{}");

        HttpResponse<String> overview = send("GET", "/_tesseraql/ops/overview", token, null);
        assertThat(overview.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(overview.body());
        assertThat(body.path("batch").path("total").asInt()).isGreaterThan(0);
        assertThat(body.path("batch").path("byStatus")).isNotEmpty();
        assertThat(body.path("lanes").isArray()).isTrue();
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
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(List<String> roles) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(
                Map.of("sub", "ops", "roles", roles)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
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
            statement.execute("""
                    create table users (
                      id serial primary key,
                      name varchar(200) not null,
                      status varchar(32) not null,
                      created_at timestamp not null default now()
                    )""");
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
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
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
