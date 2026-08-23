package io.tesseraql.operations.batch;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.yaml.enrich.KeyedReference;
import io.tesseraql.yaml.model.PipelineStep;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code chunk:} step kind (docs/batch-platform.md track C): the reader streams its
 * keyset-ordered SELECT on one connection, the writer runs once per row on a second connection
 * committing every {@code commitEvery} handled rows, and each committed chunk checkpoints its
 * last handled key so a rerun for the same business date resumes where the failure stopped.
 *
 * <p>A writer failure on one row rolls back to a per-row savepoint, is recorded in
 * {@code tql_job_skips}, and processing continues — until {@code skipLimit} is exceeded,
 * which discards the uncommitted chunk and fails the step. Skipped rows advance the
 * checkpoint like processed ones: they were handled (recorded), not lost.
 *
 * <p>The three responsibilities are separated the way the failure modes are: this class plans
 * the run and reads it ({@link #pump}), {@link ChunkWriter} owns the writer connection —
 * its statement cache, savepoints, skip accounting, and the commit that carries the checkpoint —
 * and the cooperative stop is one flag that only a committed checkpoint may raise, so a
 * {@code STOPPED} result always names rows a rerun will not redo.
 */
final class ChunkStepRunner {

    /** Runs the step's chunk; a stopped chunk publishes {@code stopped} with its counts. */
    static Map<String, Object> run(StepContext context) {
        return new ChunkStepRunner(context).execute();
    }

    private final StepContext context;
    private final Map<String, Object> jobContext;
    private final PipelineStep step;
    private final io.tesseraql.yaml.model.ChunkSpec chunk;
    private final boolean spooled;
    private final String jobId;
    private final java.time.LocalDate businessDate;
    private final String dialect;
    private final Path writerPath;
    private final String readerId;
    private final BoundSql boundReader;
    private final List<io.tesseraql.core.sql.SqlNode> writerTemplate;
    private final List<KeyedReference> enrichments;

    /**
     * Plans the run in the order the executor always did: validate the declaration, read the
     * checkpoint this business date resumes from, publish {@code chunk.after} for the reader to
     * bind, then resolve and render both halves.
     */
    private ChunkStepRunner(StepContext context) {
        this.context = context;
        this.jobContext = context.context();
        this.step = context.step();
        this.chunk = step.chunk();
        this.spooled = chunk.reader() != null && chunk.reader().isSpool();
        if (chunk.reader() == null || (!spooled && chunk.reader().file() == null)
                || chunk.writer() == null || chunk.writer().file() == null) {
            throw TqlException.builder(StepContext.STEP_ERROR)
                    .message("Step '" + step.id() + "': chunk needs a reader (sql: with a file:,"
                            + " or spool: naming an earlier step's spool) and writer.file")
                    .build();
        }
        this.jobId = context.jobFile().definition().id();
        this.businessDate = ((java.sql.Date) ((Map<?, ?>) jobContext.get("batch"))
                .get("businessDate")).toLocalDate();
        String after = context.repository().findCheckpoint(jobId, step.id(), businessDate)
                .orElse(null);
        Map<String, Object> chunkContext = new LinkedHashMap<>();
        chunkContext.put("after", after);
        jobContext.put("chunk", chunkContext);

        this.dialect = context.dialectOf(context.dataSource());
        Path readerPath = spooled
                ? null
                : context.sqlPath(chunk.reader().file(), context.dataSource());
        this.writerPath = context.sqlPath(chunk.writer().file(), context.dataSource());
        this.readerId = spooled ? "spool:" + chunk.reader().spool() : readerPath.toString();
        this.boundReader = spooled
                ? null
                : SqlRenderer.render(
                        io.tesseraql.core.sql.Sql2WayParser.parse(StepContext.read(readerPath),
                                context.functions()),
                        context.resolveParams(chunk.reader()),
                        io.tesseraql.core.sql.ScopeResolver.UNSUPPORTED, jobContext,
                        io.tesseraql.core.sql.FilePathResolver.UNSUPPORTED);
        this.writerTemplate = io.tesseraql.core.sql.Sql2WayParser
                .parse(StepContext.read(writerPath), context.functions());
        this.enrichments = context.enrichments(chunk.enrich(), dialect,
                context.timeoutSecondsFor(chunk.reader()));
    }

    /**
     * Opens the two connections, streams the chunk through them, and publishes the counts.
     *
     * <p>A spooled reader takes no connection at all: its rows were read once, on the step
     * that produced them, possibly on another datasource entirely.
     */
    private Map<String, Object> execute() {
        io.tesseraql.core.telemetry.Span span = context.tracer()
                .start("tesseraql.sql.execute", context.parentSpan())
                .attribute("surface", "chunk")
                .attribute("sqlId", readerId)
                .attribute("mode", "chunk")
                .attribute("stepId", step.id());
        long startedAt = System.currentTimeMillis();
        long startNanos = System.nanoTime();
        try (Connection reader = spooled ? null : context.dataSource().getConnection();
                Connection writer = context.dataSource().getConnection()) {
            // A held cursor needs its own transaction (PostgreSQL only streams with autocommit
            // off), and the writer's commit cadence is the whole point of the chunk.
            if (reader != null) {
                reader.setAutoCommit(false);
            }
            writer.setAutoCommit(false);
            ChunkWriter sink = new ChunkWriter(writer);
            Map<String, Object> stopped;
            try {
                stopped = drain(reader, sink);
            } finally {
                sink.close();
                jobContext.remove("row");
            }
            if (stopped != null) {
                return stopped;
            }
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            span.attribute("affectedRows", (long) sink.processed);
            span.attribute("skippedRows", (long) sink.skipped);
            context.slowSqlLog().record(new io.tesseraql.core.diag.SqlExecution(
                    readerId, "chunk", durationMs, sink.processed, startedAt));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("affectedRows", sink.processed);
            result.put("skipped", sink.skipped);
            return result;
        } catch (SQLException ex) {
            // Structural decision 8: the wrapper keeps its code and gains the classification.
            TqlException failure = TqlException.builder(StepContext.STEP_ERROR)
                    .message("Step '" + step.id() + "' failed ("
                            + io.tesseraql.core.dialect.SqlErrors.classify(ex) + "): "
                            + ex.getMessage())
                    .source(readerId)
                    .cause(ex)
                    .build();
            span.recordError(failure);
            throw failure;
        } catch (TqlException ex) {
            span.recordError(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    /**
     * Streams the reader through the writer and settles the transaction: the stopped result
     * when an operator asked for one, else {@code null} for the completed counts above.
     */
    private Map<String, Object> drain(Connection reader, ChunkWriter sink) throws SQLException {
        if (spooled) {
            try (ChunkRows rows = ChunkRows.of(context.tempStore(), spoolRef(),
                    context.mapper())) {
                pump(rows, sink);
            }
            return sink.finish();
        }
        // The statement layer under the chunk's own phase span (docs/sql-execution-shapes.md
        // structural decision 3): the chunk spans per phase, so the primitive is handed no
        // tracer here — the bound, the classification and the streaming forward-only prepare
        // at the commit cadence's fetch size still apply.
        io.tesseraql.core.sql.SqlStatement readerStatements = io.tesseraql.core.sql.SqlStatement
                .onCallerConnections()
                .timeoutSeconds(context.timeoutSecondsFor(chunk.reader()))
                .fetchSize(Math.max(100, Math.min(chunk.effectiveCommitEvery(), 1000)));
        return readerStatements.read(reader, readerId, boundReader, (resultSet, span) -> {
            try (ChunkRows rows = ChunkRows.of(resultSet, dialect)) {
                pump(rows, sink);
            }
            return sink.finish();
        });
    }

    /**
     * Reads the reader a window at a time so an enrichment can fold its reference in before the
     * writer sees a row (docs/lookups.md, slice 14). With no enrichment the window is one row
     * and the loop is what it was.
     *
     * <p>A reference failure fails the WINDOW, and the step with it. It is not one row's fault,
     * so it must never be recorded as a skip: {@code tql_job_skips} is the record of rows the
     * writer rejected, and an operator reading it has to be able to trust that.
     */
    private void pump(ChunkRows rows, ChunkWriter sink) throws SQLException {
        int window = KeyedReference.window(enrichments, KeyedReference::batchSize, 1);
        List<Map<String, Object>> buffered = new java.util.ArrayList<>(window);
        boolean more = rows.next();
        while (more && !sink.stopped) {
            buffered.clear();
            while (buffered.size() < window && more) {
                buffered.add(rows.row());
                more = rows.next();
            }
            sink.writeWindow(
                    context.enrichWindow(enrichments, context.dataSource(), buffered));
        }
    }

    /**
     * The spool an earlier step published, named by a context path (decision 19). A rerun with
     * {@code --from-failed-step} hands the prior spool over unchanged, which is what makes the
     * cross-datasource copy restartable rather than merely repeatable.
     */
    private io.tesseraql.core.spool.SpoolRef spoolRef() {
        Object resolved = new EvaluationContext(jobContext)
                .resolve(java.util.Arrays.asList(chunk.reader().spool().split("\\.")));
        if (resolved instanceof io.tesseraql.core.spool.SpoolRef ref) {
            return ref;
        }
        throw TqlException.builder(StepContext.STEP_ERROR)
                .message("Step '" + step.id() + "': reader spool: '" + chunk.reader().spool()
                        + "' does not resolve to a spool — name an earlier step's spool, which"
                        + " only a mode: query-spool step publishes")
                .build();
    }

    /**
     * The checkpoint key of one reader row; a reader that never selects it is misdeclared.
     *
     * <p>One key, exactly: reader rows carry {@link io.tesseraql.core.dialect.ResultRows}
     * -normalized labels now, so {@code chunk.key} names the same label a writer bind does and
     * the tri-case probing that papered over raw Oracle labels is gone.
     */
    private static Object keyOf(Map<String, Object> row, String key, String stepId,
            String readerId) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        throw TqlException.builder(StepContext.STEP_ERROR)
                .message("Step '" + stepId + "': the reader's rows carry no '" + key
                        + "' column — chunk.key must name a column the reader produces")
                .source(readerId)
                .build();
    }

    /**
     * The writing half of a chunk: one connection, the primitive's reusable writer handle (one
     * prepared statement per rendered-SQL variant, cached — docs/sql-execution-shapes.md
     * structural decision 5), and the commit cadence that carries the checkpoint.
     *
     * <p>Every counter the step publishes lives here because every one of them is a fact about
     * what the writer did — processed rows, skipped rows, and the stop that a commit is allowed
     * to raise.
     */
    private final class ChunkWriter {

        private final Connection connection;
        private final io.tesseraql.core.sql.SqlStatement.Rows rows;
        private String lastKey;
        private int sinceCommit;
        private int processed;
        private int skipped;
        private boolean stopped;

        ChunkWriter(Connection connection) {
            this.connection = connection;
            // The phase policy again: the chunk's span is the span, so no tracer rides the
            // handle — its per-flush spans are for surfaces that trace per statement.
            this.rows = io.tesseraql.core.sql.SqlStatement.onCallerConnections()
                    .timeoutSeconds(context.timeoutSecondsFor(chunk.writer()))
                    .rows(connection, writerPath.toString());
        }

        /** Writes one enriched window, row by row, committing whenever the cadence says so. */
        void writeWindow(List<Map<String, Object>> window) throws SQLException {
            for (Map<String, Object> row : window) {
                Object keyValue = keyOf(row, chunk.effectiveKey(), step.id(), readerId);
                jobContext.put("row", row);
                write(keyValue);
                lastKey = String.valueOf(keyValue);
                sinceCommit++;
                if (commitIfDue()) {
                    break;
                }
            }
        }

        /**
         * One row through the writer — behind a savepoint so its failure keeps the chunk, or
         * queued into the handle's JDBC batch when the chunk declares {@code batch: true}
         * (docs/sql-execution-shapes.md structural decision 6): one round trip per committed
         * slice, a member failure failing the chunk at flush, which reruns from its last
         * checkpoint.
         */
        private void write(Object keyValue) throws SQLException {
            BoundSql boundWriter = SqlRenderer.render(writerTemplate,
                    context.resolveParams(chunk.writer()),
                    io.tesseraql.core.sql.ScopeResolver.UNSUPPORTED, jobContext,
                    io.tesseraql.core.sql.FilePathResolver.UNSUPPORTED);
            if (chunk.batches()) {
                rows.add(boundWriter);
                processed++;
                return;
            }
            Savepoint beforeRow = connection.setSavepoint();
            try {
                rows.execute(boundWriter);
                processed++;
            } catch (SQLException rowFailure) {
                recordSkip(beforeRow, keyValue, rowFailure);
            }
        }

        /**
         * Records one rejected row and keeps going, until {@code skipLimit} is exceeded — which
         * discards the uncommitted chunk and fails the step.
         */
        private void recordSkip(Savepoint beforeRow, Object keyValue, SQLException rowFailure)
                throws SQLException {
            // The failed statement may have poisoned the transaction (design
            // stance: PostgreSQL aborts it) — the savepoint keeps the chunk.
            connection.rollback(beforeRow);
            skipped++;
            context.repository().recordSkip(context.executionId(), step.id(),
                    String.valueOf(keyValue), rowFailure.getMessage());
            if (skipped > chunk.effectiveSkipLimit()) {
                connection.rollback();
                throw TqlException.builder(StepContext.STEP_ERROR)
                        .message("Step '" + step.id() + "' exceeded skipLimit "
                                + chunk.effectiveSkipLimit() + " (row "
                                + keyValue + ": " + rowFailure.getMessage()
                                + ")")
                        .source(writerPath.toString())
                        .cause(rowFailure)
                        .build();
            }
        }

        /**
         * Commits the chunk when {@code commitEvery} rows have been handled, checkpoints the
         * last handled key, and polls the cooperative stop; true when this commit is where the
         * step stops.
         *
         * <p>The stop lands exactly on a committed checkpoint: nothing is lost, and a rerun
         * resumes here.
         */
        private boolean commitIfDue() throws SQLException {
            if (sinceCommit < chunk.effectiveCommitEvery()) {
                return false;
            }
            rows.flush();
            connection.commit();
            context.repository().saveCheckpoint(jobId, step.id(), businessDate, lastKey);
            sinceCommit = 0;
            stopped = context.repository().isCancelRequested(context.executionId());
            return stopped;
        }

        /**
         * Settles the transaction once the reader is drained (or the stop was raised): the
         * stopped result with its committed counts, else {@code null} after the final commit
         * cleared the checkpoint.
         */
        Map<String, Object> finish() throws SQLException {
            if (stopped) {
                connection.rollback(); // nothing pending — the stop happened on a commit
            } else {
                rows.flush();
                connection.commit();
                context.repository().clearCheckpoint(jobId, step.id(), businessDate);
            }
            if (!stopped) {
                return null;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("affectedRows", processed);
            result.put("skipped", skipped);
            result.put("stopped", true);
            return result;
        }

        /**
         * Discards whatever the abort path left queued — the transaction those rows belonged
         * to is rolling back, so dropping them is the rollback, not a silent loss (the happy
         * paths flushed before their commits) — then closes the handle's statements.
         */
        void close() {
            try {
                rows.discard();
                rows.close();
            } catch (SQLException | RuntimeException ignored) {
                // closing the pooled connection reclaims the statements regardless
            }
        }
    }
}
