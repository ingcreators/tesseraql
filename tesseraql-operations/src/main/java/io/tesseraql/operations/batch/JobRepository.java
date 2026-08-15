package io.tesseraql.operations.batch;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * JDBC-backed batch execution repository (design ch. 26.3). Persists job and step executions to
 * {@code TQL_JOB_EXECUTION} and {@code TQL_STEP_EXECUTION}.
 */
public final class JobRepository {

    /** TQL-BATCH-5001: the job repository could not read or write the job/execution tables. */
    private static final TqlErrorCode REPO_ERROR = new TqlErrorCode(TqlDomain.BATCH, 5001);

    private final DataSource dataSource;

    /** This process's label on the runs it starts (docs/audit-hardening.md Decision 6). */
    private final String nodeId;

    public JobRepository(DataSource dataSource) {
        this(dataSource, NodeIdentity.resolve(null));
    }

    public JobRepository(DataSource dataSource, String nodeId) {
        this.dataSource = dataSource;
        this.nodeId = nodeId;
    }

    /** The node label this repository stamps on the runs it starts. */
    public String nodeId() {
        return nodeId;
    }

    /**
     * Records that this node is still running {@code executionId}.
     *
     * <p>Driven by a timer, never by step or chunk-commit boundaries. Those are where the
     * cooperative stop already polls, so reusing them looks free — but their cadence is bounded by
     * step duration rather than by a clock, and a job whose long step is a single non-chunk
     * statement would emit no heartbeat for its whole runtime. A reaper reading that silence would
     * kill a live run, which is the exact false positive the alert-only SLA decision was written to
     * avoid (docs/jobs.md).
     */
    public void heartbeat(String executionId) {
        execute("update tql_job_execution set heartbeat_at = ? where job_execution_id = ?",
                ps -> {
                    ps.setTimestamp(1, Timestamp.from(Instant.now()));
                    ps.setString(2, executionId);
                });
    }

    /**
     * Creates the repository tables if they do not already exist, from the bundled
     * {@code V1__framework_operations.sql} migration script.
     */
    public void ensureSchema() {
        try {
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource, JobRepository.class,
                    "/tesseraql/db/migration/operations/V1__framework_operations.sql");
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource, JobRepository.class,
                    "/tesseraql/db/migration/operations/V3__job_execution_actor.sql");
            // The columns and tables this store reads and writes must exist even where only
            // the bootstrap runs (no Flyway): the business date (V4) and the chunk step's
            // checkpoint/skip machinery (V5) — column adds stay idempotent through the
            // bootstrap's tolerated duplicate-column errors, like V3.
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource, JobRepository.class,
                    "/tesseraql/db/migration/operations/V4__job_execution_business_date.sql");
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource, JobRepository.class,
                    "/tesseraql/db/migration/operations/V5__chunk_checkpoints.sql");
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource, JobRepository.class,
                    "/tesseraql/db/migration/operations/V6__execution_params.sql");
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource, JobRepository.class,
                    "/tesseraql/db/migration/operations/V7__execution_cancel.sql");
            // The owner and the heartbeat (V8): startExecution writes both on every insert, so a
            // bootstrap-only deployment that skipped this would fail its first run rather than
            // quietly losing the ownership it is supposed to record.
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource, JobRepository.class,
                    "/tesseraql/db/migration/operations/V8__execution_owner.sql");
        } catch (SQLException ex) {
            throw error("Failed to create batch repository schema", ex);
        }
    }

    /**
     * Claims one scheduled firing of {@code jobId} across all runtime nodes (design ch. 26): the
     * first node to insert the {@code (job_id, fire_time)} claim row runs the job, every other
     * node's insert hits the primary key and skips. Claims older than seven days are pruned
     * opportunistically.
     */
    public boolean tryClaimFiring(String jobId, Instant fireTime) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement prune = connection.prepareStatement(
                    "delete from tql_job_claim where claimed_at < ?")) {
                prune.setTimestamp(1,
                        Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(7))));
                prune.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into tql_job_claim (job_id, fire_time, claimed_at) values (?, ?, ?)")) {
                insert.setString(1, jobId);
                insert.setTimestamp(2, Timestamp.from(fireTime));
                insert.setTimestamp(3, Timestamp.from(Instant.now()));
                insert.executeUpdate();
                return true;
            }
        } catch (SQLException ex) {
            if (io.tesseraql.core.dialect.SqlErrors.isUniqueViolation(ex)) {
                return false;
            }
            throw error("Failed to claim job firing for " + jobId, ex);
        }
    }

    /**
     * Releases a claim taken by {@link #tryClaimFiring} — used when the side effect the claim
     * guarded (an SLA alert) failed, so the next sweep can retry rather than the claim burning the
     * firing permanently. Absent (already pruned) rows are a no-op.
     */
    public void releaseFiring(String jobId, Instant fireTime) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement delete = connection.prepareStatement(
                        "delete from tql_job_claim where job_id = ? and fire_time = ?")) {
            delete.setString(1, jobId);
            delete.setTimestamp(2, Timestamp.from(fireTime));
            delete.executeUpdate();
        } catch (SQLException ex) {
            throw error("Failed to release job firing claim for " + jobId, ex);
        }
    }

    public String startExecution(String jobId, String appName, String triggerType,
            String triggeredBy) {
        return startExecution(jobId, appName, triggerType, triggeredBy, null);
    }

    /** Starts an execution recording the business date the run is for (docs/batch-platform.md). */
    public String startExecution(String jobId, String appName, String triggerType,
            String triggeredBy, java.time.LocalDate businessDate) {
        return startExecution(jobId, appName, triggerType, triggeredBy, businessDate, null);
    }

    /** Starts an execution also recording its parameters, so a rerun re-runs the same fact. */
    public String startExecution(String jobId, String appName, String triggerType,
            String triggeredBy, java.time.LocalDate businessDate, String paramsJson) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        execute("""
                insert into tql_job_execution
                  (job_execution_id, job_id, app_name, status, trigger_type, triggered_by,
                   business_date, params_json, start_time, created_at, owner_node, heartbeat_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                ps -> {
                    ps.setString(1, id);
                    ps.setString(2, jobId);
                    ps.setString(3, appName);
                    ps.setString(4, JobStatus.RUNNING.name());
                    ps.setString(5, triggerType);
                    ps.setString(6, triggeredBy);
                    ps.setDate(7,
                            businessDate == null ? null : java.sql.Date.valueOf(businessDate));
                    ps.setString(8, paramsJson);
                    ps.setTimestamp(9, Timestamp.from(now));
                    ps.setTimestamp(10, Timestamp.from(now));
                    // The run is owned from its first instant, and its first heartbeat is that
                    // instant: a row is never briefly indistinguishable from an abandoned one.
                    ps.setString(11, nodeId);
                    ps.setTimestamp(12, Timestamp.from(now));
                });
        return id;
    }

    /**
     * Running executions of {@code jobId} whose owner is still reporting
     * (docs/audit-hardening.md Decision 6).
     *
     * <p>This used to select on {@code status = 'RUNNING'} alone, so a replica killed mid-run left
     * a row that read as a live run forever and {@code overlap: skip} wedged for good. The
     * predicate is applied in Java rather than in SQL because the "no heartbeat means alive"
     * reading is a decision worth stating once, in {@link JobExecution#ownerAlive}, rather than
     * spelling out as a null-tolerant comparison in every dialect.
     *
     * <p>Being plain about the limit: this makes a wedged row visible, it does not finish it. The
     * reaper is what writes the outcome.
     */
    public List<JobExecution> findRunning(String jobId, java.time.Duration livenessWindow) {
        Instant now = Instant.now();
        return findRunning(jobId).stream()
                .filter(execution -> execution.ownerAlive(now, livenessWindow))
                .toList();
    }

    /**
     * Every execution still marked RUNNING, newest first — alive or not.
     *
     * <p>What the reaper and the console read; {@code overlap: skip} and the SLA sweep want the
     * liveness-filtered overload above.
     */
    public List<JobExecution> findRunning(String jobId) {
        List<JobExecution> executions = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select * from tql_job_execution where job_id = ? and status = ?"
                                + " order by start_time desc")) {
            ps.setString(1, jobId);
            ps.setString(2, JobStatus.RUNNING.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    executions.add(readExecution(rs));
                }
            }
        } catch (SQLException ex) {
            throw error("Failed to find running executions", ex);
        }
        return executions;
    }

    /**
     * Records a firing {@code overlap: skip} declined to run (docs/batch-platform.md track E):
     * auditable, alertable, and cheap to check against this table — but not a run.
     */
    public String recordSkipped(String jobId, String appName, String triggerType,
            java.time.LocalDate businessDate, String message) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        execute("""
                insert into tql_job_execution
                  (job_execution_id, job_id, app_name, status, trigger_type, business_date,
                   start_time, end_time, exit_message, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                ps -> {
                    ps.setString(1, id);
                    ps.setString(2, jobId);
                    ps.setString(3, appName);
                    ps.setString(4, JobStatus.SKIPPED.name());
                    ps.setString(5, triggerType);
                    ps.setDate(6,
                            businessDate == null ? null : java.sql.Date.valueOf(businessDate));
                    ps.setTimestamp(7, Timestamp.from(now));
                    ps.setTimestamp(8, Timestamp.from(now));
                    ps.setString(9, message);
                    ps.setTimestamp(10, Timestamp.from(now));
                });
        return id;
    }

    /** Whether the job has a COMPLETED execution for the business date (SLA completeBy). */
    public boolean hasCompleted(String jobId, java.time.LocalDate businessDate) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select 1 from tql_job_execution where job_id = ? and status = ?"
                                + " and business_date = ?")) {
            ps.setString(1, jobId);
            ps.setString(2, JobStatus.COMPLETED.name());
            ps.setDate(3, java.sql.Date.valueOf(businessDate));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            throw error("Failed to check the SLA completion", ex);
        }
    }

    /**
     * Requests a cooperative stop of a RUNNING execution (docs/jobs.md "Stopping a run"): the
     * executor polls the flag at step and chunk-commit boundaries. Returns false when the
     * execution is not running — a finished run has nothing left to stop.
     */
    public boolean requestCancel(String executionId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "update tql_job_execution set cancel_requested = ?"
                                + " where job_execution_id = ? and status = ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, executionId);
            ps.setString(3, JobStatus.RUNNING.name());
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw error("Failed to request a cancel", ex);
        }
    }

    /** Whether a cooperative stop was requested for the execution (polled at boundaries). */
    public boolean isCancelRequested(String executionId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select cancel_requested from tql_job_execution"
                                + " where job_execution_id = ?")) {
            ps.setString(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getTimestamp(1) != null;
            }
        } catch (SQLException ex) {
            throw error("Failed to read the cancel flag", ex);
        }
    }

    /** Marks an execution STOPPED: the cooperative stop took effect at a boundary. */
    public void stopExecution(String executionId, String message) {
        finishExecution(executionId, JobStatus.STOPPED, message);
    }

    /** Marks a step STOPPED at a chunk-commit boundary, keeping its counts. */
    public void stopStep(String stepExecutionId, int affectedRows, int skippedRows) {
        execute("""
                update tql_step_execution
                set status = ?, end_time = ?, affected_rows = ?, skipped_rows = ?
                where step_execution_id = ?""",
                ps -> {
                    ps.setString(1, StepStatus.STOPPED.name());
                    ps.setTimestamp(2, Timestamp.from(Instant.now()));
                    ps.setInt(3, affectedRows);
                    ps.setInt(4, skippedRows);
                    ps.setString(5, stepExecutionId);
                });
    }

    /** The recorded parameters of an execution ({@code tesseraql job rerun}), when present. */
    public Optional<String> findExecutionParams(String executionId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select params_json from tql_job_execution where job_execution_id = ?")) {
            ps.setString(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw error("Failed to read execution parameters", ex);
        }
    }

    public void completeExecution(String executionId) {
        finishExecution(executionId, JobStatus.COMPLETED, null);
    }

    public void failExecution(String executionId, String message) {
        finishExecution(executionId, JobStatus.FAILED, message);
    }

    /**
     * TQL-BATCH-4212: an execution was reaped because its owner stopped reporting
     * (docs/audit-hardening.md Decision 6).
     *
     * <p>A code of its own, and that is the point of the whole mechanism: the console and the alert
     * set have to be able to tell "the node running this went away" from "the job's own logic
     * failed". They are different incidents with different responses — one is infrastructure, the
     * other is the app — and folding them into one exit message would make the reaper look like a
     * source of job failures.
     */
    public static final String REAPED_REASON = "TQL-BATCH-4212";

    /**
     * Finishes runs whose owner stopped reporting, returning their ids
     * (docs/audit-hardening.md Decision 6).
     *
     * <p>The reaper is a recovery mechanism, not a correctness guarantee, and the difference is
     * worth stating rather than papering over. A graceful stop already writes its own outcome;
     * what strands a row is SIGKILL, OOM or node loss, and those strand it at any timeout. This is
     * what notices afterwards.
     *
     * <p>It sweeps whatever it finds rather than only this node's rows: the whole point is that the
     * node which owned the row is gone. Marking is a conditional update on the row still being
     * RUNNING, so a run that finished between the read and the write keeps its own outcome — the
     * reaper never overwrites a verdict the run itself reached.
     */
    public List<String> reapAbandoned(String jobId, java.time.Duration livenessWindow) {
        Instant now = Instant.now();
        List<String> reaped = new ArrayList<>();
        for (JobExecution execution : findRunning(jobId)) {
            if (execution.ownerAlive(now, livenessWindow)) {
                continue;
            }
            if (markReaped(execution, livenessWindow)) {
                reaped.add(execution.id());
            }
        }
        return reaped;
    }

    /** True when this call is the one that finished the row. */
    private boolean markReaped(JobExecution execution, java.time.Duration livenessWindow) {
        String message = REAPED_REASON + ": owner '"
                + (execution.ownerNode() == null ? "unknown" : execution.ownerNode())
                + "' stopped reporting for longer than " + livenessWindow
                + "; the run was abandoned, not failed";
        int updated = update("""
                update tql_job_execution
                set status = ?, end_time = ?, exit_message = ?
                where job_execution_id = ? and status = ?""",
                ps -> {
                    ps.setString(1, JobStatus.FAILED.name());
                    ps.setTimestamp(2, Timestamp.from(Instant.now()));
                    ps.setString(3, message);
                    ps.setString(4, execution.id());
                    ps.setString(5, JobStatus.RUNNING.name());
                });
        return updated > 0;
    }

    private void finishExecution(String executionId, JobStatus status, String message) {
        execute("""
                update tql_job_execution
                set status = ?, end_time = ?, exit_message = ?
                where job_execution_id = ?""",
                ps -> {
                    ps.setString(1, status.name());
                    ps.setTimestamp(2, Timestamp.from(Instant.now()));
                    ps.setString(3, message);
                    ps.setString(4, executionId);
                });
    }

    public String startStep(String executionId, String stepId) {
        String id = UUID.randomUUID().toString();
        execute("""
                insert into tql_step_execution
                  (step_execution_id, job_execution_id, step_id, status, start_time)
                values (?, ?, ?, ?, ?)""",
                ps -> {
                    ps.setString(1, id);
                    ps.setString(2, executionId);
                    ps.setString(3, stepId);
                    ps.setString(4, StepStatus.RUNNING.name());
                    ps.setTimestamp(5, Timestamp.from(Instant.now()));
                });
        return id;
    }

    public void completeStep(String stepExecutionId, int affectedRows) {
        completeStep(stepExecutionId, affectedRows, 0);
    }

    /** Completes a step recording its processed and skipped counts (chunk steps). */
    public void completeStep(String stepExecutionId, int affectedRows, int skippedRows) {
        execute("""
                update tql_step_execution
                set status = ?, end_time = ?, affected_rows = ?, skipped_rows = ?
                where step_execution_id = ?""",
                ps -> {
                    ps.setString(1, StepStatus.COMPLETED.name());
                    ps.setTimestamp(2, Timestamp.from(Instant.now()));
                    ps.setInt(3, affectedRows);
                    ps.setInt(4, skippedRows);
                    ps.setString(5, stepExecutionId);
                });
    }

    /**
     * The chunk checkpoint a rerun resumes from (docs/batch-platform.md track C): the last
     * handled key of the newest committed chunk, one row per job/step/business date.
     */
    public Optional<String> findCheckpoint(String jobId, String stepId,
            java.time.LocalDate businessDate) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select last_key from tql_job_checkpoint"
                                + " where job_id = ? and step_id = ? and business_date = ?")) {
            ps.setString(1, jobId);
            ps.setString(2, stepId);
            ps.setDate(3, java.sql.Date.valueOf(businessDate));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw error("Failed to read the chunk checkpoint", ex);
        }
    }

    /** Records a committed chunk's last handled key; only the claiming node writes it. */
    public void saveCheckpoint(String jobId, String stepId, java.time.LocalDate businessDate,
            String lastKey) {
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement update = connection.prepareStatement(
                    "update tql_job_checkpoint set last_key = ?, updated_at = ?"
                            + " where job_id = ? and step_id = ? and business_date = ?")) {
                update.setString(1, lastKey);
                update.setTimestamp(2, now);
                update.setString(3, jobId);
                update.setString(4, stepId);
                update.setDate(5, java.sql.Date.valueOf(businessDate));
                if (update.executeUpdate() > 0) {
                    return;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into tql_job_checkpoint"
                            + " (job_id, step_id, business_date, last_key, updated_at)"
                            + " values (?, ?, ?, ?, ?)")) {
                insert.setString(1, jobId);
                insert.setString(2, stepId);
                insert.setDate(3, java.sql.Date.valueOf(businessDate));
                insert.setString(4, lastKey);
                insert.setTimestamp(5, now);
                insert.executeUpdate();
            }
        } catch (SQLException ex) {
            throw error("Failed to save the chunk checkpoint", ex);
        }
    }

    /** A step that completes clears its checkpoint — the next run reads from the top. */
    public void clearCheckpoint(String jobId, String stepId, java.time.LocalDate businessDate) {
        execute("delete from tql_job_checkpoint"
                + " where job_id = ? and step_id = ? and business_date = ?",
                ps -> {
                    ps.setString(1, jobId);
                    ps.setString(2, stepId);
                    ps.setDate(3, java.sql.Date.valueOf(businessDate));
                });
    }

    /** Records one row the chunk skip policy tolerated (docs/batch-platform.md track C). */
    public void recordSkip(String executionId, String stepId, String rowKey, String message) {
        execute("""
                insert into tql_job_skips
                  (skip_id, job_execution_id, step_id, row_key, message, created_at)
                values (?, ?, ?, ?, ?, ?)""",
                ps -> {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, executionId);
                    ps.setString(3, stepId);
                    ps.setString(4, rowKey);
                    ps.setString(5, message == null
                            ? null
                            : message.substring(0, Math.min(message.length(), 2000)));
                    ps.setTimestamp(6, Timestamp.from(Instant.now()));
                });
    }

    /** One skipped row of an execution, for the operations API and console. */
    public record SkippedRow(String stepId, String rowKey, String message, Instant createdAt) {
    }

    /** The rows an execution's chunk steps skipped, oldest first. */
    public List<SkippedRow> findSkips(String executionId) {
        List<SkippedRow> skips = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select step_id, row_key, message, created_at from tql_job_skips"
                                + " where job_execution_id = ? order by created_at")) {
            ps.setString(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    skips.add(new SkippedRow(rs.getString(1), rs.getString(2), rs.getString(3),
                            instant(rs.getTimestamp(4))));
                }
            }
        } catch (SQLException ex) {
            throw error("Failed to read skipped rows", ex);
        }
        return skips;
    }

    /** Marks a step SKIPPED: recorded, not run (a rerun's {@code --from-failed-step}). */
    public void skipStep(String stepExecutionId) {
        execute("""
                update tql_step_execution
                set status = ?, end_time = ?
                where step_execution_id = ?""",
                ps -> {
                    ps.setString(1, StepStatus.SKIPPED.name());
                    ps.setTimestamp(2, Timestamp.from(Instant.now()));
                    ps.setString(3, stepExecutionId);
                });
    }

    public void failStep(String stepExecutionId, String message) {
        execute("""
                update tql_step_execution
                set status = ?, end_time = ?, error_message = ?
                where step_execution_id = ?""",
                ps -> {
                    ps.setString(1, StepStatus.FAILED.name());
                    ps.setTimestamp(2, Timestamp.from(Instant.now()));
                    ps.setString(3, message);
                    ps.setString(4, stepExecutionId);
                });
    }

    /** The vendor-appropriate trailing row-limit clause, detected once per store. */
    private volatile String fetchClause;

    private String fetchClause() {
        if (fetchClause == null) {
            fetchClause = io.tesseraql.core.dialect.Pagination.fetchClause(
                    io.tesseraql.core.util.DatabaseVendors.vendor(dataSource).orElse(null));
        }
        return fetchClause;
    }

    public List<JobExecution> listExecutions(int limit) {
        List<JobExecution> executions = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select * from tql_job_execution order by start_time desc "
                                + fetchClause())) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    executions.add(readExecution(rs));
                }
            }
        } catch (SQLException ex) {
            throw error("Failed to list executions", ex);
        }
        return executions;
    }

    /** The most recent execution of {@code jobId}, or empty when it has never run. */
    public Optional<JobExecution> latestExecution(String jobId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select * from tql_job_execution where job_id = ? "
                                + "order by start_time desc " + fetchClause())) {
            ps.setString(1, jobId);
            ps.setInt(2, 1);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readExecution(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw error("Failed to find the latest execution", ex);
        }
    }

    public Optional<JobExecution> findExecution(String executionId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select * from tql_job_execution where job_execution_id = ?")) {
            ps.setString(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readExecution(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw error("Failed to find execution", ex);
        }
    }

    public List<StepExecution> findSteps(String executionId) {
        List<StepExecution> steps = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select * from tql_step_execution where job_execution_id = ? order by start_time")) {
            ps.setString(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    steps.add(readStep(rs));
                }
            }
        } catch (SQLException ex) {
            throw error("Failed to find steps", ex);
        }
        return steps;
    }

    private static JobExecution readExecution(ResultSet rs) throws SQLException {
        Instant start = instant(rs.getTimestamp("start_time"));
        Instant end = instant(rs.getTimestamp("end_time"));
        return new JobExecution(
                rs.getString("job_execution_id"),
                rs.getString("job_id"),
                rs.getString("app_name"),
                JobStatus.valueOf(rs.getString("status")),
                rs.getString("trigger_type"),
                rs.getString("triggered_by"),
                rs.getDate("business_date") == null
                        ? null
                        : rs.getDate("business_date").toLocalDate(),
                start,
                end,
                durationMs(start, end),
                rs.getString("exit_message"),
                rs.getString("owner_node"),
                instant(rs.getTimestamp("heartbeat_at")));
    }

    private static StepExecution readStep(ResultSet rs) throws SQLException {
        Instant start = instant(rs.getTimestamp("start_time"));
        Instant end = instant(rs.getTimestamp("end_time"));
        return new StepExecution(
                rs.getString("step_execution_id"),
                rs.getString("job_execution_id"),
                rs.getString("step_id"),
                StepStatus.valueOf(rs.getString("status")),
                start,
                end,
                durationMs(start, end),
                (Integer) rs.getObject("affected_rows"),
                (Integer) rs.getObject("skipped_rows"),
                rs.getString("error_message"));
    }

    private static Long durationMs(Instant start, Instant end) {
        return start == null || end == null ? null : end.toEpochMilli() - start.toEpochMilli();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    /** Like {@link #execute}, but the caller needs to know whether its row was the one updated. */
    private int update(String sql, StatementBinder binder) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        } catch (SQLException ex) {
            throw error("Repository update failed", ex);
        }
    }

    private void execute(String sql, StatementBinder binder) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw error("Repository update failed", ex);
        }
    }

    private static TqlException error(String message, SQLException ex) {
        return TqlException.builder(REPO_ERROR).message(message + ": " + ex.getMessage()).cause(ex)
                .build();
    }
}
