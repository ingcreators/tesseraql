package io.tesseraql.operations.retention;

import io.tesseraql.core.attachment.AttachmentStore;
import io.tesseraql.core.blob.BlobRef;
import io.tesseraql.core.blob.BlobStore;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;

/**
 * Deletes framework data past its retention period (design ch. 44): delivered outbox events and
 * finished batch executions (with their steps), and — when configured — attachments older than the
 * configured windows (roadmap Phase 30 slice 3). The sweep is idempotent and safe to run
 * concurrently from several nodes; rows still in flight ({@code PENDING}/{@code SENDING} events,
 * {@code RUNNING} executions) are never touched, and blob deletes are best-effort so two nodes
 * racing on the same object is harmless.
 */
public final class RetentionSweeper {

    private static final TqlErrorCode SWEEP_ERROR = new TqlErrorCode(TqlDomain.BATCH, 5201);

    private final DataSource dataSource;
    private final AttachmentStore attachmentStore;
    private final BlobStore blobStore;

    public RetentionSweeper(DataSource dataSource) {
        this(dataSource, null, null);
    }

    public RetentionSweeper(DataSource dataSource, AttachmentStore attachmentStore,
            BlobStore blobStore) {
        this.dataSource = dataSource;
        this.attachmentStore = attachmentStore;
        this.blobStore = blobStore;
    }

    /** What one sweep removed. */
    public record Result(int outboxEvents, int jobExecutions, int stepExecutions, int attachments) {
    }

    /** Removes delivered outbox events and finished executions older than the given windows. */
    public Result sweep(Duration outboxRetention, Duration jobRetention) {
        return sweep(outboxRetention, jobRetention, null);
    }

    /**
     * Removes delivered outbox events, finished executions, and — when {@code attachmentRetention}
     * is non-null and an attachment store is wired — attachments (metadata rows and their blobs)
     * older than the given windows.
     */
    public Result sweep(Duration outboxRetention, Duration jobRetention,
            Duration attachmentRetention) {
        Instant now = Instant.now();
        int outbox;
        int[] jobs;
        try (Connection connection = dataSource.getConnection()) {
            outbox = deleteOutbox(connection, now.minus(outboxRetention));
            jobs = deleteExecutions(connection, now.minus(jobRetention));
        } catch (SQLException ex) {
            throw new TqlException(SWEEP_ERROR, "Retention sweep failed: " + ex.getMessage());
        }
        int attachments = attachmentRetention == null
                ? 0
                : sweepAttachments(now.minus(attachmentRetention));
        return new Result(outbox, jobs[0], jobs[1], attachments);
    }

    /**
     * Deletes attachment metadata older than {@code cutoff} and reclaims each blob (best-effort, so
     * an already-removed blob or a concurrent node is harmless). Returns the number removed.
     */
    int sweepAttachments(Instant cutoff) {
        if (attachmentStore == null) {
            return 0;
        }
        List<String> storageKeys = attachmentStore.deleteOlderThan(cutoff);
        if (blobStore != null) {
            for (String key : storageKeys) {
                try {
                    blobStore.delete(new BlobRef(key, null, 0, null, null));
                } catch (RuntimeException ignored) {
                    // best-effort; an already-removed blob is fine
                }
            }
        }
        return storageKeys.size();
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
            return new int[]{ps.executeUpdate(), steps};
        }
    }
}
