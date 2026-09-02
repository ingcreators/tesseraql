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

    /**
     * An upload to apply: the row statement runs once per parsed row.
     *
     * @param contract what each row must satisfy beyond parsing (docs/csv-import.md decision 3),
     *                 frozen — including the code sets a {@code codes:} column was resolved
     *                 against — so a reviewed import's two passes cannot disagree
     */
    record ImportRequest(String routeId, String appName, String format, FileReadSpec readSpec,
            Path rowSqlFile, String onError, RowContract contract) {

        /** The shape before an import could hold its rows to a contract. */
        public ImportRequest(String routeId, String appName, String format, FileReadSpec readSpec,
                Path rowSqlFile, String onError) {
            this(routeId, appName, format, readSpec, rowSqlFile, onError, RowContract.none());
        }

        public ImportRequest {
            contract = contract == null ? RowContract.none() : contract;
        }
    }

    /**
     * An export to generate: the query streams into the file; {@code afterSqlFile} optional.
     *
     * @param rowCap  the ceiling a buffering codec's export runs under, unbounded for a streaming
     *                one (docs/export-pipeline.md, decision 7)
     * @param queries named queries run on the extraction connection before the extraction, whose
     *                results a template composes around the rows (decision 2)
     * @param values  results already resolved by the caller — an export's {@code http:} sources are
     *                called at submission, so no network call happens while a cursor is held
     */
    record ExportRequest(String routeId, String appName, String format, FileWriteSpec writeSpec,
            String filename, Path querySqlFile, Map<String, Object> params,
            String afterTiming, Path afterSqlFile, ExportRowCap rowCap,
            List<ExportQuery> queries, Map<String, Object> values,
            RowEnricher enricher, int enrichWindow) {

        /** The shape before an export could enrich its rows (docs/lookups.md, slice 13b). */
        public ExportRequest(String routeId, String appName, String format,
                FileWriteSpec writeSpec, String filename, Path querySqlFile,
                Map<String, Object> params, String afterTiming, Path afterSqlFile,
                ExportRowCap rowCap, List<ExportQuery> queries, Map<String, Object> values) {
            this(routeId, appName, format, writeSpec, filename, querySqlFile, params, afterTiming,
                    afterSqlFile, rowCap, queries, values, null, 0);
        }

        public ExportRequest {
            rowCap = rowCap == null ? ExportRowCap.unbounded() : rowCap;
            queries = queries == null ? List.of() : List.copyOf(queries);
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }

    /**
     * One rejected import row, from either pass (docs/csv-import.md decision 4): the parse names
     * the column and the text it could not accept, the write pass has neither and leaves both
     * null. {@code row} is the table row the reader counted, which the surface turns into the
     * file line the author sees.
     *
     * <p>{@code message} is always the framework's own sentence, safe to render on a page.
     * {@code detail} is the database's — the driver text a write-pass rejection used to publish
     * as its message, which names SQL and sometimes another row's values. It rides beside the
     * sentence rather than replacing it, so an operator reading the transfer keeps the diagnosis
     * the report deliberately does not show (docs/csv-import.md decision 4).
     */
    record RowError(long row, String field, String value, String message, String detail) {

        /** A rejection with no column to blame and nothing beneath it — the parse's own. */
        public static RowError of(long row, String message) {
            return new RowError(row, null, null, message, null);
        }

        /** A value the declared type or the row contract refused: column, text, sentence. */
        public static RowError ofColumn(long row, String field, String value, String message) {
            return new RowError(row, field, value, message, null);
        }
    }

    /**
     * The answer to a reviewed upload (docs/csv-import.md decisions 1 and 3): what the parse
     * found, and whether anything can be committed.
     *
     * <p>{@code committable} is the whole affordance rule in one field — it is true exactly when
     * a set exists to commit, which under {@code onError: skip} is the clean rows and under
     * {@code rollback} is every row or none. The caller answers 200 with the token when it is
     * true and 422 without one when it is false, so the status code and the confirm affordance
     * can never disagree.
     *
     * @param batchId    the confirm token, null when there is nothing to confirm
     * @param rows       rows the parse read
     * @param ready      rows that would be written
     * @param rejected   rows the parse refused (the complete count, not the reported sample)
     * @param errors     the reported rejections, bounded; {@code rejected} is the true total
     * @param fileError  the file could not be read at all — a header that does not map, an
     *                   unreadable upload — in which case no row was ever examined
     * @param expiresAt  when an uncommitted batch is swept, null when nothing was parked
     */
    record ImportReview(String batchId, long rows, long ready, long rejected,
            List<RowError> errors, String fileError, java.time.Instant expiresAt) {

        public ImportReview {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        /** Whether a set exists to commit — the confirm affordance and the status code. */
        public boolean committable() {
            return batchId != null;
        }
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

    /**
     * Parses and validates the upload without writing anything, parking the batch for a later
     * commit (docs/csv-import.md decision 1). Synchronous: the answer is the report, so there is
     * nothing to poll. The spooled bytes and the <em>resolved</em> read spec are parked together,
     * because the commit is a different request and the spec's locale is resolved per request —
     * re-parsing under the commit's own resolution would move the rejection set.
     *
     * <p>Parking supersedes: the same subject's earlier unclaimed batch for the same route
     * expires as this one is parked, so a re-upload really does replace the batch rather than
     * leaving two live tokens.
     *
     * @param subject the principal parking the batch; only they may commit it
     */
    ImportReview reviewImport(ImportRequest request, String subject, java.io.InputStream content);

    /**
     * Where a data row of {@code format} sits in the file, for a surface that has to name it
     * (docs/csv-import.md decision 8). Delegates to the codec, which is the only thing that
     * knows whether the answer is a line or a sheet and a row — and which lives here, because
     * this service is what resolves a format to a codec.
     */
    RowReference locate(String format, FileReadSpec spec, long row);

    /**
     * Claims a parked batch and starts its import, returning the transfer id
     * (docs/csv-import.md decision 5). The claim is a conditional update taken <em>before</em>
     * the run, so a replayed confirm loses the race rather than importing twice; a batch that is
     * unknown, expired, claimed, or another subject's is refused.
     *
     * <p>The request supplies what the route declares — the per-row statement and the failure
     * policy — while the read spec comes from the parked batch, so the commit parses exactly
     * what the review parsed.
     */
    String commitImport(String batchId, String subject, ImportRequest request);

    /**
     * Expires parked batches past their review window: the spooled bytes are deleted and the row
     * is marked expired, so a late confirm is told the batch expired rather than "unknown".
     * Always swept, unlike produced export files — a parked batch holds business data the user
     * never chose to store. Returns the number of batches reclaimed.
     */
    int expireReviewBatches(java.time.Instant cutoff);

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
            io.tesseraql.core.sql.BoundSql afterExtract, ExportRowCap rowCap,
            Map<String, io.tesseraql.core.sql.BoundSql> queries,
            RowEnricher enricher, int enrichWindow) {

        /** The shape before an export could enrich its rows (docs/lookups.md, slice 13b). */
        public InlineExport(String routeId, String appName, String format,
                FileWriteSpec writeSpec, String filename, io.tesseraql.core.sql.BoundSql query,
                io.tesseraql.core.sql.BoundSql afterExtract, ExportRowCap rowCap,
                Map<String, io.tesseraql.core.sql.BoundSql> queries) {
            this(routeId, appName, format, writeSpec, filename, query, afterExtract, rowCap,
                    queries, null, 0);
        }

        public InlineExport {
            rowCap = rowCap == null ? ExportRowCap.unbounded() : rowCap;
            queries = queries == null ? Map.of() : Map.copyOf(queries);
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
