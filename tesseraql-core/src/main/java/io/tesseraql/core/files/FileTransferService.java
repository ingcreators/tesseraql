package io.tesseraql.core.files;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generic asynchronous file transfers between uploaded/downloaded tabular files and the database
 * (design ch. 28): the {@code file-import} recipe parses an uploaded file and applies a 2-way SQL
 * statement per row, the {@code file-export} recipe streams a query into a generated file, both
 * tracked as batch executions (visible and app-scoped in the operations console). Exports can run
 * a follow-up statement either with the extraction (same transaction) or on first download.
 */
public interface FileTransferService {

    /** Import behavior on a failing row. */
    String ON_ERROR_ROLLBACK = "rollback";
    String ON_ERROR_SKIP = "skip";

    /** Export follow-up timing. */
    String AFTER_EXTRACT = "extract";
    String AFTER_DOWNLOAD = "download";

    /** An upload to apply: the row statement runs once per parsed row. */
    record ImportRequest(String routeId, String appName, String format, FileReadSpec readSpec,
            Path rowSqlFile, String onError) {
    }

    /**
     * An export to generate: the query streams into the file; {@code afterSqlFile} optional.
     *
     * @param rowCap the ceiling a buffering codec's export runs under, unbounded for a streaming
     *               one (docs/export-pipeline.md, decision 7)
     */
    record ExportRequest(String routeId, String appName, String format, FileWriteSpec writeSpec,
            String filename, Path querySqlFile, Map<String, Object> params,
            String afterTiming, Path afterSqlFile, ExportRowCap rowCap) {

        public ExportRequest {
            rowCap = rowCap == null ? ExportRowCap.unbounded() : rowCap;
        }
    }

    /** One rejected import row. */
    record RowError(long row, String message) {
    }

    /** The transfer state: the execution status plus transfer-specific detail. */
    record TransferStatus(String transferId, String routeId, String appName, String direction,
            String status, long rows, List<RowError> errors, String filename,
            boolean downloaded) {
    }

    /** A ready file: stream plus response metadata. */
    record Download(String filename, String contentType, InputStream content) {
    }

    /**
     * One transfer in the operations overview, tagged with its owning app for scoping.
     * {@code expired} marks a completed export whose produced bytes the retention sweep has
     * reclaimed — the row stays as history, the download answers 409.
     */
    record TransferSummary(String transferId, String routeId, String appName, String direction,
            String format, String status, long rows, String filename, boolean downloaded,
            boolean expired, java.time.Instant createdAt) {
    }

    /**
     * Starts an asynchronous import of the uploaded content; returns the transfer id. The stream
     * is consumed (spooled off-heap) before this returns, so arbitrarily large uploads never
     * materialize in memory.
     */
    String startImport(ImportRequest request, java.io.InputStream content);

    /** Starts an asynchronous export; returns the transfer id. */
    String startExport(ExportRequest request);

    /**
     * A batch step's extraction, pre-rendered by its executor: the caller resolves the dialect
     * variant and the file placeholders it already owns, so the service only executes. The
     * follow-up, when present, is extraction-timed by construction (a step refuses
     * {@code timing: download} at lint time).
     */
    record InlineExport(String routeId, String appName, String format, FileWriteSpec writeSpec,
            String filename, io.tesseraql.core.sql.BoundSql query,
            io.tesseraql.core.sql.BoundSql afterExtract, ExportRowCap rowCap) {

        public InlineExport {
            rowCap = rowCap == null ? ExportRowCap.unbounded() : rowCap;
        }
    }

    /** The produced transfer: its id (also the download handle) and the row count written. */
    record InlineResult(String transferId, String filename, long rows) {
    }

    /**
     * Runs an export synchronously on the given connection source — the batch export step
     * (docs/analytics-experience.md track 3). Bookkeeping is identical to
     * {@link #startExport}: an execution row, a transfer row, the spool; only the shape
     * differs — the caller's thread, the caller's datasource, and a thrown error instead of a
     * failed status to poll.
     */
    InlineResult exportInline(InlineExport request, javax.sql.DataSource extraction);

    /** The transfer state, or empty when the id is unknown. */
    Optional<TransferStatus> status(String transferId);

    /** The most recent transfers, newest first (for the operations console). */
    List<TransferSummary> recent(int limit);

    /**
     * Reclaims the produced files of transfers created before {@code cutoff}
     * (docs/file-transfers.md, retention): the spooled bytes are deleted and the row keeps
     * its history with the spool reference cleared, so the download answers "no downloadable
     * file" from then on. Idempotent and safe on every node — though a node-local file spool
     * can only free its own disk; cluster deployments want {@code tesseraql.temp.store:
     * db|blob}. Returns the number of transfers whose file was reclaimed.
     */
    int expireTransfersOlderThan(java.time.Instant cutoff);

    /**
     * Opens the generated file once the export completed (empty when unknown or not ready). The
     * first successful download triggers the {@code download}-timed follow-up statement.
     */
    Optional<Download> download(String transferId);
}
