package io.tesseraql.operations.retention;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;

/**
 * Deletes framework data past its retention period (design ch. 44): delivered outbox events and
 * finished batch executions (with their steps) older than the configured windows. The sweep is
 * idempotent and safe to run concurrently from several nodes; rows still in flight
 * ({@code PENDING}/{@code SENDING} events, {@code RUNNING} executions) are never touched.
 */
public final class RetentionSweeper {

    private static final TqlErrorCode SWEEP_ERROR = new TqlErrorCode(TqlDomain.BATCH, 5201);

    private final DataSource dataSource;

    public RetentionSweeper(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** What one sweep removed. */
    public record Result(int outboxEvents, int jobExecutions, int stepExecutions) {
    }

    /** Removes delivered outbox events and finished executions older than the given windows. */
    public Result sweep(Duration outboxRetention, Duration jobRetention) {
        Instant now = Instant.now();
        try (Connection connection = dataSource.getConnection()) {
            int outbox = deleteOutbox(connection, now.minus(outboxRetention));
            int[] jobs = deleteExecutions(connection, now.minus(jobRetention));
            return new Result(outbox, jobs[0], jobs[1]);
        } catch (SQLException ex) {
            throw new TqlException(SWEEP_ERROR, "Retention sweep failed: " + ex.getMessage());
        }
    }

    private int deleteOutbox(Connection connection, Instant cutoff) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "delete from tql_outbox_event where status = 'SENT' and sent_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            return ps.executeUpdate();
        }
    }

    private int[] deleteExecutions(Connection connection, Instant cutoff) throws SQLException {
        int steps;
        try (PreparedStatement ps = connection.prepareStatement(
                "delete from tql_step_execution where job_execution_id in ("
                        + "select job_execution_id from tql_job_execution "
                        + "where status <> 'RUNNING' and end_time < ?)")) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            steps = ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "delete from tql_job_execution where status <> 'RUNNING' and end_time < ?")) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            return new int[] {ps.executeUpdate(), steps};
        }
    }
}
