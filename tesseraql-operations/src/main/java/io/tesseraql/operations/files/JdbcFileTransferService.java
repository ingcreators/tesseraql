package io.tesseraql.operations.files;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.ExportQuery;
import io.tesseraql.core.files.FileCodec;
import io.tesseraql.core.files.FileCodecs;
import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.core.files.FileWriteSpec;
import io.tesseraql.core.files.SpooledRows;
import io.tesseraql.core.spool.SpoolKind;
import io.tesseraql.core.spool.SpoolRef;
import io.tesseraql.core.spool.SpoolWriter;
import io.tesseraql.core.spool.TempStore;
import io.tesseraql.core.sql.BoundParameter;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.operations.batch.ExecutionHeartbeats;
import io.tesseraql.operations.batch.JobRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Database-backed {@link FileTransferService} (design ch. 28): every transfer is a batch
 * execution in {@code tql_job_execution} (so it shows up app-scoped in the operations console)
 * plus a {@code tql_file_transfer} row holding the transfer detail - generated file location,
 * rejected rows, download state. Work runs on virtual threads; generated files spool through the
 * {@link TempStore} so they never materialize in memory.
 *
 * <p>Imports parse every row, render the per-row 2-way statement and execute it under a row
 * savepoint: with {@code onError: rollback} (default) any failure rolls the whole import back
 * while still reporting every rejected row; with {@code skip} the clean rows commit. Exports
 * stream the query through the codec into a spool file; the optional follow-up statement runs in
 * the extraction transaction ({@code extract}) or once on first download ({@code download}).
 */
public final class JdbcFileTransferService implements FileTransferService {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcFileTransferService.class);
    private static final TqlErrorCode TRANSFER_ERROR = new TqlErrorCode(TqlDomain.LD, 2810);
    private static final TqlErrorCode EMPTY_UPLOAD = new TqlErrorCode(TqlDomain.LD, 2820);
    // The review-batch refusals (docs/csv-import.md decision 5). They are distinct codes so a
    // log and an operator can tell them apart, and they all answer the same status, because to
    // the caller they are one situation — this token cannot be spent, upload again — and
    // answering them differently would tell a holder of someone else's token which tokens exist.
    private static final TqlErrorCode BATCH_UNKNOWN = new TqlErrorCode(TqlDomain.LD, 2860);
    private static final TqlErrorCode BATCH_EXPIRED = new TqlErrorCode(TqlDomain.LD, 2861);
    private static final TqlErrorCode BATCH_CLAIMED = new TqlErrorCode(TqlDomain.LD, 2862);
    private static final TqlErrorCode BATCH_FOREIGN = new TqlErrorCode(TqlDomain.LD, 2864);
    private static final TqlErrorCode BATCH_PARSE_MOVED = new TqlErrorCode(TqlDomain.LD, 2865);
    private static final TqlErrorCode BATCH_SPOOL_GONE = new TqlErrorCode(TqlDomain.LD, 2866);
    private static final int MAX_RECORDED_ERRORS = 100;
    /** How often a running import publishes its counter and looks for a stop request. */
    private static final long PROGRESS_INTERVAL_NANOS = java.time.Duration.ofSeconds(2).toNanos();
    /** The review window, unless the app narrows or widens it (tesseraql.transfers.reviewTtl). */
    private static final long DEFAULT_REVIEW_TTL_MILLIS = 30 * 60 * 1000L;
    /** A parked batch's life: waiting, spent, replaced by a newer upload, or swept. */
    private static final String PARKED = "PARKED";
    private static final String COMMITTED = "COMMITTED";
    private static final String SUPERSEDED = "SUPERSEDED";
    private static final String EXPIRED = "EXPIRED";

    private final JobRepository jobs;
    /** The pulse a running transfer writes, shared with the job executor's runs. */
    private final io.tesseraql.operations.batch.ExecutionHeartbeats heartbeats;
    private final TempStore tempStore;
    private final DataSource dataSource;
    private final FileCodecs codecs;
    private final io.tesseraql.core.expr.ExpressionFunctions functions;
    private final ObjectMapper mapper = io.tesseraql.yaml.JsonMappers.constrained();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private volatile String dialect;
    private int sqlTimeoutSeconds;
    private long reviewTtlMillis = DEFAULT_REVIEW_TTL_MILLIS;
    private io.tesseraql.core.telemetry.Tracer tracer = io.tesseraql.core.telemetry.NoopTracer.INSTANCE;
    private java.util.function.Supplier<io.tesseraql.core.events.TopicBus> topicBus;

    /**
     * One constructor, and the heartbeat is an argument rather than a setter: a transfer is
     * recorded as a job execution and read against the same liveness window a run is, so a wiring
     * site that could omit the pulse is a wiring site that can produce transfers the reaper calls
     * abandoned while they run. Custom calls in transfer SQL resolve against {@code functions}.
     */
    public JdbcFileTransferService(JobRepository jobs,
            io.tesseraql.operations.batch.ExecutionHeartbeats heartbeats, TempStore tempStore,
            DataSource dataSource, FileCodecs codecs,
            io.tesseraql.core.expr.ExpressionFunctions functions) {
        this.jobs = jobs;
        this.heartbeats = heartbeats;
        this.tempStore = tempStore;
        this.dataSource = dataSource;
        this.codecs = codecs;
        this.functions = functions;
    }

    /**
     * The query timeout every transfer statement runs under, in seconds; 0 leaves it unset.
     *
     * <p>There was none: an export query or an after-SQL statement ran for as long as the driver
     * allowed, holding a pooled connection, where the same statement on a route has been bounded
     * by {@code tesseraql.sql.timeoutSeconds} all along.
     */
    public JdbcFileTransferService sqlTimeoutSeconds(int seconds) {
        this.sqlTimeoutSeconds = Math.max(0, seconds);
        return this;
    }

    /**
     * How long a parked review batch may wait for its confirm (docs/csv-import.md decision 2).
     * Unlike produced export files, which expire only when an app opts in, a parked batch is
     * always swept: it holds business data the user never chose to store.
     */
    public JdbcFileTransferService reviewTtlMillis(long millis) {
        this.reviewTtlMillis = millis > 0 ? millis : DEFAULT_REVIEW_TTL_MILLIS;
        return this;
    }

    /**
     * The tracer each transfer phase spans through (docs/contract-sql-execution.md structural
     * decision 5): one {@code tesseraql.sql.execute} span per import, export, or inline
     * extraction — a span per row would be noise, and the phase is the unit an operator asks
     * about. Absent a tracer, spans are a no-op.
     */
    public JdbcFileTransferService tracer(io.tesseraql.core.telemetry.Tracer tracer) {
        this.tracer = tracer == null ? io.tesseraql.core.telemetry.NoopTracer.INSTANCE : tracer;
        return this;
    }

    /**
     * Where a finished import announces itself (docs/csv-import.md decision 6). A supplier
     * rather than the bus, because the bus is bound later in the boot than this service is
     * built — and only when the application declares topics at all, so it can stay absent.
     */
    public JdbcFileTransferService topicBus(
            java.util.function.Supplier<io.tesseraql.core.events.TopicBus> topicBus) {
        this.topicBus = topicBus;
        return this;
    }

    private io.tesseraql.core.telemetry.Span span(String mode, Object sqlId) {
        return tracer.start("tesseraql.sql.execute")
                .attribute("surface", "transfer")
                .attribute("mode", mode)
                .attribute("sqlId", String.valueOf(sqlId));
    }

    /** The datasource's dialect id, read once: asking costs a pooled connection. */
    private String dialect() {
        if (dialect == null) {
            dialect = io.tesseraql.core.util.DatabaseVendors.vendor(dataSource).orElse("");
        }
        return dialect;
    }

    /** Applies the configured query timeout, if any. */
    private void applyTimeout(PreparedStatement statement) throws SQLException {
        if (sqlTimeoutSeconds > 0) {
            statement.setQueryTimeout(sqlTimeoutSeconds);
        }
    }

    /**
     * Creates the transfer table if absent, from the bundled
     * {@code V1__framework_operations.sql} migration script.
     */
    public void ensureSchema() {
        try {
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource,
                    JdbcFileTransferService.class,
                    "/tesseraql/db/migration/operations/V1__framework_operations.sql");
            // Every version this store's own tables need must be listed, not just V1: Flyway
            // covers only the four bundled vendors, so H2 — `tesseraql dev`, the embedded-db
            // path, and much of the test surface — has nothing else. A version added to the
            // migration set and not here exists on PostgreSQL and does not exist on H2, and the
            // difference shows up as a missing table at the first upload.
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource,
                    JdbcFileTransferService.class,
                    "/tesseraql/db/migration/operations/V12__import_review_batch.sql");
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource,
                    JdbcFileTransferService.class,
                    "/tesseraql/db/migration/operations/V13__transfer_expected_rows.sql");
        } catch (SQLException ex) {
            throw new TqlException(TRANSFER_ERROR,
                    "Failed to create file transfer schema: " + ex.getMessage());
        }
    }

    @Override
    public String startImport(ImportRequest request, java.io.InputStream content) {
        FileCodec codec = codecs.require(request.format());
        // The upload spools off-heap before the request returns: imports of any size cost only
        // a copy buffer, and multipart parts (already on disk in Vert.x) move disk-to-disk.
        SpoolRef upload = spool(content);
        if (upload.bytes() == 0) {
            tempStore.delete(upload);
            throw new TqlException(EMPTY_UPLOAD,
                    "file-import expects the uploaded file as the request body");
        }
        return launchImport(request, codec, upload, null, null);
    }

    /**
     * Records the transfer and runs the import off the request thread. Shared by the one-shot
     * upload and by a confirmed review batch, because a commit <em>is</em> an ordinary import
     * (docs/csv-import.md decision 2) — the only difference is that a commit knows which rows the
     * review already refused, and refuses to write anything if the parse no longer agrees.
     */
    private String launchImport(ImportRequest request, FileCodec codec, SpoolRef upload,
            Set<Long> expectedRejects, Long expectedRows) {
        String transferId = jobs.startExecution(request.routeId(), request.appName(), "import",
                null);
        insertTransfer(transferId, request.routeId(), request.appName(), "IMPORT",
                request.format(), null, null, null, Map.of(), expectedRows);
        executor.submit(guarded(transferId, () -> {
            try {
                runImport(transferId, request, codec, upload, expectedRejects);
            } finally {
                tempStore.delete(upload);
            }
        }));
        return transferId;
    }

    private SpoolRef spool(java.io.InputStream content) {
        SpoolWriter writer = tempStore.createWriter(SpoolKind.BINARY);
        try (writer; content) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = content.read(buffer)) >= 0) {
                if (read > 0) {
                    byte[] chunk = new byte[read];
                    System.arraycopy(buffer, 0, chunk, 0, read);
                    writer.write(chunk);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        // toRef() is only valid after close, which the try-with-resources performed.
        return writer.toRef();
    }

    @Override
    public String startExport(ExportRequest request) {
        FileCodec codec = codecs.require(request.format());
        String filename = request.filename() != null && !request.filename().isBlank()
                ? request.filename()
                : request.routeId() + codec.extension();
        String transferId = jobs.startExecution(request.routeId(), request.appName(), "export",
                null);
        insertTransfer(transferId, request.routeId(), request.appName(), "EXPORT",
                request.format(), filename, request.afterTiming(),
                request.afterSqlFile() == null ? null : request.afterSqlFile().toString(),
                request.params());
        executor.submit(guarded(transferId, () -> runExport(transferId, request, codec, filename)));
        return transferId;
    }

    @Override
    public InlineResult exportInline(InlineExport request, javax.sql.DataSource extraction) {
        FileCodec codec = codecs.require(request.format());
        String filename = request.filename() != null && !request.filename().isBlank()
                ? request.filename()
                : request.routeId() + codec.extension();
        String transferId = jobs.startExecution(request.routeId(), request.appName(), "export",
                null);
        insertTransfer(transferId, request.routeId(), request.appName(), "EXPORT",
                request.format(), filename, request.afterExtract() == null
                        ? null
                        : AFTER_EXTRACT,
                null, Map.of());
        // The extraction runs on the caller's datasource, so its vendor decides both the label
        // normalization and the streaming profile — this service's own may be a different one.
        String extractionDialect = io.tesseraql.core.util.DatabaseVendors.vendor(extraction)
                .orElse(null);
        // The runExport shape, synchronous: extraction and follow-up commit together on the
        // caller's datasource; bookkeeping lands in the shared tables so the ops transfers
        // page and the download endpoint see a step-produced file like any other.
        //
        // Synchronous does not mean short. This opens its own pulse because it does not pass
        // through guarded(), and an inline export of a large extraction outlives the liveness
        // window as readily as an async one.
        try (ExecutionHeartbeats.Pulse _ = heartbeats.start(transferId);
                Connection connection = extraction.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            List<SpooledRows> spools = new ArrayList<>();
            try {
                Map<String, Object> values = renderedValues(connection, request.queries(),
                        effectiveCap(codec, request.writeSpec(), request.rowCap()), spools);
                long rows;
                SpoolWriter writer = tempStore.createWriter(SpoolKind.BINARY);
                try (writer;
                        PreparedStatement statement = prepareExtraction(connection,
                                request.query(), extractionDialect);
                        ResultSet results = statement.executeQuery();
                        OutputStream out = new io.tesseraql.core.spool.SpoolOutput(writer)) {
                    io.tesseraql.core.files.ResultSetRows iterator = new io.tesseraql.core.files.ResultSetRows(
                            results, extractionDialect,
                            effectiveCap(codec, request.writeSpec(), request.rowCap()),
                            TRANSFER_ERROR);
                    io.tesseraql.core.files.ExportWrite.write(codec, request.writeSpec(),
                            tempStore, iterator, request.enricher(), request.enrichWindow(),
                            values, filename, out);
                    rows = iterator.count();
                    writer.incrementRows(rows);
                }
                if (request.afterExtract() != null) {
                    executeUpdate(connection, request.afterExtract());
                }
                connection.commit();
                recordSpool(transferId, writer.toRef(), rows);
                jobs.completeExecution(transferId);
                return new InlineResult(transferId, filename, rows);
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
                // The named results outlive the codec's write and nothing else, so their spools
                // are this method's to reclaim.
                spools.forEach(SpooledRows::close);
            }
        } catch (Exception ex) {
            jobs.failExecution(transferId, ex.getMessage());
            throw new TqlException(TRANSFER_ERROR,
                    "Export step failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Optional<TransferStatus> status(String transferId) {
        // Read the execution status before the transfer detail: the run records its counts and
        // errors before completing, so a terminal status guarantees the detail row is final
        // (reading the other way round can observe COMPLETED with stale counts).
        String executionStatus = jobs.findExecution(transferId)
                .map(execution -> execution.status().name()).orElse("UNKNOWN");
        return findTransfer(transferId).map(transfer -> new TransferStatus(
                transferId, transfer.routeId(), transfer.appName(), transfer.direction(),
                executionStatus, transfer.rowCount(), transfer.expectedRows(),
                transfer.errors(), transfer.filename(), transfer.downloadedAt() != null));
    }

    /** The connected vendor (for label normalization and the row-limit clause), detected once. */
    private volatile String vendor;
    private volatile boolean vendorDetected;

    private String vendor() {
        if (!vendorDetected) {
            vendor = io.tesseraql.core.util.DatabaseVendors.vendor(dataSource).orElse(null);
            vendorDetected = true;
        }
        return vendor;
    }

    private String fetchClause() {
        return io.tesseraql.core.dialect.Pagination.fetchClause(vendor());
    }

    @Override
    public List<TransferSummary> recent(int limit) {
        List<TransferSummary> summaries = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        select t.*, e.status as execution_status
                        from tql_file_transfer t
                          left join tql_job_execution e on e.job_execution_id = t.transfer_id
                        order by t.created_at desc
                        """ + fetchClause())) {
            applyTimeout(statement);
            statement.setInt(1, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    summaries.add(new TransferSummary(
                            rs.getString("transfer_id"),
                            rs.getString("route_id"),
                            rs.getString("app_name"),
                            rs.getString("direction"),
                            rs.getString("format"),
                            rs.getString("execution_status") == null
                                    ? "UNKNOWN"
                                    : rs.getString("execution_status"),
                            rs.getLong("row_count"),
                            rs.getString("filename"),
                            rs.getTimestamp("downloaded_at") != null,
                            // A completed export with no spool left: retention reclaimed it.
                            "EXPORT".equals(rs.getString("direction"))
                                    && "COMPLETED".equals(rs.getString("execution_status"))
                                    && rs.getString("spool_uri") == null,
                            rs.getTimestamp("created_at").toInstant()));
                }
            }
        } catch (SQLException ex) {
            throw new TqlException(TRANSFER_ERROR,
                    "Failed to list file transfers: " + ex.getMessage());
        }
        return summaries;
    }

    @Override
    public Optional<Download> download(String transferId) {
        TransferRow transfer = findTransfer(transferId).orElse(null);
        if (transfer == null || !"EXPORT".equals(transfer.direction())
                || transfer.spoolUri() == null
                || !jobs.findExecution(transferId)
                        .map(execution -> "COMPLETED".equals(execution.status().name()))
                        .orElse(false)) {
            return Optional.empty();
        }
        if (claimFirstDownload(transferId)
                && AFTER_DOWNLOAD.equals(transfer.afterTiming())
                && transfer.afterSqlFile() != null) {
            runAfterSql(Path.of(transfer.afterSqlFile()), transfer.params());
        }
        try {
            FileCodec codec = codecs.require(transfer.format());
            SpoolRef ref = new SpoolRef(transferId, SpoolKind.BINARY,
                    URI.create(transfer.spoolUri()), 0, transfer.rowCount(), Instant.now());
            return Optional.of(new Download(
                    transfer.filename(), codec.contentType(), tempStore.openInput(ref)));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public int expireTransfersOlderThan(Instant cutoff) {
        List<String[]> due = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select transfer_id, spool_uri from tql_file_transfer"
                                + " where created_at < ? and spool_uri is not null")) {
            applyTimeout(statement);
            statement.setTimestamp(1, Timestamp.from(cutoff));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    due.add(new String[]{rs.getString(1), rs.getString(2)});
                }
            }
        } catch (SQLException ex) {
            throw new TqlException(TRANSFER_ERROR,
                    "Failed to list expirable transfers: " + ex.getMessage());
        }
        int expired = 0;
        for (String[] transfer : due) {
            try {
                tempStore.delete(new SpoolRef(transfer[0], SpoolKind.BINARY,
                        URI.create(transfer[1]), 0, 0, Instant.now()));
            } catch (RuntimeException ex) {
                // Delete what we can and keep going: a node-local file spool written on
                // another node is not ours to free, and one bad reference must not stall
                // the sweep. The row is cleared either way — the reference is dead.
                LOG.warn("Transfer {} spool {} not deleted here: {}", transfer[0], transfer[1],
                        ex.getMessage());
            }
            update("update tql_file_transfer set spool_uri = null where transfer_id = ?",
                    statement -> statement.setString(1, transfer[0]));
            expired++;
        }
        return expired;
    }

    /** Stops accepting work and lets running transfers finish. */
    public void close() {
        executor.shutdown();
    }

    /**
     * No failure may leave a transfer RUNNING forever: anything escaping fails the execution.
     *
     * <p>The pulse opens here, around the whole submitted body, because a transfer is an execution
     * and is read against the same liveness window a run is. It did not report at all, so any
     * import outliving that window was treated as abandoned — the file reported failed and moved
     * to {@code .error} while its rows committed anyway.
     */
    private Runnable guarded(String transferId, Runnable work) {
        return () -> {
            try (ExecutionHeartbeats.Pulse _ = heartbeats.start(transferId)) {
                work.run();
            } catch (Throwable ex) {
                LOG.warn("File transfer {} failed: {}", transferId, ex.toString());
                jobs.failExecution(transferId, ex.toString());
            }
        };
    }

    private void runImport(String transferId, ImportRequest request, FileCodec codec,
            SpoolRef upload, Set<Long> expectedRejects) {
        List<SqlNode> rowSql = parse(request.rowSqlFile());
        List<RowError> errors = new ArrayList<>();
        // The reported errors are capped; the rejection index is not. A commit that cannot say
        // which rows it excluded cannot claim to have applied exactly what was reviewed.
        //
        // Only PARSE rejections belong in it. A row the database refuses is a different fact
        // about a different pass, and folding the two together would make the agreement check
        // fire on the first unique-key clash — rolling back an `onError: skip` import that was
        // behaving exactly as declared, under a message blaming the file for having changed.
        Set<Long> parseRejected = new java.util.LinkedHashSet<>();
        long[] applied = {0};
        // The observation clock, shared by the progress flush and the stop poll: both ask a
        // question of the database, so both ask it on an interval rather than per row. One
        // clock, because they are the same boundary — "between rows, occasionally".
        long[] nextTick = {System.nanoTime() + PROGRESS_INTERVAL_NANOS};
        boolean[] stopping = {false};
        io.tesseraql.core.telemetry.Span span = span("import", request.rowSqlFile());
        try (Connection connection = dataSource.getConnection();
                java.io.InputStream content = tempStore.openInput(upload)) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                codec.read(content, request.readSpec(),
                        (rowNumber, values) -> {
                            // The cooperative stop (docs/csv-import.md decision 6): the job
                            // repository has held a cancel flag all along and the import loop
                            // never looked at it, so a Cancel button would have answered 200 and
                            // changed nothing. Checked here, between rows, where stopping is
                            // exact — and where the same tick publishes the row counter.
                            if (System.nanoTime() >= nextTick[0]) {
                                nextTick[0] = System.nanoTime() + PROGRESS_INTERVAL_NANOS;
                                recordProgress(transferId, applied[0]);
                                stopping[0] = stopping[0] || jobs.isCancelRequested(transferId);
                            }
                            if (stopping[0]) {
                                // The codec owns the loop, so the way out is to stop doing work:
                                // the remaining rows are read and dropped, and the transaction
                                // rolls back below. Reading on costs a parse per row and buys a
                                // shape that needs no exception to escape a third-party loop.
                                return;
                            }
                            // Typed columns (date/datetime/number) parse before binding, so bad
                            // values surface as row errors, not dialect cast failures. The parse
                            // sits outside the savepoint because it touches no database: a row
                            // the file already lost costs no transaction work, and keeping the
                            // two passes structurally apart is what lets the agreement check
                            // compare like with like.
                            Map<String, Object> typed;
                            try {
                                typed = io.tesseraql.core.files.ColumnValues
                                        .parseRow(request.readSpec(), values);
                                io.tesseraql.core.files.ColumnValueException violation = request
                                        .contract().firstViolation(typed);
                                if (violation != null) {
                                    throw violation;
                                }
                            } catch (RuntimeException ex) {
                                parseRejected.add(rowNumber);
                                record(errors, rowNumber, ex);
                                return;
                            }
                            Savepoint savepoint = connection.setSavepoint();
                            try {
                                applied[0] += executeUpdate(connection,
                                        SqlRenderer.render(rowSql, typed));
                                releaseQuietly(connection, savepoint);
                            } catch (SQLException | RuntimeException ex) {
                                connection.rollback(savepoint);
                                record(errors, rowNumber, ex);
                            }
                        });
                // The agreement check (docs/csv-import.md decision 2): re-parsing the parked
                // bytes under the parked spec must refuse exactly the rows the review refused.
                // It cannot differ — that is the point of freezing the spec — so a difference
                // means the bytes or the declaration moved, and writing part of a set nobody
                // reviewed is the one outcome worse than refusing.
                if (stopping[0]) {
                    // Nothing was written, and that is the strongest answer this shape can give:
                    // an import is one transaction with a savepoint per row, so a stop that
                    // arrives before the commit takes everything with it. A partial import
                    // nobody asked for would be the worse outcome.
                    connection.rollback();
                    recordRows(transferId, 0, errors);
                    jobs.stopExecution(transferId, "Import cancelled; nothing was written");
                    return;
                }
                if (expectedRejects != null && !parseRejected.equals(expectedRejects)) {
                    connection.rollback();
                    recordRows(transferId, 0, errors);
                    jobs.failExecution(transferId, BATCH_PARSE_MOVED
                            + ": the file no longer parses as it did when it was reviewed ("
                            + expectedRejects.size() + " row(s) were rejected then, "
                            + parseRejected.size() + " now); nothing was written");
                    return;
                }
                boolean rollbackAll = !errors.isEmpty()
                        && ON_ERROR_ROLLBACK.equals(request.onError());
                if (rollbackAll) {
                    connection.rollback();
                } else {
                    connection.commit();
                }
                recordRows(transferId, rollbackAll ? 0 : applied[0], errors);
                if (errors.isEmpty()) {
                    jobs.completeExecution(transferId);
                } else if (rollbackAll) {
                    jobs.failExecution(transferId, errors.size()
                            + " row(s) rejected; import rolled back");
                } else {
                    jobs.completeExecution(transferId);
                }
                // The completion signal (docs/csv-import.md decision 6). It fires here rather
                // than on the request that confirmed the import, because the request returns
                // before a single row is written: emitting there would tell every open page to
                // refetch the rows this run has not written yet. Rolled back means nothing
                // changed, so nothing is announced.
                if (!rollbackAll) {
                    emit(request);
                }
            } finally {
                connection.setAutoCommit(autoCommit);
            }
            span.attribute("affectedRows", applied[0]);
        } catch (Exception ex) {
            span.recordError(ex);
            LOG.warn("File import {} failed: {}", transferId, ex.getMessage());
            recordRows(transferId, 0, errors);
            jobs.failExecution(transferId, ex.getMessage());
        } finally {
            span.end();
        }
    }

    /**
     * Records one rejected row, bounded. The cap is on what is <em>reported</em>: the caller
     * keeps the complete rejection index separately, because the two answer different questions
     * — what to show the author, and what the commit is allowed to write.
     *
     * <p>The two passes produce different rejections and say so differently. A value the
     * declared type or the row contract refused knows which column and which text, and its
     * message is already the framework's. A failing per-row statement knows neither column nor
     * text, and its message used to be the driver's — SQL and, on several dialects, the
     * conflicting row's values. It becomes the sentence for the failure class, with the driver
     * text kept beside it for the operator (docs/csv-import.md decision 4).
     */
    private static void record(List<RowError> errors, long rowNumber, Exception ex) {
        if (errors.size() > MAX_RECORDED_ERRORS) {
            return;
        }
        if (errors.size() == MAX_RECORDED_ERRORS) {
            errors.add(RowError.of(rowNumber, "... further errors omitted"));
            return;
        }
        if (ex instanceof io.tesseraql.core.files.ColumnValueException bad) {
            // The complaint, not the composed sentence: the column and the value ride in their
            // own components, and a message that repeats them is a reason no report can group
            // on — the value is in it, so every bad row would be its own reason.
            errors.add(RowError.ofColumn(rowNumber, bad.column(), bad.value(),
                    bad.complaint()));
            return;
        }
        errors.add(new RowError(rowNumber, null, null,
                io.tesseraql.core.files.RowFailures.message(ex),
                io.tesseraql.core.files.RowFailures.detail(ex)));
    }

    // The reviewed upload (docs/csv-import.md)

    /** What a parse-only pass found: the report, and the complete set of rows it refused. */
    private record ParseOutcome(long rows, long rejectedCount, List<RowError> errors,
            Set<Long> rejected, String fileError) {
    }

    @Override
    public boolean cancel(String transferId) {
        return jobs.requestCancel(transferId);
    }

    /**
     * Announces a finished import on the route's declared topics. The bus is looked up rather
     * than injected because it is bound after this service is constructed, and only when the
     * application declares topics at all; without one this is a no-op, as it is for a command.
     */
    private void emit(ImportRequest request) {
        io.tesseraql.core.events.TopicBus bus = topicBus == null ? null : topicBus.get();
        if (bus == null || request.emit().isEmpty()) {
            return;
        }
        for (String topic : request.emit()) {
            bus.emit(request.tenantId(), topic);
        }
    }

    @Override
    public io.tesseraql.core.files.RowReference locate(String format,
            io.tesseraql.core.files.FileReadSpec spec, long row) {
        return codecs.require(format).locate(spec, row);
    }

    @Override
    public ImportReview reviewImport(ImportRequest request, String subject,
            java.io.InputStream content) {
        FileCodec codec = codecs.require(request.format());
        SpoolRef upload = spool(content);
        if (upload.bytes() == 0) {
            tempStore.delete(upload);
            throw new TqlException(EMPTY_UPLOAD,
                    "file-import expects the uploaded file as the request body");
        }
        // Everything from here either parks the bytes or drops them. Without the finally, a
        // failure between the spool and the row — a pool timeout, a lock wait, a constraint —
        // leaves bytes on disk that no row points at, and the sweep only walks rows.
        boolean parked = false;
        try {
            ParseOutcome outcome = parseOnly(codec, request.readSpec(), request.contract(),
                    upload);
            // A file the codec could not finish reading has no ready rows, whatever the counter
            // reached before it threw: saying "899 rows ready" beside a refusal would be the
            // status code and the report disagreeing, which is the thing this shape prevents.
            long ready = outcome.fileError() != null ? 0 : outcome.rows() - outcome.rejectedCount();
            // The affordance rule, in one expression (docs/csv-import.md decision 3): a
            // committable set is the clean rows under `skip`, and every row or none under
            // `rollback`. No set, no token, and the status code reads off the same fact.
            boolean committable = outcome.fileError() == null && ready > 0
                    && (outcome.rejectedCount() == 0 || ON_ERROR_SKIP.equals(request.onError()));
            if (!committable) {
                return new ImportReview(null, outcome.rows(), ready, outcome.rejectedCount(),
                        outcome.errors(), outcome.fileError(), null);
            }
            String batchId = UUID.randomUUID().toString().replace("-", "");
            Instant expiresAt = Instant.now().plusMillis(reviewTtlMillis);
            supersede(request.appName(), request.routeId(), subject);
            insertBatch(batchId, request, subject, upload, outcome, ready, expiresAt);
            parked = true;
            return new ImportReview(batchId, outcome.rows(), ready, outcome.rejectedCount(),
                    outcome.errors(), null, expiresAt);
        } finally {
            if (!parked) {
                releaseSpool(upload);
            }
        }
    }

    /**
     * The parse without the write. The loop is the codec's, unchanged - only the handler
     * differs, which is what makes "the commit is an ordinary import" true rather than
     * aspirational: one reader, two policies.
     */
    private ParseOutcome parseOnly(FileCodec codec, io.tesseraql.core.files.FileReadSpec spec,
            io.tesseraql.core.files.RowContract contract, SpoolRef upload) {
        List<RowError> errors = new ArrayList<>();
        Set<Long> rejected = new java.util.LinkedHashSet<>();
        long[] rows = {0};
        try (java.io.InputStream content = tempStore.openInput(upload)) {
            codec.read(content, spec, (rowNumber, values) -> {
                rows[0]++;
                try {
                    Map<String, Object> typed = io.tesseraql.core.files.ColumnValues
                            .parseRow(spec, values);
                    // The declared contract runs on the TYPED row, so `min: 1` compares numbers
                    // and not the text they arrived as.
                    io.tesseraql.core.files.ColumnValueException violation = contract
                            .firstViolation(typed);
                    if (violation != null) {
                        throw violation;
                    }
                } catch (RuntimeException ex) {
                    rejected.add(rowNumber);
                    record(errors, rowNumber, ex);
                }
            });
        } catch (Exception ex) {
            // A header that does not map, or an upload no codec can read, fails before any row
            // is examined. It used to end as a failed transfer with an empty error list - the
            // commonest real import failure, reported as nothing at all. Rows already refused
            // below a file-level failure are kept: they are true, and the author will want them
            // once the file itself is readable.
            return new ParseOutcome(rows[0], rejected.size(), errors, rejected, ex.getMessage());
        }
        return new ParseOutcome(rows[0], rejected.size(), errors, rejected, null);
    }

    /**
     * A refusal the confirming caller is meant to act on. The error envelope renders only the
     * code and a status phrase, so the sentence has to ride {@code details.message} — the
     * channel a thrower uses to declare text safe to show — or the caller reads "Conflict" and
     * learns nothing about which of the four ways to lose a token they hit.
     */
    private static TqlException refuseCommit(TqlErrorCode code, String message) {
        return TqlException.builder(code)
                .message(message)
                .details(Map.of("message", message))
                .build();
    }

    @Override
    public String commitImport(String batchId, String subject, ImportRequest request) {
        BatchRow batch = findBatch(batchId)
                .orElseThrow(() -> refuseCommit(BATCH_UNKNOWN,
                        "No import batch to commit; upload the file again"));
        if (!batch.appName().equals(request.appName())
                || !batch.routeId().equals(request.routeId())
                || !batch.subject().equals(subject)) {
            // Deliberately the same answer shape as an unknown batch: whose it is, is not
            // something a caller holding someone else's token gets to confirm.
            LOG.warn("Import batch {} confirmed by the wrong owner or route", batchId);
            throw refuseCommit(BATCH_FOREIGN,
                    "No import batch to commit; upload the file again");
        }
        if (batch.claimedAt() != null) {
            throw refuseCommit(BATCH_CLAIMED,
                    "This import was already committed; upload the file again to import it"
                            + " once more");
        }
        if (SUPERSEDED.equals(batch.status())) {
            // Not "already committed": a newer upload replaced it, and telling the author their
            // import ran when it did not is the one wrong sentence here.
            throw refuseCommit(BATCH_EXPIRED,
                    "A newer upload replaced this one; confirm that upload instead");
        }
        if (EXPIRED.equals(batch.status()) || batch.expiresAt() == null
                || batch.expiresAt().toInstant().isBefore(Instant.now())) {
            throw refuseCommit(BATCH_EXPIRED,
                    "The review window for this import has passed; upload the file again");
        }
        SpoolRef upload = batch.spool();
        // Read the bytes before claiming, not after. The spool may be unreachable from this node
        // — the default temp store is node-local, and a stack can serve the confirm from a
        // different member — and that has to be a refusal the caller sees, naming the store, not
        // a 202 followed by a transfer that fails somewhere they are not looking. Claiming first
        // would also burn the token on a commit that never ran.
        if (upload == null || !spoolReadable(upload)) {
            throw refuseCommit(BATCH_SPOOL_GONE,
                    "The reviewed file is not readable from this node (tesseraql.temp.store: "
                            + tempStore.getClass().getSimpleName()
                            + "); upload it again, or move the temp store off the node with"
                            + " tesseraql.temp.store: db");
        }
        // Claim before running: a crash after claiming leaves an import whose transfer says what
        // happened, while claiming after running would let a replay import twice.
        if (!claimBatch(batchId)) {
            throw refuseCommit(BATCH_CLAIMED,
                    "This import was already committed; upload the file again to import it"
                            + " once more");
        }
        FileCodec codec = codecs.require(request.format());
        // The route supplies what it declares - the per-row statement, the failure policy - and
        // the batch supplies the read spec, so the commit parses exactly what the review parsed
        // even though the locale expression would resolve against a different request.
        ImportRequest frozen = new ImportRequest(request.routeId(), request.appName(),
                request.format(), readSpec(batch.readSpecJson(), request.readSpec()),
                request.rowSqlFile(), request.onError(),
                contract(batch.contractJson(), request.contract()));
        String transferId = launchImport(frozen, codec, upload, batch.rejected(),
                batch.rowCount());
        linkTransfer(batchId, transferId);
        return transferId;
    }

    @Override
    public int expireReviewBatches(Instant cutoff) {
        int reclaimed = 0;
        // The row stays, without its bytes: that is what lets a late confirm be told the batch
        // expired rather than that it never existed.
        for (BatchSpool batch : parkedBatches(
                "select batch_id, spool_id, spool_uri from tql_import_batch"
                        + " where status = 'PARKED' and expires_at < ?",
                statement -> statement.setTimestamp(1, Timestamp.from(cutoff)),
                "Failed to list expired import batches")) {
            if (claim(batch.batchId(), EXPIRED)) {
                releaseSpool(batch.spool());
                reclaimed++;
            }
        }
        return reclaimed;
    }

    // tql_import_batch persistence

    private record BatchRow(String routeId, String appName, String subject, String status,
            String spoolId, String spoolUri, String readSpecJson, String contractJson,
            long rowCount, Set<Long> rejected, Timestamp claimedAt, Timestamp expiresAt) {

        SpoolRef spool() {
            return spoolOf(spoolId, spoolUri);
        }
    }

    /**
     * Drops a parked upload's bytes. Delete what we can and keep going: a node-local spool
     * written on another node is not ours to free, and one bad reference must not stall the
     * sweep — the same guard the transfer retention sweep carries, for the same reason.
     */
    private void releaseSpool(SpoolRef spool) {
        if (spool == null) {
            return;
        }
        try {
            tempStore.delete(spool);
        } catch (RuntimeException ex) {
            LOG.warn("Could not reclaim import spool {}: {}", spool.id(), ex.toString());
        }
    }

    /** Whether this node can actually read the parked bytes. */
    private boolean spoolReadable(SpoolRef spool) {
        try (java.io.InputStream probe = tempStore.openInput(spool)) {
            return probe.read() >= 0;
        } catch (IOException | RuntimeException ex) {
            LOG.warn("Parked import spool {} is unreadable here: {}", spool.id(), ex.toString());
            return false;
        }
    }

    /**
     * The reference a parked batch's bytes live under. Both halves are stored because the temp
     * stores disagree about which one addresses them: the file store resolves the URI, while the
     * database and blob stores look the id up as a key. Rebuilding the id from anything else
     * works on one store and silently fails on the others.
     */
    private static SpoolRef spoolOf(String spoolId, String spoolUri) {
        return spoolUri == null || spoolId == null
                ? null
                : new SpoolRef(spoolId, SpoolKind.BINARY, URI.create(spoolUri), 1, 0,
                        Instant.now());
    }

    private void insertBatch(String batchId, ImportRequest request, String subject,
            SpoolRef upload, ParseOutcome outcome, long ready, Instant expiresAt) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        insert into tql_import_batch
                          (batch_id, route_id, app_name, subject, format, spool_id, spool_uri,
                           read_spec_json, contract_json, report_json, row_count, ready_count,
                           rejected_count, status, expires_at, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PARKED', ?, ?)""")) {
            applyTimeout(statement);
            statement.setString(1, batchId);
            statement.setString(2, request.routeId());
            statement.setString(3, request.appName());
            statement.setString(4, subject);
            statement.setString(5, request.format());
            statement.setString(6, upload.id());
            statement.setString(7, upload.uri().toString());
            statement.setString(8, toJson(request.readSpec()));
            statement.setString(9, toJson(request.contract()));
            statement.setString(10, toJson(Map.of(
                    "errors", outcome.errors(), "rejected", outcome.rejected())));
            statement.setLong(11, outcome.rows());
            statement.setLong(12, ready);
            statement.setLong(13, outcome.rejectedCount());
            statement.setTimestamp(14, Timestamp.from(expiresAt));
            statement.setTimestamp(15, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new TqlException(TRANSFER_ERROR,
                    "Failed to park the import batch: " + ex.getMessage());
        }
    }

    /**
     * A new upload replaces this subject's earlier unclaimed batch for the same route
     * (docs/csv-import.md decision 2). Without it two tokens are live at once and the superseded
     * report stays committable - the author reviews one file and can still commit the other.
     */
    private void supersede(String appName, String routeId, String subject) {
        for (BatchSpool batch : parkedBatches(
                "select batch_id, spool_id, spool_uri from tql_import_batch where app_name = ?"
                        + " and route_id = ? and subject = ? and status = 'PARKED'",
                statement -> {
                    statement.setString(1, appName);
                    statement.setString(2, routeId);
                    statement.setString(3, subject);
                },
                "Failed to supersede the previous import batch")) {
            if (claim(batch.batchId(), SUPERSEDED)) {
                releaseSpool(batch.spool());
            }
        }
    }

    /** One parked batch's identity and bytes, for the two paths that reclaim them. */
    private record BatchSpool(String batchId, String spoolId, String spoolUri) {

        SpoolRef spool() {
            return spoolOf(spoolId, spoolUri);
        }
    }

    private List<BatchSpool> parkedBatches(String sql, SqlBindings binder, String failure) {
        List<BatchSpool> batches = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            applyTimeout(statement);
            binder.bind(statement);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    batches.add(new BatchSpool(rs.getString(1), rs.getString(2),
                            rs.getString(3)));
                }
            }
        } catch (SQLException ex) {
            throw new TqlException(TRANSFER_ERROR, failure + ": " + ex.getMessage());
        }
        return batches;
    }

    /**
     * Moves a parked batch to a terminal state, winner takes all. The transition happens BEFORE
     * the bytes go: a confirm that claimed the batch a moment earlier still holds it, and losing
     * this race must cost the loser nothing — deleting first would pull the file out from under
     * an import already running.
     */
    private boolean claim(String batchId, String status) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "update tql_import_batch set status = ?, spool_id = null,"
                                + " spool_uri = null where batch_id = ? and status = 'PARKED'")) {
            applyTimeout(statement);
            statement.setString(1, status);
            statement.setString(2, batchId);
            return statement.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new TqlException(TRANSFER_ERROR,
                    "Failed to retire the import batch: " + ex.getMessage());
        }
    }

    /** Atomically claims a parked batch for a confirm; true only for the winning caller. */
    private boolean claimBatch(String batchId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "update tql_import_batch set claimed_at = ?, status = '" + COMMITTED
                                + "' where batch_id = ? and claimed_at is null"
                                + " and status = '" + PARKED + "'")) {
            applyTimeout(statement);
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setString(2, batchId);
            return statement.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new TqlException(TRANSFER_ERROR,
                    "Failed to claim the import batch: " + ex.getMessage());
        }
    }

    private void linkTransfer(String batchId, String transferId) {
        update("update tql_import_batch set transfer_id = ? where batch_id = ?", statement -> {
            statement.setString(1, transferId);
            statement.setString(2, batchId);
        });
    }

    private Optional<BatchRow> findBatch(String batchId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select route_id, app_name, subject, status, spool_id, spool_uri,"
                                + " read_spec_json, contract_json, report_json, row_count,"
                                + " claimed_at, expires_at from tql_import_batch"
                                + " where batch_id = ?")) {
            applyTimeout(statement);
            statement.setString(1, batchId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new BatchRow(rs.getString("route_id"),
                        rs.getString("app_name"), rs.getString("subject"), rs.getString("status"),
                        rs.getString("spool_id"), rs.getString("spool_uri"),
                        rs.getString("read_spec_json"), rs.getString("contract_json"),
                        rs.getLong("row_count"), rejectedRows(rs.getString("report_json")),
                        rs.getTimestamp("claimed_at"), rs.getTimestamp("expires_at")));
            }
        } catch (SQLException ex) {
            throw new TqlException(TRANSFER_ERROR,
                    "Failed to read the import batch: " + ex.getMessage());
        }
    }

    private Set<Long> rejectedRows(String reportJson) {
        if (reportJson == null || reportJson.isBlank()) {
            return Set.of();
        }
        try {
            Map<String, Object> report = mapper.readValue(reportJson,
                    new TypeReference<Map<String, Object>>() {
                    });
            Set<Long> rows = new java.util.LinkedHashSet<>();
            if (report.get("rejected") instanceof List<?> stored) {
                stored.forEach(row -> rows.add(((Number) row).longValue()));
            }
            return rows;
        } catch (JsonProcessingException ex) {
            throw new TqlException(TRANSFER_ERROR,
                    "Failed to read the import batch report: " + ex.getMessage());
        }
    }

    /**
     * The contract the review held the rows to. Read back rather than rebuilt, because rebuilding
     * would re-resolve the code catalogs and a code retired since the upload would move the
     * rejection set — tripping the agreement check with a message blaming the file.
     */
    private io.tesseraql.core.files.RowContract contract(String json,
            io.tesseraql.core.files.RowContract fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return mapper.readValue(json, io.tesseraql.core.files.RowContract.class);
        } catch (JsonProcessingException ex) {
            throw new TqlException(TRANSFER_ERROR,
                    "Failed to read the parked row contract: " + ex.getMessage());
        }
    }

    private io.tesseraql.core.files.FileReadSpec readSpec(String json,
            io.tesseraql.core.files.FileReadSpec fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return mapper.readValue(json, io.tesseraql.core.files.FileReadSpec.class);
        } catch (JsonProcessingException ex) {
            throw new TqlException(TRANSFER_ERROR,
                    "Failed to read the parked read spec: " + ex.getMessage());
        }
    }

    private void runExport(String transferId, ExportRequest request, FileCodec codec,
            String filename) {
        List<SqlNode> query = parse(request.querySqlFile());
        io.tesseraql.core.telemetry.Span span = span("export", request.querySqlFile());
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            List<SpooledRows> spools = new ArrayList<>();
            try {
                BoundSql bound = SqlRenderer.render(query, request.params());
                Map<String, Object> values = composedValues(connection, request.queries(),
                        request.params(), request.values(),
                        effectiveCap(codec, request.writeSpec(), request.rowCap()), spools);
                long rows;
                SpoolWriter writer = tempStore.createWriter(SpoolKind.BINARY);
                try (writer;
                        PreparedStatement statement = prepareExtraction(connection, bound,
                                vendor());
                        ResultSet results = statement.executeQuery();
                        OutputStream out = new io.tesseraql.core.spool.SpoolOutput(writer)) {
                    io.tesseraql.core.files.ResultSetRows iterator = new io.tesseraql.core.files.ResultSetRows(
                            results, vendor(),
                            effectiveCap(codec, request.writeSpec(), request.rowCap()),
                            TRANSFER_ERROR);
                    io.tesseraql.core.files.ExportWrite.write(codec, request.writeSpec(),
                            tempStore, iterator, request.enricher(), request.enrichWindow(),
                            values, filename, out);
                    rows = iterator.count();
                    writer.incrementRows(rows);
                }
                if (AFTER_EXTRACT.equals(request.afterTiming())
                        && request.afterSqlFile() != null) {
                    executeUpdate(connection,
                            SqlRenderer.render(parse(request.afterSqlFile()), request.params()));
                }
                connection.commit();
                recordSpool(transferId, writer.toRef(), rows);
                jobs.completeExecution(transferId);
                span.attribute("rowCount", rows);
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
                // The named results outlive the codec's write and nothing else, so their spools
                // are this method's to reclaim.
                spools.forEach(SpooledRows::close);
            }
        } catch (Exception ex) {
            span.recordError(ex);
            LOG.warn("File export {} failed: {}", transferId, ex.getMessage());
            jobs.failExecution(transferId, ex.getMessage());
        } finally {
            span.end();
        }
    }

    /** Oracle and SQL Server have no RELEASE SAVEPOINT; the commit releases them anyway. */
    private static void releaseQuietly(Connection connection, Savepoint savepoint) {
        try {
            connection.releaseSavepoint(savepoint);
        } catch (SQLException unsupported) {
            // Best-effort hygiene only: accumulated savepoints die with the transaction.
        }
    }

    private void runAfterSql(Path afterSqlFile, Map<String, Object> params) {
        try (Connection connection = dataSource.getConnection()) {
            executeUpdate(connection, SqlRenderer.render(parse(afterSqlFile), params));
        } catch (SQLException ex) {
            throw new TqlException(TRANSFER_ERROR,
                    "Post-download statement failed: " + ex.getMessage());
        }
    }

    private int executeUpdate(Connection connection, BoundSql bound) throws SQLException {
        try (PreparedStatement statement = prepare(connection, bound)) {
            return statement.executeUpdate();
        }
    }

    /** The inline shape: a batch step pre-renders its named queries, so only execution is left. */
    private Map<String, Object> renderedValues(Connection connection,
            Map<String, BoundSql> queries, io.tesseraql.core.files.ExportRowCap cap,
            List<SpooledRows> spools) throws SQLException {
        if (queries.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, BoundSql> query : queries.entrySet()) {
            values.put(query.getKey(), execute(connection, query.getValue(), cap, spools));
        }
        return Map.copyOf(values);
    }

    /**
     * One named query's result, shaped like a read route's — {@code rows} plus {@code rowCount} —
     * and spooled like the extraction (docs/export-pipeline.md, decision 15). A second sheet of a
     * hundred thousand rows costs a spool rather than a heap, and it runs under the same ceiling:
     * a cap that bounds the subject and lets a named query run unbounded bounds nothing.
     */
    private Map<String, Object> execute(Connection connection, BoundSql bound,
            io.tesseraql.core.files.ExportRowCap cap,
            List<SpooledRows> spools) throws SQLException {
        try (PreparedStatement statement = prepare(connection, bound);
                ResultSet results = statement.executeQuery()) {
            return io.tesseraql.core.files.ExportWrite.namedResult(tempStore,
                    new io.tesseraql.core.files.ResultSetRows(results, vendor(), cap,
                            TRANSFER_ERROR),
                    spools);
        }
    }

    /**
     * Runs the export's named queries on the extraction connection, before the extraction and
     * inside its transaction (docs/export-pipeline.md, decision 2), and merges them over the
     * values the caller already resolved. A result is shaped like a read route's named query —
     * {@code rows} and {@code rowCount} — so a template reads one the way it reads the other.
     */
    private Map<String, Object> composedValues(Connection connection, List<ExportQuery> queries,
            Map<String, Object> params, Map<String, Object> resolved,
            io.tesseraql.core.files.ExportRowCap cap, List<SpooledRows> spools)
            throws SQLException {
        if (queries.isEmpty()) {
            return resolved;
        }
        Map<String, Object> values = new LinkedHashMap<>(resolved);
        for (ExportQuery query : queries) {
            values.put(query.name(), execute(connection,
                    SqlRenderer.render(parse(query.sqlFile()), params), cap, spools));
        }
        return Map.copyOf(values);
    }

    /**
     * The declared cap, but only where the codec holds the rows for this spec
     * (docs/export-pipeline.md, decisions 6 and 7). A streaming export accumulates nothing, so a
     * ceiling there would exist only to be raised.
     */
    private static io.tesseraql.core.files.ExportRowCap effectiveCap(FileCodec codec,
            FileWriteSpec writeSpec,
            io.tesseraql.core.files.ExportRowCap declared) {
        return io.tesseraql.core.files.ExportWrite.effectiveCap(codec, writeSpec, declared);
    }

    private PreparedStatement prepare(Connection connection, BoundSql bound)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(bound.sql());
        applyTimeout(statement);
        bind(statement, bound);
        return statement;
    }

    /**
     * The extraction statement of an export, prepared to stream (docs/export-pipeline.md,
     * decision 5): forward-only and read-only, with the dialect's fetch size so the driver opens
     * a cursor instead of reading the whole result into the client. Auto-commit is already off on
     * both export methods, which is PostgreSQL's other cursor condition.
     *
     * <p>Separate from {@link #prepare} because that one also prepares the {@code after:}
     * statement: MySQL streams with a fetch size of {@link Integer#MIN_VALUE}, which is not a
     * thing to hand an update.
     *
     * @param dialect the vendor of {@code connection}'s datasource, which is not always this
     *                service's own — an inline export runs on the caller's
     */
    private PreparedStatement prepareExtraction(Connection connection, BoundSql bound,
            String dialect) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(bound.sql(),
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        applyTimeout(statement);
        statement.setFetchSize(
                io.tesseraql.core.dialect.StreamingProfiles.forDialect(dialect).fetchSize());
        bind(statement, bound);
        return statement;
    }

    private static void bind(PreparedStatement statement, BoundSql bound) throws SQLException {
        List<BoundParameter> parameters = bound.parameters();
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, parameters.get(i).value());
        }
    }

    /**
     * Parses a transfer's SQL, taking the dialect variant beside it.
     *
     * <p>This executor read the declared file directly, so an {@code x.postgresql.sql} sitting
     * next to {@code x.sql} was never opened — the same gap the batch executor had, and for the
     * same reason: it resolves its own paths instead of going through the producer.
     */
    private List<SqlNode> parse(Path sqlFile) {
        try {
            Path resolved = io.tesseraql.core.dialect.DialectSqlResolver.resolve(sqlFile,
                    dialect());
            return Sql2WayParser.parse(Files.readString(resolved, StandardCharsets.UTF_8),
                    functions);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    // --- tql_file_transfer persistence ---

    private record TransferRow(String routeId, String appName, String direction, String format,
            String filename, String spoolUri, long rowCount, Long expectedRows,
            List<RowError> errors, String afterTiming, String afterSqlFile,
            Map<String, Object> params, Timestamp downloadedAt) {
    }

    private void insertTransfer(String transferId, String routeId, String appName,
            String direction, String format, String filename, String afterTiming,
            String afterSqlFile, Map<String, Object> params) {
        insertTransfer(transferId, routeId, appName, direction, format, filename, afterTiming,
                afterSqlFile, params, null);
    }

    /**
     * @param expectedRows how many rows this run will attempt, when that is known before it
     *                     starts — a reviewed import parsed the whole file already. Null
     *                     otherwise, and the progress card then counts up without a total
     *                     rather than showing a guessed one (docs/csv-import.md decision 6).
     */
    private void insertTransfer(String transferId, String routeId, String appName,
            String direction, String format, String filename, String afterTiming,
            String afterSqlFile, Map<String, Object> params, Long expectedRows) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        insert into tql_file_transfer
                          (transfer_id, route_id, app_name, direction, format, filename,
                           after_timing, after_sql_file, params_json, row_count, created_at,
                           expected_rows)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)""")) {
            applyTimeout(statement);
            statement.setString(1, transferId);
            statement.setString(2, routeId);
            statement.setString(3, appName);
            statement.setString(4, direction);
            statement.setString(5, format);
            statement.setString(6, filename);
            statement.setString(7, afterTiming);
            statement.setString(8, afterSqlFile);
            statement.setString(9, toJson(params));
            statement.setTimestamp(10, Timestamp.from(Instant.now()));
            if (expectedRows == null) {
                statement.setNull(11, java.sql.Types.BIGINT);
            } else {
                statement.setLong(11, expectedRows);
            }
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new TqlException(TRANSFER_ERROR,
                    "Failed to record file transfer: " + ex.getMessage());
        }
    }

    /**
     * Publishes how far the run has got, without its errors. The whole outcome — counts and
     * rejections together — is written once at the end, and a poller watching that saw
     * {@code RUNNING} with zero rows for the entire import and then the final number
     * (docs/csv-import.md decision 6). This is a counter, flushed on a clock rather than per
     * row: a write per row would cost more than the import.
     */
    private void recordProgress(String transferId, long rows) {
        update("update tql_file_transfer set row_count = ? where transfer_id = ?",
                statement -> {
                    statement.setLong(1, rows);
                    statement.setString(2, transferId);
                });
    }

    private void recordSpool(String transferId, SpoolRef ref, long rows) {
        update("update tql_file_transfer set spool_uri = ?, row_count = ? where transfer_id = ?",
                statement -> {
                    statement.setString(1, ref.uri().toString());
                    statement.setLong(2, rows);
                    statement.setString(3, transferId);
                });
    }

    private void recordRows(String transferId, long rows, List<RowError> errors) {
        update("update tql_file_transfer set row_count = ?, error_json = ? where transfer_id = ?",
                statement -> {
                    statement.setLong(1, rows);
                    statement.setString(2, toJson(errors));
                    statement.setString(3, transferId);
                });
    }

    /** Atomically marks the first download; true only for the winning call. */
    private boolean claimFirstDownload(String transferId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "update tql_file_transfer set downloaded_at = ?"
                                + " where transfer_id = ? and downloaded_at is null")) {
            applyTimeout(statement);
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setString(2, transferId);
            return statement.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new TqlException(TRANSFER_ERROR,
                    "Failed to mark download: " + ex.getMessage());
        }
    }

    private Optional<TransferRow> findTransfer(String transferId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select * from tql_file_transfer where transfer_id = ?")) {
            applyTimeout(statement);
            statement.setString(1, transferId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new TransferRow(
                        rs.getString("route_id"),
                        rs.getString("app_name"),
                        rs.getString("direction"),
                        rs.getString("format"),
                        rs.getString("filename"),
                        rs.getString("spool_uri"),
                        rs.getLong("row_count"),
                        expectedRows(rs),
                        fromJsonErrors(rs.getString("error_json")),
                        rs.getString("after_timing"),
                        rs.getString("after_sql_file"),
                        fromJsonParams(rs.getString("params_json")),
                        rs.getTimestamp("downloaded_at")));
            }
        } catch (SQLException ex) {
            throw new TqlException(TRANSFER_ERROR,
                    "Failed to read file transfer: " + ex.getMessage());
        }
    }

    /** The declared row total, or null — the column is nullable and {@code getLong} is not. */
    private static Long expectedRows(ResultSet rs) throws SQLException {
        long value = rs.getLong("expected_rows");
        return rs.wasNull() ? null : value;
    }

    private void update(String sql, SqlBindings bindings) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            applyTimeout(statement);
            bindings.bind(statement);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new TqlException(TRANSFER_ERROR,
                    "Failed to update file transfer: " + ex.getMessage());
        }
    }

    @FunctionalInterface
    private interface SqlBindings {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new TqlException(TRANSFER_ERROR, "Failed to serialize transfer detail");
        }
    }

    private List<RowError> fromJsonErrors(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<RowError>>() {
            });
        } catch (IOException ex) {
            return List.of();
        }
    }

    private Map<String, Object> fromJsonParams(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException ex) {
            return Map.of();
        }
    }
}
