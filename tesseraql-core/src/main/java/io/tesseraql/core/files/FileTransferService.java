package io.tesseraql.core.files;

import io.tesseraql.core.util.OrderedCopies;
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
     * @param invalidates the tables the route's {@code invalidates:} names (docs/caching.md):
     *                 dropped from the catalogs and the hold when the import's transaction
     *                 commits, never on a rollback
     * @param subject  who starts it (docs/job-inbox.md decision 1): the requesting principal's
     *                 stable subject, recorded on the transfer as its owner for listing; null
     *                 for a transfer nobody started — a polled import, a public route's caller
     */
    record ImportRequest(String routeId, String appName, String format, FileReadSpec readSpec,
            Path rowSqlFile, String onError, RowContract contract, List<String> emit,
            List<String> invalidates, String tenantId, TransferPool pool, String subject) {

        /** The shape before an import could hold its rows to a contract. */
        public ImportRequest(String routeId, String appName, String format, FileReadSpec readSpec,
                Path rowSqlFile, String onError) {
            this(routeId, appName, format, readSpec, rowSqlFile, onError, RowContract.none());
        }

        /** The shape before an import announced its own completion. */
        public ImportRequest(String routeId, String appName, String format, FileReadSpec readSpec,
                Path rowSqlFile, String onError, RowContract contract) {
            this(routeId, appName, format, readSpec, rowSqlFile, onError, contract, List.of(),
                    List.of(), null, null, null);
        }

        public ImportRequest {
            contract = contract == null ? RowContract.none() : contract;
            emit = emit == null ? List.of() : List.copyOf(emit);
            invalidates = invalidates == null ? List.of() : List.copyOf(invalidates);
            pool = pool == null ? TransferPool.MAIN : pool;
        }

        /** This request with the route's live-view topics and the caller's tenant attached. */
        public ImportRequest announcing(List<String> topics, String tenant) {
            return new ImportRequest(routeId, appName, format, readSpec, rowSqlFile, onError,
                    contract, topics, invalidates, tenant, pool, subject);
        }

        /**
         * This request with the tables its commit makes stale attached (docs/caching.md): the
         * code catalogs and the held results that read them are dropped when the import's
         * transaction commits — the placement {@code emit:} has, for the same reason.
         */
        public ImportRequest invalidating(List<String> tables) {
            return new ImportRequest(routeId, appName, format, readSpec, rowSqlFile, onError,
                    contract, emit, tables, tenantId, pool, subject);
        }

        /** This request with the pool its row statement runs on (docs/multi-tenancy.md). */
        public ImportRequest on(TransferPool pool) {
            return new ImportRequest(routeId, appName, format, readSpec, rowSqlFile, onError,
                    contract, emit, invalidates, tenantId, pool, subject);
        }

        /**
         * This request with who starts it attached (docs/job-inbox.md decision 1): the
         * transfer records it as its owner. The reviewed commit's frozen copy carries it like
         * the topics and the pool, so the confirmer the commit checked is the owner it records.
         */
        public ImportRequest by(String subject) {
            return new ImportRequest(routeId, appName, format, readSpec, rowSqlFile, onError,
                    contract, emit, invalidates, tenantId, pool, subject);
        }
    }

    /**
     * The datasource a transfer's own SQL runs on — the row statement, the extraction, the
     * {@code after:} statement — and the tenant it was resolved for (docs/multi-tenancy.md):
     * in a per-tenant isolation mode the request's tenant pool replaces {@code main}, exactly as
     * it does for every other executor, and the tenant id is recorded on the transfer so the
     * {@code after:} statement a first download fires — a later request — runs on the same pool.
     * {@link #MAIN} is the service's own datasource, the untenanted default.
     *
     * <p>Transfers ran on the main pool in every mode until 0.18.0: an export answered another
     * tenant's rows and an import landed in the shared schema, 202 and COMPLETED
     * (docs/audit-low-leads.md G24).
     *
     * @param dataSource the pool, or {@code null} for the service's main datasource
     * @param tenantId   the resolved tenant the pool belongs to, or {@code null} when untenanted
     */
    record TransferPool(javax.sql.DataSource dataSource, String tenantId) {

        /** The service's main datasource, untenanted. */
        public static final TransferPool MAIN = new TransferPool(null, null);
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
     * @param emit    the route's live-view topics (docs/realtime.md), announced when the
     *                {@code after:} statement commits — with the extraction, from this request;
     *                on the first fetch, from the transfer row this request is recorded on, so
     *                a fetch that knows no route (the operations console's) announces the same;
     *                an export with no follow-up writes nothing and announces nothing
     * @param invalidates the tables the route's {@code invalidates:} names (docs/caching.md),
     *                dropped from the catalogs and the hold at the same commit, never on a
     *                rollback
     * @param tenantId the tenant the announcement is scoped to — the requesting principal's,
     *                read on the request because the run outlives it
     * @param subject who starts it (docs/job-inbox.md decision 1): the requesting principal's
     *                stable subject, recorded on the transfer as its owner for listing — never a
     *                reader gate — or null for a transfer nobody started (a job step's export)
     */
    record ExportRequest(String routeId, String appName, String format, FileWriteSpec writeSpec,
            String filename, Path querySqlFile, Map<String, Object> params,
            String afterTiming, Path afterSqlFile, ExportRowCap rowCap,
            List<ExportQuery> queries, Map<String, Object> values,
            RowEnricher enricher, int enrichWindow, List<String> emit,
            List<String> invalidates, String tenantId, TransferPool pool, String subject) {

        /** The shape before an export's follow-up announced itself (docs/list-export.md). */
        public ExportRequest(String routeId, String appName, String format,
                FileWriteSpec writeSpec, String filename, Path querySqlFile,
                Map<String, Object> params, String afterTiming, Path afterSqlFile,
                ExportRowCap rowCap, List<ExportQuery> queries, Map<String, Object> values,
                RowEnricher enricher, int enrichWindow, TransferPool pool) {
            this(routeId, appName, format, writeSpec, filename, querySqlFile, params, afterTiming,
                    afterSqlFile, rowCap, queries, values, enricher, enrichWindow, List.of(),
                    List.of(), null, pool, null);
        }

        /** The shape before an export carried its pool (docs/multi-tenancy.md). */
        public ExportRequest(String routeId, String appName, String format,
                FileWriteSpec writeSpec, String filename, Path querySqlFile,
                Map<String, Object> params, String afterTiming, Path afterSqlFile,
                ExportRowCap rowCap, List<ExportQuery> queries, Map<String, Object> values,
                RowEnricher enricher, int enrichWindow) {
            this(routeId, appName, format, writeSpec, filename, querySqlFile, params, afterTiming,
                    afterSqlFile, rowCap, queries, values, enricher, enrichWindow, null);
        }

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
            values = values == null ? Map.of() : OrderedCopies.map(values);
            emit = emit == null ? List.of() : List.copyOf(emit);
            invalidates = invalidates == null ? List.of() : List.copyOf(invalidates);
            pool = pool == null ? TransferPool.MAIN : pool;
        }

        /** This request with the pool its extraction and {@code after:} statement run on. */
        public ExportRequest on(TransferPool pool) {
            return new ExportRequest(routeId, appName, format, writeSpec, filename, querySqlFile,
                    params, afterTiming, afterSqlFile, rowCap, queries, values, enricher,
                    enrichWindow, emit, invalidates, tenantId, pool, subject);
        }

        /**
         * This request with who starts it attached (docs/job-inbox.md decision 1): the
         * transfer records it as its owner, and the surfaces that list a subject's own
         * transfers read it back. Null — a caller with no principal — records nothing.
         */
        public ExportRequest by(String subject) {
            return new ExportRequest(routeId, appName, format, writeSpec, filename, querySqlFile,
                    params, afterTiming, afterSqlFile, rowCap, queries, values, enricher,
                    enrichWindow, emit, invalidates, tenantId, pool, subject);
        }

        /**
         * This request with the route's live-view topics and the caller's tenant attached: what
         * the {@code after:} statement's commit announces (docs/list-export.md), the placement
         * an import's completion signal has.
         */
        public ExportRequest announcing(List<String> topics, String tenant) {
            return new ExportRequest(routeId, appName, format, writeSpec, filename, querySqlFile,
                    params, afterTiming, afterSqlFile, rowCap, queries, values, enricher,
                    enrichWindow, topics, invalidates, tenant, pool, subject);
        }

        /**
         * This request with the tables its follow-up makes stale attached (docs/caching.md):
         * dropped from the catalogs and the hold when the {@code after:} statement commits.
         */
        public ExportRequest invalidating(List<String> tables) {
            return new ExportRequest(routeId, appName, format, writeSpec, filename, querySqlFile,
                    params, afterTiming, afterSqlFile, rowCap, queries, values, enricher,
                    enrichWindow, emit, tables, tenantId, pool, subject);
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

    /**
     * The transfer state: the execution status plus transfer-specific detail.
     *
     * @param rows         rows written so far — published while the run is still going, not only
     *                     at the end (docs/csv-import.md decision 6)
     * @param expectedRows  how many rows the run will attempt, when that was knowable before it
     *                      started; null otherwise, and a progress surface then counts up with
     *                      no total rather than showing a guessed one
     * @param fileReclaimed whether a completed export's file has been reclaimed by the retention
     *                      sweep (docs/list-export.md decision 5): the row stays as history, the
     *                      bytes are gone, and a surface that would offer the file says expired
     *                      instead of done with a dead link
     * @param createdAt     when the transfer started (docs/job-inbox.md decision 3), for a
     *                      surface that lists a subject's own; null on the shapes that predate it
     */
    record TransferStatus(String transferId, String routeId, String appName, String direction,
            String status, long rows, Long expectedRows, List<RowError> errors, String filename,
            boolean downloaded, String exitMessage, String tenantId, boolean fileReclaimed,
            java.time.Instant createdAt) {

        /** The shape before a transfer knew its file had been reclaimed. */
        public TransferStatus(String transferId, String routeId, String appName, String direction,
                String status, long rows, Long expectedRows, List<RowError> errors, String filename,
                boolean downloaded, String exitMessage, String tenantId) {
            this(transferId, routeId, appName, direction, status, rows, expectedRows, errors,
                    filename, downloaded, exitMessage, tenantId, false, null);
        }

        /** The shape before a transfer carried the tenant it was resolved for. */
        public TransferStatus(String transferId, String routeId, String appName, String direction,
                String status, long rows, Long expectedRows, List<RowError> errors, String filename,
                boolean downloaded, String exitMessage) {
            this(transferId, routeId, appName, direction, status, rows, expectedRows, errors,
                    filename, downloaded, exitMessage, null);
        }

        /** The shape before a failed transfer carried the reason it failed for. */
        public TransferStatus(String transferId, String routeId, String appName, String direction,
                String status, long rows, Long expectedRows, List<RowError> errors, String filename,
                boolean downloaded) {
            this(transferId, routeId, appName, direction, status, rows, expectedRows, errors,
                    filename, downloaded, null);
        }

        /** The shape before a running transfer could say how far through it was. */
        public TransferStatus(String transferId, String routeId, String appName, String direction,
                String status, long rows, List<RowError> errors, String filename,
                boolean downloaded) {
            this(transferId, routeId, appName, direction, status, rows, null, errors, filename,
                    downloaded);
        }

        /**
         * The code a failed run recorded, or null: the {@code TQL-XXX-nnnn} prefix of its exit
         * message (docs/export-hygiene.md P8). The message itself carries the driver's text, SQL
         * fragments and paths and is never projected onto the wire; the code is what a client can
         * branch on, and the framework's own sentence for it is the catalog's.
         */
        public String failureCode() {
            if (exitMessage == null) {
                return null;
            }
            java.util.regex.Matcher code = java.util.regex.Pattern
                    .compile("^(TQL-[A-Z]+-\\d{4})\\b").matcher(exitMessage);
            return code.find() ? code.group(1) : null;
        }
    }

    /** A ready file: stream plus response metadata. */
    record Download(String filename, String contentType, InputStream content) {
    }

    /**
     * One transfer in the operations overview, tagged with its owning app for scoping.
     * {@code expired} marks a completed export whose produced bytes the retention sweep has
     * reclaimed — the row stays as history, the download answers 409. {@code subject} is who
     * started it (docs/job-inbox.md decision 7), null for a transfer nobody started.
     */
    record TransferSummary(String transferId, String routeId, String appName, String direction,
            String format, String status, long rows, String filename, boolean downloaded,
            boolean expired, java.time.Instant createdAt, String subject) {
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
            queries = queries == null ? Map.of() : OrderedCopies.map(queries);
        }
    }

    /**
     * The produced transfer: its id (also the download handle), the name it is recorded under (a
     * split export's is the bundle's), and the row count written.
     */
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

    /**
     * Asks a running transfer to stop (docs/csv-import.md decision 6). Cooperative: the request
     * is a flag the import's row loop reads between rows, so a stop takes effect at a row
     * boundary and never mid-statement. Returns false when there was nothing running to stop —
     * a finished run, or an id nobody knows.
     *
     * <p>What a stopped import leaves is nothing: an import is one transaction with a savepoint
     * per row, so a stop before the commit takes every applied row with it.
     */
    boolean cancel(String transferId);

    /** The most recent transfers, newest first (for the operations console). */
    List<TransferSummary> recent(int limit);

    /**
     * One subject's transfers of one application, newest first, at most {@code limit} rows —
     * both directions (docs/job-inbox.md decision 3). The tenant is part of "own" the way the
     * route's subtree reads it: a row recorded under another tenant, or under none where the
     * caller has one, is not listed. A null subject lists nothing: a transfer nobody started
     * belongs to nobody. Who may <em>read</em> a transfer is unchanged by this listing; every
     * link a surface renders from it goes through the route's own subtree.
     */
    default List<TransferStatus> mine(String appName, String subject, String tenantId,
            int limit) {
        return mine(appName, subject, tenantId, null, limit);
    }

    /**
     * {@link #mine}, narrowed to one direction ({@code EXPORT} or {@code IMPORT}) when
     * {@code direction} is not null: the "My exports" page lists exports alone
     * (docs/job-inbox.md decision 9), and filtering after the cap would under-fill it.
     */
    List<TransferStatus> mine(String appName, String subject, String tenantId, String direction,
            int limit);

    /**
     * One subject's exports of one route that still need them (docs/job-inbox.md decision 8):
     * running, or completed with a file nobody has fetched yet. Newest first, at most
     * {@code limit}; the tenant rule of {@link #mine}.
     */
    List<TransferStatus> pending(String appName, String routeId, String subject, String tenantId,
            int limit);

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
     * Opens the generated file once the export completed (empty when unknown or not ready) and
     * takes the first-download claim: the first call that opened the bytes records the transfer
     * as downloaded and runs the {@code download}-timed follow-up statement, in one transaction
     * — a follow-up that fails releases the claim, so the next fetch tries again. Once it
     * committed, the fetch announces what the transfer recorded when it started
     * (docs/list-export.md): the route's live-view topics and the tables the statement made
     * stale. The row carries the declaration, so the route's own file leg and the operations
     * console's fetch announce alike; a fetch that took no claim, or whose transfer runs no
     * download-timed statement, announces nothing.
     */
    Optional<Download> download(String transferId);

    /**
     * Opens the generated file exactly as {@link #download} would — the same refusals, the same
     * name, type and length — without taking the claim or running the follow-up. What a HEAD of
     * the download reads (docs/edge-hygiene.md E4): a request that by definition delivers no
     * byte must not be recorded as the download, so the GET's headers come from here and the
     * claim waits for a GET.
     */
    Optional<Download> inspect(String transferId);
}
