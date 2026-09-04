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

    /**
     * The clock every running execution reports on, and what stops it.
     *
     * <p>Both halves matter. Two executions pulsing together is the shape a burst of transfers
     * has, and they are written by one statement per tick rather than one connection each — a
     * pulse that queued behind the work it reports on would silence every live execution at
     * exactly the load where the reaper then kills them. Closing a pulse has to stop the writing,
     * or a finished row keeps looking alive and {@code overlap: skip} never lets the next firing
     * through.
     */
    @Test
    void everyRegisteredExecutionKeepsBeatingUntilItsPulseCloses() throws Exception {
        JobRepository repository = repository("node-pulse");
        String running = repository.startExecution("pulse.a", "app", "manual", null);
        String finishing = repository.startExecution("pulse.b", "app", "manual", null);

        try (io.tesseraql.operations.batch.ExecutionHeartbeats heartbeats = new io.tesseraql.operations.batch.ExecutionHeartbeats(
                repository,
                Duration.ofMillis(100))) {
            io.tesseraql.operations.batch.ExecutionHeartbeats.Pulse first = heartbeats
                    .start(running);
            io.tesseraql.operations.batch.ExecutionHeartbeats.Pulse second = heartbeats
                    .start(finishing);

            Instant runningBefore = heartbeatAt(repository, running);
            Instant finishingBefore = heartbeatAt(repository, finishing);
            Thread.sleep(1_000);

            // Both advanced, from one statement per tick.
            assertThat(heartbeatAt(repository, running)).isAfter(runningBefore);
            Instant finishingAfter = heartbeatAt(repository, finishing);
            assertThat(finishingAfter).isAfter(finishingBefore);

            second.close();
            // A tick that had already read the live set when close() returned may still be
            // writing, so the last value is taken after that one can have landed — comparing
            // against a value read before the close would be a race, not an assertion.
            Thread.sleep(500);
            Instant finishingAtRest = heartbeatAt(repository, finishing);
            Instant runningAtClose = heartbeatAt(repository, running);
            Thread.sleep(1_000);

            // The closed one stopped; the open one did not.
            assertThat(heartbeatAt(repository, finishing)).isEqualTo(finishingAtRest);
            assertThat(heartbeatAt(repository, running)).isAfter(runningAtClose);
            first.close();
        }
    }

    private static Instant heartbeatAt(JobRepository repository, String executionId) {
        return repository.findExecution(executionId).orElseThrow().heartbeatAt();
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

    // --- the reaper (docs/audit-hardening.md Decision 6, slice 9) --------------------------------

    /**
     * A stranded row is finished, with a reason that names what happened.
     *
     * <p>The distinct code is the point: an operator seeing TQL-BATCH-4212 knows the node running
     * this went away, which is an infrastructure incident, not a job whose own logic failed. Those
     * are different responses, and folding them into one exit message would make the reaper look
     * like a source of job failures.
     */
    @Test
    void anAbandonedRunIsReapedWithAReasonOfItsOwn() throws Exception {
        JobRepository repository = repository("node-gone");
        String executionId = repository.startExecution("abandoned.job", "app", "cron", null);
        ageHeartbeat(executionId, Duration.ofHours(2));

        assertThat(repository.reapAbandoned("abandoned.job", Duration.ofMinutes(5)))
                .containsExactly(executionId);

        JobExecution reaped = repository.findExecution(executionId).orElseThrow();
        assertThat(reaped.status().name()).isEqualTo("FAILED");
        assertThat(reaped.endTime()).isNotNull();
        assertThat(reaped.exitMessage())
                .contains(JobRepository.REAPED_REASON)
                .contains("node-gone")
                .contains("abandoned, not failed");
    }

    /** A live run is never reaped — the window is many heartbeat intervals wide for this. */
    @Test
    void aRunStillReportingIsLeftAlone() {
        JobRepository repository = repository("node-busy");
        String executionId = repository.startExecution("busy2.job", "app", "cron", null);

        assertThat(repository.reapAbandoned("busy2.job", Duration.ofMinutes(5))).isEmpty();
        assertThat(repository.findExecution(executionId).orElseThrow().status().name())
                .isEqualTo("RUNNING");
    }

    /**
     * The reaper never overwrites a verdict the run itself reached.
     *
     * <p>The marking update is conditional on the row still being RUNNING, so a run that finished
     * between the reaper's read and its write keeps its own outcome — and two nodes sweeping at
     * once produce one write and one winner.
     */
    @Test
    void aRunThatFinishedItselfKeepsItsOwnOutcome() throws Exception {
        JobRepository repository = repository("node-racing");
        String executionId = repository.startExecution("racy.job", "app", "cron", null);
        ageHeartbeat(executionId, Duration.ofHours(2));
        repository.completeExecution(executionId);

        assertThat(repository.reapAbandoned("racy.job", Duration.ofMinutes(5))).isEmpty();
        JobExecution finished = repository.findExecution(executionId).orElseThrow();
        assertThat(finished.status().name()).isEqualTo("COMPLETED");
        // A clean completion carries no exit message at all, which is itself the assertion: the
        // reaper did not write one over it.
        assertThat(finished.exitMessage()).isNull();
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
