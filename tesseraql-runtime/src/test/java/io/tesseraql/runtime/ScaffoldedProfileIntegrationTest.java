package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.pipeline.tenant.MainRolePools;
import io.tesseraql.pipeline.tenant.PoolRole;
import io.tesseraql.yaml.scaffold.AppScaffolder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The production profile a new application carries (docs/capacity-defaults.md decision 7), booted
 * as {@code tesseraql new} writes it: under {@code TESSERAQL_ENV=prod}, {@code main} is fixed at
 * 10 and waits 10 s, the two role pools hold nothing while idle, and a job runs on the job pool.
 *
 * <p>The application is generated here rather than copied from the gallery, so the test reads the
 * generator's own output. Only {@code db.main}'s coordinate is pointed at the container, and one
 * job is added, because the skeleton declares none.
 */
@Testcontainers
class ScaffoldedProfileIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

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

    private static HikariDataSource role(PoolRole role) {
        MainRolePools roles = runtime.context().lookup(TesseraqlProperties.MAIN_ROLE_POOLS_BEAN,
                MainRolePools.class);
        assertThat(roles).as("main's role pools are bound").isNotNull();
        assertThat(roles.of(role)).isInstanceOf(HikariDataSource.class);
        return (HikariDataSource) roles.of(role);
    }

    /** The skeleton as {@code tesseraql new} writes it, pointed at the container, plus a job. */
    private static Path generate(Path home) throws IOException {
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
