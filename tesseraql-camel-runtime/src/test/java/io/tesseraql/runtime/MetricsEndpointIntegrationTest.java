package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The Prometheus exposition (roadmap Phase 45, decision point 9): opt-in, bearer + policy
 * gated by default, and fed by the always-on JDK-only aggregating meter — invoking a route
 * shows up as a labelled latency histogram and invocation counter on the next scrape.
 */
@Testcontainers
class MetricsEndpointIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    void scrapeIsPolicyGatedAndShowsRouteHistogramsAfterAnInvocation() throws Exception {
        // Drive one request through a route so the meter has something to expose.
        assertThat(get("/api/ping", null).statusCode()).isEqualTo(200);

        // No bearer -> the scrape is refused (metric labels reveal route ids).
        assertThat(get("/_tesseraql/metrics", null).statusCode()).isEqualTo(401);
        // A bearer without the ops.metrics.view policy role is refused too.
        assertThat(get("/_tesseraql/metrics", token(List.of("NOBODY"))).statusCode())
                .isEqualTo(403);

        HttpResponse<String> scrape = get("/_tesseraql/metrics", token(List.of("OPS")));
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.headers().firstValue("Content-Type").orElse(""))
                .contains("version=0.0.4");
        assertThat(scrape.body())
                .contains("# TYPE tesseraql_route_invocations_total counter")
                .contains("tesseraql_route_invocations_total{method=\"GET\","
                        + "routeId=\"ping\"}")
                .contains("# TYPE tesseraql_route_duration_seconds histogram")
                .contains("routeId=\"ping\"")
                .contains("outcome=\"2xx\"")
                .contains("le=\"+Inf\"")
                .contains("tesseraql_route_duration_seconds_count");
    }

    @Test
    void scrapeCarriesThePollSourceGaugesFromTheRegistry() throws Exception {
        // The bundled job's sftp host is not allow-listed, so the source is refused at
        // wire time — the scrape is where that refusal becomes alertable outside the
        // console (docs/poll-source-metrics.md).
        HttpResponse<String> scrape = get("/_tesseraql/metrics", token(List.of("OPS")));

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body())
                .contains("# TYPE tesseraql_poll_source_wired gauge")
                .contains("tesseraql_poll_source_wired{jobId=\"partner.intake\"} 0")
                .contains("tesseraql_poll_source_consecutive_failures"
                        + "{jobId=\"partner.intake\"} 0")
                // No poll has ever completed, so the age family stays absent — and the
                // skip reason's host never leaks into the exposition.
                .doesNotContain("tesseraql_poll_source_last_poll_age_seconds")
                .doesNotContain("files.partner.example");
    }

    private static HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token(List<String> roles) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(MAPPER.writeValueAsBytes(Map.of(
                "sub", "metrics-scraper", "roles", roles)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return header + "." + payload + "." + signature;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void jobRunsCountOnTheExpositionWithTheirStatusAndDuration() throws Exception {
        // One real run through the executor: the counter and the duration histogram land.
        runtime.runJob("metrics.tick", java.util.Map.of());

        HttpResponse<String> scrape = get("/_tesseraql/metrics", token(List.of("OPS")));
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body())
                .contains("# TYPE tesseraql_job_runs_total counter")
                .contains("job=\"metrics.tick\"")
                .contains("status=\"COMPLETED\"")
                .contains("tesseraql_job_duration_seconds");
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-metrics-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: metrics-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  security:
                    jwt:
                      secret: dev-only-secret-change-me-in-production
                      rolesClaim: roles
                    policies:
                      ops.metrics.view:
                        anyOf:
                          - role: OPS
                  metrics:
                    enabled: true
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path pingDir = target.resolve("web/api/ping");
        Files.createDirectories(pingDir);
        Files.writeString(pingDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: ping
                kind: route
                recipe: query-json
                security:
                  auth: public
                sql:
                  file: ping.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(pingDir.resolve("ping.sql"), "select 'pong' as answer\n");
        // A poll-triggered job whose sftp host is not in the (unset) allow-list: refused
        // at wire time, deterministically, so the scrape has a poll source to expose.
        Path jobDir = target.resolve("batch/partner-intake");
        Files.createDirectories(jobDir);
        Files.writeString(jobDir.resolve("job.yml"), """
                version: tesseraql/v1
                id: partner.intake
                kind: job
                recipe: file-import
                trigger:
                  poll:
                    transport: sftp
                    host: files.partner.example
                    path: /inbound
                    credential: partner
                    include: "*.csv"
                import:
                  format: csv
                  columns:
                    - orderNo
                  sql:
                    file: noop.sql
                """);
        Files.writeString(jobDir.resolve("noop.sql"), "select 1\n");
        // A runnable tasklet: the job-metrics test drives one run through the executor so
        // the exposition has a tesseraql_job_runs_total sample (docs/jobs.md).
        Path tickDir = target.resolve("batch/tick");
        Files.createDirectories(tickDir);
        Files.writeString(tickDir.resolve("job.yml"), """
                version: tesseraql/v1
                id: metrics.tick
                kind: job
                recipe: batch-tasklet
                sql: { file: tick.sql, mode: query }
                """);
        Files.writeString(tickDir.resolve("tick.sql"), "select 1 as one\n");
        return target;
    }
}
