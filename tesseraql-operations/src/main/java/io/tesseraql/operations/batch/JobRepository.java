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

    private static final TqlErrorCode REPO_ERROR = new TqlErrorCode(TqlDomain.BATCH, 5001);

    private final DataSource dataSource;

    public JobRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Creates the repository tables if they do not already exist, from the bundled
     * {@code V1__framework_operations.sql} migration script.
     */
    public void ensureSchema() {
        try {
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource, JobRepository.class,
                    "/tesseraql/db/migration/operations/V1__framework_operations.sql");
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

    public String startExecution(String jobId, String appName, String triggerType) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        execute("""
                insert into tql_job_execution
                  (job_execution_id, job_id, app_name, status, trigger_type, start_time, created_at)
                values (?, ?, ?, ?, ?, ?, ?)""",
                ps -> {
                    ps.setString(1, id);
                    ps.setString(2, jobId);
                    ps.setString(3, appName);
                    ps.setString(4, JobStatus.RUNNING.name());
                    ps.setString(5, triggerType);
                    ps.setTimestamp(6, Timestamp.from(now));
                    ps.setTimestamp(7, Timestamp.from(now));
                });
        return id;
    }

    public void completeExecution(String executionId) {
        finishExecution(executionId, JobStatus.COMPLETED, null);
    }

    public void failExecution(String executionId, String message) {
        finishExecution(executionId, JobStatus.FAILED, message);
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
        execute("""
                update tql_step_execution
                set status = ?, end_time = ?, affected_rows = ?
                where step_execution_id = ?""",
                ps -> {
                    ps.setString(1, StepStatus.COMPLETED.name());
                    ps.setTimestamp(2, Timestamp.from(Instant.now()));
                    ps.setInt(3, affectedRows);
                    ps.setString(4, stepExecutionId);
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
                start,
                end,
                durationMs(start, end),
                rs.getString("exit_message"));
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
