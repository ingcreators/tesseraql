package io.tesseraql.operations.batch;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.spool.SpoolRef;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.yaml.model.PipelineStep;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The plain {@code sql:} step kind: one statement on one connection, in the mode the step
 * declares — a write ({@code affectedRows}), a materializing read ({@code mode: query}), or a
 * spooled read ({@code mode: query-spool}).
 */
final class SqlStepRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SqlStepRunner.class);
    /** TQL-LD-0001: a query step's result exceeded the row cap — the same code routes raise. */
    private static final TqlErrorCode MATERIALIZATION_OVERFLOW = new TqlErrorCode(TqlDomain.LD, 1);

    private SqlStepRunner() {
    }

    static Map<String, Object> run(StepContext context) {
        PipelineStep step = context.step();
        DataSource dataSource = context.stepDataSource();
        // The dialect variant beside the file, the way every other executor picks it: a step
        // declaring `x.sql` with an `x.postgresql.sql` next to it ran the generic one, silently,
        // because this executor resolved the path itself and never asked.
        String dialect = context.dialectOf(dataSource);
        Path sqlPath = context.sqlPath(step.sql().file(), dataSource);
        String source = StepContext.read(sqlPath);
        Map<String, Object> sqlParams = context.resolveParams(step.sql());
        // File placeholders (docs/duckdb.md) resolve against the job's datasource; the job
        // context doubles as the resolver context, so a perTenant run's tenant partitions scopes.
        io.tesseraql.core.sql.FilePathResolver filePathResolver = context.filePathResolver();
        BoundSql bound = SqlRenderer.render(
                io.tesseraql.core.sql.Sql2WayParser.parse(source, context.functions()),
                sqlParams, io.tesseraql.core.sql.ScopeResolver.UNSUPPORTED, context.context(),
                filePathResolver);
        String mode = step.sql().effectiveMode();

        // The statement layer (docs/sql-execution-shapes.md slice 1): prepare, bind, bound,
        // execute-and-count, classify and the span are the primitive's; this runner keeps what
        // is the step's — its result envelope, the caps, the spool drain, and the TQL-BATCH-5002
        // wrapper. The step's identity rides the span as a declared attribute. The result set
        // of a mode: update is deliberately not read: a step that wants rows asks with
        // mode: query.
        io.tesseraql.core.sql.SqlStatement statements = io.tesseraql.core.sql.SqlStatement
                .on(dataSource)
                .dialect(dialect)
                .timeoutSeconds(context.timeoutSecondsFor(step.sql()))
                .surface("job")
                .attribute("stepId", step.id())
                .tracer(context.tracer())
                .spanParent(context.parentSpan());
        long startNanos = System.nanoTime();
        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> result = switch (mode) {
                case "query-spool" -> spoolStreaming(context, statements, sqlPath, bound,
                        dialect);
                case "query" -> statements.read(sqlPath.toString(), bound,
                        (resultSet, span) -> query(context, resultSet, dialect, span));
                default -> Map.of("affectedRows",
                        statements.update(sqlPath.toString(), bound));
            };
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            long rows = ((Number) result.getOrDefault("affectedRows",
                    result.getOrDefault("rowCount", 0))).longValue();
            context.slowSqlLog().record(new io.tesseraql.core.diag.SqlExecution(
                    sqlPath.toString(), mode, durationMs, rows, startedAt));
            return result;
        } catch (SQLException ex) {
            // The wrapper keeps its code and gains the portable classification (structural
            // decision 8, docs/contract-sql-execution.md): a foreign-key violation from bad
            // input and a dropped table stopped reporting identically. The statement's own
            // span already recorded the classified failure.
            throw TqlException.builder(StepContext.STEP_ERROR)
                    .message("Step '" + step.id() + "' failed ("
                            + io.tesseraql.core.dialect.SqlErrors.classify(ex) + "): "
                            + ex.getMessage())
                    .source(sqlPath.toString())
                    .cause(ex)
                    .build();
        }
    }

    /**
     * A {@code mode: query} step publishes the envelope every read publishes —
     * {@code rows} / {@code rowCount} / {@code first} — bounded by the step's
     * {@code materialize.maxRows} or the job's default (docs/unified-sources.md decision 18).
     *
     * <p>It used to drain the {@code ResultSet} into a count and discard the rows, which made
     * "fetch a control value, bind it into later steps" inexpressible for a reason no reader of
     * the document could see: the step existed, its result did not. Counting was memory
     * protection; the bound keeps that protection while the rows become usable, and an extract
     * too large to hold is what {@code query-spool} is for.
     */
    private static Map<String, Object> query(StepContext context, ResultSet rs, String dialect,
            io.tesseraql.core.telemetry.Span span) throws SQLException {
        PipelineStep step = context.step();
        int cap = step.sql().materialize() != null && step.sql().materialize().maxRows() != null
                ? step.sql().materialize().maxRows()
                : context.maxRows();
        String overflow = step.sql().materialize() != null
                && step.sql().materialize().onOverflow() != null
                        ? step.sql().materialize().onOverflow()
                        : context.onOverflow();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        ResultSetMetaData metaData = rs.getMetaData();
        int columns = metaData.getColumnCount();
        while (rs.next()) {
            if (cap >= 0 && rows.size() >= cap) {
                if (!"warn".equals(overflow)) {
                    throw TqlException.builder(MATERIALIZATION_OVERFLOW)
                            .message("Step '" + step.id() + "': query returned more than "
                                    + cap + " rows; raise materialize.maxRows, or use"
                                    + " mode: query-spool for an extract too large to hold")
                            .build();
                }
                LOG.warn("Job step {} query exceeded {} rows; truncating", step.id(), cap);
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int col = 1; col <= columns; col++) {
                // The one label answer every JDBC path asks (ResultRows): the same
                // `select … as total` publishes `total` on Oracle and PostgreSQL alike.
                // Values stay typed — a later step binds them, and an ISO string is not a
                // timestamp (docs/sql-execution-shapes.md structural decision 1).
                row.put(io.tesseraql.core.dialect.ResultRows.label(dialect,
                        metaData.getColumnLabel(col)), rs.getObject(col));
            }
            rows.add(row);
        }
        span.attribute("rowCount", rows.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("rowCount", rows.size());
        result.put("first", rows.isEmpty() ? null : rows.get(0));
        return result;
    }

    /**
     * A {@code query-spool} extraction streams. The dialect's profile picks the fetch size and
     * {@code transact} supplies the autocommit-off bracket PostgreSQL's cursor mode demands —
     * the own-connection read ran with autocommit on and no fetch size, so the driver buffered
     * the whole result before the first row reached the spool, defeating the mode's purpose
     * ("the rows were never held"). A read-only transaction's commit is a no-op on the
     * dialects that need no bracket.
     */
    private static Map<String, Object> spoolStreaming(StepContext context,
            io.tesseraql.core.sql.SqlStatement statements, Path sqlPath, BoundSql bound,
            String dialect) throws SQLException {
        io.tesseraql.core.dialect.StreamingProfile profile = io.tesseraql.core.dialect.StreamingProfiles
                .forDialect(dialect);
        io.tesseraql.core.sql.SqlStatement streaming = statements
                .fetchSize(profile.fetchSize());
        return streaming.transact(sqlPath.toString(),
                connection -> streaming.read(connection, sqlPath.toString(), bound,
                        (resultSet, span) -> spool(context, resultSet, dialect, span)));
    }

    /**
     * Streams the result set to a tagged-binary {@link io.tesseraql.core.files.SpooledRows}
     * spool, exposing the {@link SpoolRef} to later steps (ch. 28.6).
     *
     * <p>The encoding is the export pipeline's, for the export pipeline's reason: a JSON round
     * trip is lossy exactly where a SQL extract cares — a decimal's scale, a temporal's type —
     * and a chunk writer binds what comes back. Labels are normalized through
     * {@link io.tesseraql.core.dialect.ResultRows}, so the spooled keys are the ones a writer
     * bind or {@code chunk.key} names on every dialect. HTTP-sourced rows keep JSONL
     * ({@link HttpStepRunner}): that data was JSON, so JSON is faithful there.
     */
    private static Map<String, Object> spool(StepContext context, ResultSet rs, String dialect,
            io.tesseraql.core.telemetry.Span span) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columns = metaData.getColumnCount();
        SpoolRef ref;
        try {
            ref = io.tesseraql.core.files.SpooledRows
                    .drain(context.tempStore(), new java.util.Iterator<Map<String, Object>>() {

                        private Boolean advanced;

                        @Override
                        public boolean hasNext() {
                            if (advanced == null) {
                                try {
                                    advanced = rs.next();
                                } catch (SQLException ex) {
                                    throw new UncheckedSqlException(ex);
                                }
                            }
                            return advanced;
                        }

                        @Override
                        public Map<String, Object> next() {
                            if (!hasNext()) {
                                throw new java.util.NoSuchElementException();
                            }
                            advanced = null;
                            try {
                                Map<String, Object> row = new LinkedHashMap<>();
                                for (int col = 1; col <= columns; col++) {
                                    row.put(io.tesseraql.core.dialect.ResultRows.label(
                                            dialect, metaData.getColumnLabel(col)),
                                            rs.getObject(col));
                                }
                                return row;
                            } catch (SQLException ex) {
                                throw new UncheckedSqlException(ex);
                            }
                        }
                    })
                    .ref();
        } catch (UncheckedSqlException ex) {
            throw ex.cause;
        }
        span.attribute("rowCount", (int) ref.rows());
        Map<String, Object> result = new LinkedHashMap<>();
        // A spool has a count and a reference, and no `rows` — the point of spooling is that
        // the rows were never held. `rows` means a list on every other surface (decision 10),
        // so publishing the count under that name was the envelope contradicting itself.
        result.put("rowCount", (int) ref.rows());
        result.put("spool", ref);
        return result;
    }

    /** Carries a {@link SQLException} across the drain's iterator boundary, unwrapped above. */
    private static final class UncheckedSqlException extends RuntimeException {

        private final SQLException cause;

        UncheckedSqlException(SQLException cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
