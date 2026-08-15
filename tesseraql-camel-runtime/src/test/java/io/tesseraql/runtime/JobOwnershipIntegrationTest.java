package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.operations.batch.NodeIdentity;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A batch run has an owner and a pulse (docs/audit-hardening.md Decision 6).
 *
 * <p>{@code tql_job_execution} had no owner, node or heartbeat column, and {@code findRunning}
 * selected on {@code status = 'RUNNING'} alone. A replica killed mid-run therefore left a row that
 * read as a live run forever, and {@code overlap: skip} wedged permanently: every later firing was
 * recorded SKIPPED naming an execution nobody would ever finish.
 */
@Testcontainers
class JobOwnershipIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static JobRepository repository(String nodeId) {
        JobRepository repository = new JobRepository(dataSource(), nodeId);
        repository.ensureSchema();
        return repository;
    }

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    @Test
    void aRunIsOwnedAndBeatingFromItsFirstInstant() {
        JobRepository repository = repository("node-a");
        String executionId = repository.startExecution("owned.job", "app", "manual", "tester");

        JobExecution execution = repository.findExecution(executionId).orElseThrow();
        assertThat(execution.ownerNode()).isEqualTo("node-a");
        // Not merely non-null: a row must never be briefly indistinguishable from an abandoned one.
        assertThat(execution.heartbeatAt()).isNotNull();
        assertThat(execution.ownerAlive(Instant.now(), Duration.ofMinutes(5))).isTrue();
    }

    /** The defect, and the fix: a run whose owner stopped reporting stops blocking. */
    @Test
    void aRunWhoseOwnerStoppedReportingIsNoLongerCountedAsRunning() throws Exception {
        JobRepository repository = repository("node-doomed");
        String executionId = repository.startExecution("wedged.job", "app", "cron", null);

        assertThat(repository.findRunning("wedged.job", Duration.ofMinutes(5)))
                .extracting(JobExecution::id).containsExactly(executionId);

        // The replica dies: no more heartbeats, and the row stays RUNNING forever.
        ageHeartbeat(executionId, Duration.ofHours(2));

        // The unfiltered read still sees it — the row is still there, and the reaper is what
        // writes its outcome. What changed is that overlap: skip stops believing in it.
        assertThat(repository.findRunning("wedged.job")).hasSize(1);
        assertThat(repository.findRunning("wedged.job", Duration.ofMinutes(5))).isEmpty();
    }

    /** A live run still blocks: the window is many heartbeat intervals wide for exactly this. */
    @Test
    void aRunStillBeatingKeepsBlockingOverlappingFirings() {
        JobRepository repository = repository("node-live");
        String executionId = repository.startExecution("busy.job", "app", "cron", null);

        repository.heartbeat(executionId);

        assertThat(repository.findRunning("busy.job", Duration.ofMinutes(5)))
                .extracting(JobExecution::id).containsExactly(executionId);
    }

    /**
     * A row from before this column existed reads as alive, deliberately.
     *
     * <p>Treating a null heartbeat as dead would let the reaper kill a run that a still-running
     * older process owns. The conservative reading keeps today's behaviour for those rows: they
     * stay wedged, which is the bug this change stops creating rather than one it retroactively
     * repairs.
     */
    @Test
    void aRowWithNoHeartbeatAtAllIsTreatedAsAlive() throws Exception {
        JobRepository repository = repository("node-legacy");
        String executionId = repository.startExecution("legacy.job", "app", "cron", null);
        clearHeartbeat(executionId);

        List<JobExecution> running = repository.findRunning("legacy.job", Duration.ofMinutes(5));
        assertThat(running).extracting(JobExecution::id).containsExactly(executionId);
        assertThat(running.get(0).heartbeatAt()).isNull();
    }

    /** Two replicas of one image on one host must not share an identity. */
    @Test
    void aDerivedNodeIdIsUsedWhenNoneIsConfigured() {
        assertThat(NodeIdentity.resolve(null)).isNotBlank()
                .contains(String.valueOf(ProcessHandle.current().pid()));
        assertThat(NodeIdentity.resolve("  slot-3  ")).isEqualTo("slot-3");
    }

    private static void ageHeartbeat(String executionId, Duration age) throws Exception {
        update(executionId, Timestamp.from(Instant.now().minus(age)));
    }

    private static void clearHeartbeat(String executionId) throws Exception {
        update(executionId, null);
    }

    private static void update(String executionId, Timestamp heartbeat) throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement ps = connection.prepareStatement(
                        "update tql_job_execution set heartbeat_at = ?"
                                + " where job_execution_id = ?")) {
            ps.setTimestamp(1, heartbeat);
            ps.setString(2, executionId);
            ps.executeUpdate();
        }
    }
}
