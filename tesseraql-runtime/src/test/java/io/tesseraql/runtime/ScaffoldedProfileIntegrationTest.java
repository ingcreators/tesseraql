package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.pipeline.tenant.MainRolePools;
import io.tesseraql.pipeline.tenant.PoolRole;
import io.tesseraql.yaml.scaffold.AppScaffolder;
import java.io.IOException;
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
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * The production profile a new application carries (docs/capacity-defaults.md decision 7), booted
 * as {@code tesseraql new} writes it: under {@code TESSERAQL_ENV=prod}, {@code main} is fixed at
 * 10 and waits 10 s, the two role pools hold nothing while idle, a job runs on the job pool, and
 * the metrics scrape answers a bearer holding {@code OPS} (docs/deployment-decisions.md).
 *
 * <p>The application is generated here rather than copied from the gallery, so the test reads the
 * generator's own output. Only {@code db.main}'s coordinate is pointed at the container, and one
 * job is added, because the skeleton declares none.
 */
@Testcontainers
class ScaffoldedProfileIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /** The secret this environment supplies, as a deployment would in {@code JWT_SECRET}. */
    private static final String JWT_SECRET = "profile-it-secret-for-this-environment-only";

    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();

    static Path root;
    static TesseraqlRuntime runtime;

    @BeforeAll
    static void start() throws Exception {
        root = Files.createTempDirectory("tesseraql-profile-it");
        Path appHome = generate(root.resolve("profile-it"));
        // Test classes run one at a time in this module, so the profile is set for this class
        // alone and cleared after it.
        System.setProperty("tesseraql.env", "prod");
        runtime = TesseraqlRuntime.start(appHome, 0);
    }

    @AfterAll
    static void stop() throws IOException {
        try {
            if (runtime != null) {
                runtime.close();
            }
        } finally {
            System.clearProperty("tesseraql.env");
            if (root != null) {
                try (Stream<Path> files = Files.walk(root)) {
                    files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                }
            }
        }
    }

    @Test
    void mainIsFixedAtTenAndWaitsTenSeconds() {
        HikariDataSource main = runtime.context().lookup("main", HikariDataSource.class);

        assertThat(main.getPoolName()).isEqualTo("tesseraql-main");
        assertThat(main.getMaximumPoolSize()).isEqualTo(10);
        assertThat(main.getMinimumIdle()).as("fixed-size").isEqualTo(10);
        assertThat(main.getConnectionTimeout()).isEqualTo(10_000L);
    }

    @Test
    void bothRolePoolsHoldNothingWhileIdle() {
        HikariDataSource jobs = role(PoolRole.JOBS);
        assertThat(jobs.getPoolName()).isEqualTo("tesseraql-main-jobs");
        assertThat(jobs.getMaximumPoolSize()).isEqualTo(3);
        assertThat(jobs.getMinimumIdle()).isZero();
        assertThat(jobs.getIdleTimeout()).isEqualTo(600_000L);
        assertThat(jobs.getConnectionTimeout()).isEqualTo(300_000L);

        HikariDataSource transfers = role(PoolRole.FILE_TRANSFERS);
        assertThat(transfers.getPoolName()).isEqualTo("tesseraql-main-transfers");
        assertThat(transfers.getMaximumPoolSize()).isEqualTo(5);
        assertThat(transfers.getMinimumIdle()).isZero();
        assertThat(transfers.getIdleTimeout()).isEqualTo(600_000L);
        assertThat(transfers.getConnectionTimeout()).isEqualTo(120_000L);
    }

    @Test
    void aJobRunsOnTheJobPool() throws Exception {
        HikariDataSource jobs = role(PoolRole.JOBS);
        CompletableFuture<io.tesseraql.operations.batch.JobExecution> run = CompletableFuture
                .supplyAsync(() -> runtime.runJob("slow", Map.of()));

        java.time.Instant deadline = java.time.Instant.now().plusSeconds(10);
        int active = jobs.getHikariPoolMXBean().getActiveConnections();
        while (active == 0 && java.time.Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
            active = jobs.getHikariPoolMXBean().getActiveConnections();
        }
        assertThat(active).as("the job's connection, on tesseraql-main-jobs").isPositive();
        assertThat(run.get().status().name()).isEqualTo("COMPLETED");
    }

    /**
     * The generated profile takes the JWT secret from {@code JWT_SECRET} with no fallback
     * (docs/deployment-decisions.md decision 1): without it, production refuses to start, naming
     * it, instead of running on the development secret the base configuration falls back to.
     */
    @Test
    void withoutJwtSecretTheProfileRefusesToStart() throws Exception {
        Path appHome = generate(root.resolve("no-secret"), false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TesseraqlRuntime.start(appHome, 0))
                .hasMessageContaining("TQL-YAML-1101")
                .hasMessageContaining("JWT_SECRET");
    }

    /**
     * The generated profile turns metrics on behind the gate the member's scrape already has
     * (docs/deployment-decisions.md decision 3): a request with no bearer is refused, and so is a
     * bearer without {@code OPS}, which the profile's {@code ops.metrics.view} names.
     */
    @Test
    void theScrapeIsOnAndAnswersOnlyABearerHoldingOps() throws Exception {
        assertThat(scrape(null).statusCode()).isEqualTo(401);
        assertThat(scrape(token(List.of("APP_READ"))).statusCode()).isEqualTo(403);

        HttpResponse<String> scrape = scrape(token(List.of("OPS")));
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("tesseraql_pool_");
    }

    private static HttpResponse<String> scrape(String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/_tesseraql/metrics"));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A token for the generated application, signed with the secret the test supplies. */
    private static String token(List<String> roles) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(MAPPER.writeValueAsBytes(TestClaims.addressed(
                Map.of("sub", "ops-scraper", "roles", roles,
                        "aud", List.of("https://profile-it.example.com")))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return header + "." + payload + "." + signature;
    }

    private static HikariDataSource role(PoolRole role) {
        MainRolePools roles = runtime.context().lookup(TesseraqlProperties.MAIN_ROLE_POOLS_BEAN,
                MainRolePools.class);
        assertThat(roles).as("main's role pools are bound").isNotNull();
        assertThat(roles.of(role)).isInstanceOf(HikariDataSource.class);
        return (HikariDataSource) roles.of(role);
    }

    /** The skeleton as {@code tesseraql new} writes it, pointed at the container, plus a job. */
    private static Path generate(Path home) throws IOException {
        return generate(home, true);
    }

    /**
     * The skeleton as {@code tesseraql new} writes it, pointed at the container, plus a job. The
     * generated profile takes {@code ${JWT_SECRET}} with no fallback; {@code withSecret} supplies
     * it as the configuration key of that name, which the placeholder resolves after the
     * environment.
     */
    private static Path generate(Path home, boolean withSecret) throws IOException {
        AppScaffolder scaffolder = new AppScaffolder();
        scaffolder.writeNew(home, scaffolder.scaffold("profile-it"));

        Path application = home.resolve("config/application.yml");
        String generated = Files.readString(application);
        String pointed = generated
                .replace("jdbc:postgresql://localhost:5432/profile_it", POSTGRES.getJdbcUrl())
                .replace("${DB_USER:profile_it}", POSTGRES.getUsername())
                .replace("${DB_PASSWORD:profile_it}", POSTGRES.getPassword());
        assertThat(pointed).as("the generated coordinate was found and replaced")
                .contains(POSTGRES.getJdbcUrl())
                .doesNotContain("${DB_USER");
        if (withSecret) {
            pointed += "\nJWT_SECRET: " + JWT_SECRET + "\n";
        }
        Files.writeString(application, pointed);
        assertThat(home.resolve("config/env/prod.yml")).isRegularFile();

        Path job = home.resolve("batch/slow");
        Files.createDirectories(job);
        Files.writeString(job.resolve("job.yml"), """
                version: tesseraql/v1
                id: slow
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: main
                    sql:
                      file: slow.sql
                      mode: query
                """);
        // Sleeps in the database, so the job holds its connection long enough to be seen.
        Files.writeString(job.resolve("slow.sql"), "select 1 as one from pg_sleep(1.5)\n");
        return home;
    }
}
