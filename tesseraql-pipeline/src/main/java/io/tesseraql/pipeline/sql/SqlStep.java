package io.tesseraql.pipeline.sql;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.FileCodec;
import io.tesseraql.core.files.FileWriteSpec;
import io.tesseraql.core.files.SpooledRows;
import io.tesseraql.core.spool.FileTempStore;
import io.tesseraql.core.spool.SpoolKind;
import io.tesseraql.core.spool.SpoolRef;
import io.tesseraql.core.spool.SpoolWriter;
import io.tesseraql.core.spool.TempStore;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.ScopeResolver;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.pipeline.tenant.TenantRouting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Executes a 2-way SQL file against JDBC and publishes the result into the execution context
 * (design ch. 9.1). The SQL is parsed once at startup and rendered per exchange with the resolved
 * bind parameters held in the {@link TesseraqlProperties#SQL_PARAMS} property.
 */
public class SqlStep implements Step {

    /**
     * The document's primary source. Declarative pagination is main-bound
     * (docs/unified-sources.md, out of scope), so only the query publishing under this name
     * executes with the dialect's page clause appended.
     */
    private static final String MAIN = io.tesseraql.core.files.ExportModel.SUBJECT;

    /** TQL-SQL-2500: the SQL failed to execute for a reason beyond the portable constraint kinds. */
    private static final TqlErrorCode EXECUTION_ERROR = new TqlErrorCode(TqlDomain.SQL, 2500);
    private static final TqlErrorCode UNSUPPORTED_MODE = new TqlErrorCode(TqlDomain.SQL, 2501);
    // Portable constraint-violation codes, mapped to HTTP statuses by ErrorResponseRenderer.
    private static final TqlErrorCode UNIQUE_VIOLATION_CODE = new TqlErrorCode(TqlDomain.SQL, 4090);
    private static final TqlErrorCode FOREIGN_KEY_VIOLATION_CODE = new TqlErrorCode(TqlDomain.SQL,
            4091);
    private static final TqlErrorCode NOT_NULL_VIOLATION_CODE = new TqlErrorCode(TqlDomain.SQL,
            4001);
    private static final TqlErrorCode CHECK_VIOLATION_CODE = new TqlErrorCode(TqlDomain.SQL, 4002);
    /** TQL-SQL-4093: a serialization failure or deadlock; the write may succeed if retried (HTTP 409). */
    private static final TqlErrorCode SERIALIZATION_CODE = new TqlErrorCode(TqlDomain.SQL, 4093);
    /** TQL-LD-0001: result materialization exceeded the configured maxRows. */
    private static final TqlErrorCode MATERIALIZATION_OVERFLOW = new TqlErrorCode(TqlDomain.LD, 1);
    private static final System.Logger LOG = System.getLogger(SqlStep.class.getName());

    private final Map<Path, List<SqlNode>> exportQueryNodes = new java.util.concurrent.ConcurrentHashMap<>();
    private List<SqlNode> nodes;

    private final String sqlPath;
    private final String datasource;
    private final String mode;
    private final String resultKey;
    private final int maxRows;
    private final int queryTimeoutSeconds;
    private final String onOverflow;
    private final String filename;
    private final String dialect;

    /** One SQL execution, with the settings its endpoint URI used to carry. */
    public SqlStep(String sqlPath, String datasource, String mode, String resultKey, int maxRows,
            int queryTimeoutSeconds, String onOverflow, String filename, String dialect) {
        this.sqlPath = sqlPath;
        this.datasource = datasource == null ? "main" : datasource;
        this.mode = mode == null ? "query" : mode;
        this.resultKey = resultKey == null ? "main" : resultKey;
        this.maxRows = maxRows;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.onOverflow = onOverflow == null ? "fail" : onOverflow;
        this.filename = filename == null ? "export.csv" : filename;
        this.dialect = dialect;
    }

    /**
     * The parsed statement, read and parsed once.
     *
     * <p>A producer parsed it in {@code doStart}, which is the lifecycle a service has and a step
     * does not. Parsing on first use keeps the property that matters — the file is read once, not
     * per request — without inventing a lifecycle for it (docs/camel-removal.md decision 2).
     */
    private synchronized List<SqlNode> nodes(Exchange exchange) throws Exception {
        if (nodes == null) {
            Path file = io.tesseraql.core.dialect.DialectSqlResolver.resolve(
                    Path.of(sqlPath), dialect);
            nodes = Sql2WayParser.parse(Files.readString(file), functions(exchange));
        }
        return nodes;
    }

    /**
     * This runtime's function set, bound beside the tracer and lanes; a hand-built context
     * without the bean falls back to the process default (docs/module-scope.md).
     */
    private io.tesseraql.core.expr.ExpressionFunctions functions(Exchange exchange) {
        io.tesseraql.core.expr.ExpressionFunctions bound = exchange.beans().lookup(
                TesseraqlProperties.FUNCTIONS_BEAN,
                io.tesseraql.core.expr.ExpressionFunctions.class);
        return bound != null
                ? bound
                : io.tesseraql.core.expr.ExpressionFunctions
                        .processDefault();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> params = exchange.getProperty(
                TesseraqlProperties.SQL_PARAMS, Map.of(), Map.class);
        Map<String, Object> scopeContext = exchange.getProperty(
                TesseraqlProperties.CONTEXT, Map.of(), Map.class);
        BoundSql bound = SqlRenderer.render(nodes(exchange), params, scopeResolver(exchange),
                scopeContext, filePathResolver(exchange));
        DataSource dataSource = dataSource(exchange);
        // The statement layer, per exchange (docs/contract-sql-execution.md structural
        // decision 1): each statement this step runs — the main query or update, the count
        // wrapper, an export's extraction and its named queries — executes bounded, classified
        // and spanned (surface=route) through the one primitive. Cheap immutable; the tracer is
        // looked up per request.
        io.tesseraql.core.sql.SqlStatement statements = io.tesseraql.core.sql.SqlStatement
                .on(dataSource)
                .dialect(dialect)
                .timeoutSeconds(queryTimeoutSeconds)
                .surface("route")
                .tracer(tracer(exchange))
                .spanParent(exchange.getProperty(TesseraqlProperties.TRACE_CONTEXT,
                        io.tesseraql.core.telemetry.SpanContext.class));

        if ("query-export".equals(mode)) {
            export(exchange, dataSource, statements, bound);
            return;
        }
        if (!"query".equals(mode) && !"update".equals(mode)) {
            throw new TqlException(UNSUPPORTED_MODE,
                    "Unsupported SQL mode '" + mode
                            + "' (supported: query, update, query-export)");
        }
        Map<String, Object> context = exchange.getProperty(
                TesseraqlProperties.CONTEXT, Map.class);
        long startNanos = System.nanoTime();
        long startedAt = System.currentTimeMillis();
        // Declarative pagination (roadmap Phase 41): the main query of a page:-declaring
        // route executes with the dialect's clause appended (one extra row answers hasNext),
        // and the `page` context entry carries the metadata renderers and views read.
        io.tesseraql.pipeline.PageRequest page = exchange.getProperty(TesseraqlProperties.PAGE,
                io.tesseraql.pipeline.PageRequest.class);
        boolean paged = page != null && "query".equals(mode)
                && MAIN.equals(resultKey);
        Map<String, Object> result;
        if (paged) {
            result = executeQuery(statements, paginated(bound, page));
            List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
            boolean hasNext = rows.size() > page.size();
            if (hasNext) {
                rows = new java.util.ArrayList<>(rows.subList(0, page.size()));
                result.put("rows", rows);
                result.put("rowCount", rows.size());
            }
            Map<String, Object> info = new java.util.LinkedHashMap<>();
            info.put("number", page.number());
            info.put("size", page.size());
            info.put("hasNext", hasNext);
            info.put("hasPrev", page.number() > 1);
            if (page.by() != null && !page.by().isEmpty() && !rows.isEmpty()) {
                Map<String, Object> last = rows.get(rows.size() - 1);
                if (page.by().size() == 1) {
                    info.put("next", last.get(page.by().get(0)));
                } else {
                    // A composite cursor is one opaque row token (docs/list-surface.md
                    // decision 5); a null cursor component cannot mint one — the page ends.
                    try {
                        info.put("next",
                                io.tesseraql.core.rows.RowTokens.encode(last, page.by()));
                    } catch (IllegalArgumentException missingCursorComponent) {
                        // deliberately no next
                    }
                }
            }
            if (page.count()) {
                long total = countAll(statements, bound);
                info.put("totalRows", total);
                info.put("totalPages", Math.max(1,
                        (total + page.size() - 1) / page.size()));
            }
            if (context != null) {
                context.put("page", info);
            }
        } else {
            result = "update".equals(mode)
                    ? executeUpdate(statements, bound)
                    : executeQuery(statements, bound);
        }

        String countKey = "update".equals(mode) ? "affectedRows" : "rowCount";
        Object count = result.get(countKey);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        long rows = count instanceof Number number ? number.longValue() : 0L;
        slowSqlLog(exchange).record(new io.tesseraql.core.diag.SqlExecution(
                sqlPath, mode, durationMs, rows, startedAt));
        if (context != null) {
            io.tesseraql.pipeline.ContextResults.put(context, resultKey, result);
        }
        exchange.setBody(result);
    }

    private io.tesseraql.core.telemetry.Tracer tracer(Exchange exchange) {
        io.tesseraql.core.telemetry.Tracer tracer = exchange.beans().lookup(
                TesseraqlProperties.TRACER_BEAN,
                io.tesseraql.core.telemetry.Tracer.class);
        return tracer != null ? tracer : io.tesseraql.core.telemetry.NoopTracer.INSTANCE;
    }

    /**
     * The data-scope resolver bound by the runtime (roadmap Phase 29), or a resolver that rejects
     * any {@code /*%scope%/} directive when none is configured — so a scope directive in an app
     * without scopes fails loudly rather than silently bypassing scoping.
     */
    private ScopeResolver scopeResolver(Exchange exchange) {
        ScopeResolver resolver = exchange.beans().lookup(TesseraqlProperties.SCOPE_RESOLVER_BEAN,
                ScopeResolver.class);
        return resolver != null ? resolver : ScopeResolver.UNSUPPORTED;
    }

    /**
     * The file-scope resolver bound by the runtime (docs/duckdb.md), narrowed to this endpoint's
     * datasource. File placeholders only resolve on a duckdb endpoint — on any other dialect, and
     * when no resolver is bound, the renderer's reject-any-placeholder default applies, so a
     * {@code ${scope.*}} outside an analytics datasource fails loudly.
     */
    private io.tesseraql.core.sql.FilePathResolver filePathResolver(Exchange exchange) {
        if (!"duckdb".equals(dialect)) {
            return io.tesseraql.core.sql.FilePathResolver.UNSUPPORTED;
        }
        DatasourceFilePathResolver resolver = exchange.beans().lookup(
                TesseraqlProperties.FILE_PATH_RESOLVER_BEAN,
                DatasourceFilePathResolver.class);
        if (resolver == null) {
            return io.tesseraql.core.sql.FilePathResolver.UNSUPPORTED;
        }
        return (channel, name, suffix, context) -> resolver.resolve(
                datasource, channel, name, suffix, context);
    }

    private io.tesseraql.core.diag.SqlExecutionLog slowSqlLog(Exchange exchange) {
        io.tesseraql.core.diag.SqlExecutionLog log = exchange.beans().lookup(
                TesseraqlProperties.SLOW_SQL_LOG_BEAN,
                io.tesseraql.core.diag.SqlExecutionLog.class);
        return log != null ? log : io.tesseraql.core.diag.NoopSqlExecutionLog.INSTANCE;
    }

    /**
     * Streams the result set through the route's {@link FileCodec} into a spool and sets the
     * response body to its input stream (design ch. 28.6, 28.10) without materializing a
     * List&lt;Map&gt;. The codec and write spec (columns, formats, resolved locale/zone) are bound
     * by the compiled route, so synchronous exports share the file-export machinery. The spool is
     * deleted when the exchange completes.
     */
    private void export(Exchange exchange, DataSource dataSource,
            io.tesseraql.core.sql.SqlStatement statements, BoundSql bound) {
        FileCodec codec = exchange.getProperty(TesseraqlProperties.EXPORT_CODEC, FileCodec.class);
        FileWriteSpec spec = exchange.getProperty(TesseraqlProperties.EXPORT_SPEC,
                FileWriteSpec.class);
        if (codec == null || spec == null) {
            throw new TqlException(UNSUPPORTED_MODE,
                    "query-export requires the compiled export binding (codec and write spec)");
        }
        TempStore tempStore = tempStore(exchange);
        // The named results outlive the codec's write and nothing else.
        List<SpooledRows> spools = new java.util.ArrayList<>();
        SpoolRef ref;
        // Stream large exports per the dialect's profile so the driver uses a cursor instead of
        // buffering the whole result set in memory (design ch. 42, 28). The primitive prepares
        // forward-only at the profile's fetch size; the transaction bracket the profile demands
        // stays here, on the connection this method owns.
        io.tesseraql.core.dialect.StreamingProfile profile = io.tesseraql.core.dialect.StreamingProfiles
                .forDialect(dialect);
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            if (profile.autoCommitOff()) {
                connection.setAutoCommit(false);
            }
            try {
                SpoolKind kind = "csv".equals(codec.format()) ? SpoolKind.CSV : SpoolKind.BINARY;
                SpoolWriter writer = tempStore.createWriter(kind);
                try {
                    ref = statements.fetchSize(profile.fetchSize()).read(connection, sqlPath,
                            bound, (resultSet, span) -> {
                                // The writer closes before the statement; toRef() is only valid
                                // after close, so it answers outside this try. The reader's
                                // contract is SQLException, so an I/O failure travels unchecked
                                // and the outer catch unwraps it to the code it always mapped to.
                                try (writer) {
                                    io.tesseraql.core.files.ExportRowCap cap = io.tesseraql.core.files.ExportWrite
                                            .effectiveCap(codec, spec, exchange.getProperty(
                                                    TesseraqlProperties.EXPORT_ROW_CAP,
                                                    io.tesseraql.core.files.ExportRowCap.class));
                                    Map<String, Object> values = composedValues(exchange,
                                            connection,
                                            statements, tempStore, cap, spools);
                                    io.tesseraql.core.files.ResultSetRows extraction = new io.tesseraql.core.files.ResultSetRows(
                                            resultSet, dialect, cap, EXECUTION_ERROR);
                                    io.tesseraql.core.files.ExportWrite.write(codec, spec,
                                            tempStore,
                                            extraction,
                                            exchange.getProperty(
                                                    TesseraqlProperties.EXPORT_ENRICHER,
                                                    io.tesseraql.core.files.RowEnricher.class),
                                            exchange.getProperty(
                                                    TesseraqlProperties.EXPORT_ENRICH_WINDOW, 0,
                                                    Integer.class),
                                            values, filename,
                                            new io.tesseraql.core.spool.SpoolOutput(writer));
                                    writer.incrementRows(extraction.count());
                                    span.attribute("rowCount", extraction.count());
                                } catch (java.io.IOException ex) {
                                    throw new java.io.UncheckedIOException(ex);
                                }
                                return writer.toRef();
                            });
                } catch (Exception failed) {
                    // The reader lambda closes the writer, but a failure in prepare or execute
                    // never reaches the lambda: the open stream and the never-referenced spool
                    // file are still this method's to release.
                    try {
                        writer.close();
                    } catch (java.io.IOException closing) {
                        failed.addSuppressed(closing);
                    }
                    try {
                        tempStore.delete(writer.toRef());
                    } catch (RuntimeException deleting) {
                        failed.addSuppressed(deleting);
                    }
                    throw failed;
                }
                if (profile.autoCommitOff()) {
                    connection.commit();
                }
            } catch (Exception failed) {
                // A failed extraction must not commit, and a cleanup that also fails must not
                // replace the failure that matters.
                if (profile.autoCommitOff()) {
                    try {
                        connection.rollback();
                    } catch (java.sql.SQLException rollback) {
                        failed.addSuppressed(rollback);
                    }
                }
                throw failed;
            } finally {
                if (profile.autoCommitOff()) {
                    try {
                        connection.setAutoCommit(previousAutoCommit);
                    } catch (java.sql.SQLException restore) {
                        // The extraction's outcome is already decided; a connection that cannot
                        // reset is the pool's to retire, not a reason to re-report the export.
                        LOG.log(System.Logger.Level.WARNING,
                                "Could not restore autocommit after export {0}: {1}", sqlPath,
                                restore.getMessage());
                    }
                }
            }
        } catch (TqlException ex) {
            throw ex;
        } catch (java.io.UncheckedIOException ex) {
            throw executionError(ex.getCause());
        } catch (Exception ex) {
            throw executionError(ex);
        } finally {
            spools.forEach(SpooledRows::close);
        }

        try {
            exchange.setBody(tempStore.openInput(ref));
        } catch (java.io.IOException ex) {
            throw executionError(ex);
        }
        exchange.response().status(200);
        boolean split = spec.splitBy() != null && !spec.splitBy().isBlank();
        exchange.response().header(Headers.CONTENT_TYPE,
                split ? "application/zip" : codec.contentType());
        // The filename is route-author data (export.filename / the route id), but it is the one
        // Content-Disposition writer that sanitized nothing — a quote or control character in a
        // route file reached the wire verbatim.
        exchange.response().header("Content-Disposition",
                io.tesseraql.core.http.ContentDisposition.attachment(split
                        ? zipName(filename)
                        : filename));
        exchange.addOnCompletion(done -> tempStore.delete(ref));
    }

    /**
     * The export's other data (docs/export-pipeline.md, decision 2): the {@code http:} sources the
     * route already resolved before the extraction, plus its named queries run here — on the
     * extraction's own connection, inside its transaction and before it, so a document reads
     * exactly the state its rows came from. Each result is shaped like a read route's named query,
     * {@code rows} plus {@code rowCount}, so one template reads the same as the other.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> composedValues(Exchange exchange, Connection connection,
            io.tesseraql.core.sql.SqlStatement statements, TempStore tempStore,
            io.tesseraql.core.files.ExportRowCap cap, List<SpooledRows> spools) {
        Map<String, Object> resolved = exchange.getProperty(TesseraqlProperties.EXPORT_VALUES,
                Map.of(), Map.class);
        List<io.tesseraql.core.files.ExportQuery> queries = exchange.getProperty(
                TesseraqlProperties.EXPORT_QUERIES, List.of(), List.class);
        if (queries.isEmpty()) {
            return resolved;
        }
        Map<String, Object> params = exchange.getProperty(TesseraqlProperties.SQL_PARAMS, Map.of(),
                Map.class);
        Map<String, Object> values = new LinkedHashMap<>(resolved);
        for (io.tesseraql.core.files.ExportQuery query : queries) {
            BoundSql bound = SqlRenderer.render(exportQueryNodes(exchange, query), params);
            try {
                // Spooled like the extraction, and counted by the same ceiling: a cap that
                // bounds the subject and lets a named query run unbounded bounds nothing
                // (docs/export-pipeline.md, decision 15).
                values.put(query.name(),
                        statements.read(connection, query.sqlFile().toString(), bound,
                                (resultSet, span) -> io.tesseraql.core.files.ExportWrite
                                        .namedResult(tempStore,
                                                new io.tesseraql.core.files.ResultSetRows(
                                                        resultSet, dialect, cap,
                                                        EXECUTION_ERROR),
                                                spools)));
            } catch (java.sql.SQLException ex) {
                throw executionError(ex);
            }
        }
        return Map.copyOf(values);
    }

    /** An export query's parsed SQL, read once per file and kept for this step's lifetime. */
    private List<SqlNode> exportQueryNodes(Exchange exchange,
            io.tesseraql.core.files.ExportQuery query) {
        return exportQueryNodes.computeIfAbsent(query.sqlFile(), file -> {
            try {
                return Sql2WayParser.parse(Files.readString(
                        io.tesseraql.core.dialect.DialectSqlResolver.resolve(file,
                                dialect)),
                        functions(exchange));
            } catch (java.io.IOException ex) {
                throw new TqlException(EXECUTION_ERROR,
                        "Cannot read export query '" + query.name() + "': " + ex.getMessage());
            }
        });
    }

    /** The bundle's own name: the declared filename with its placeholder and extension dropped. */
    private static String zipName(String filename) {
        String withoutKey = filename.replace(io.tesseraql.core.files.SplitExport.KEY, "")
                .replaceAll("[-_.]+$", "");
        int dot = withoutKey.lastIndexOf('.');
        String stem = dot > 0 ? withoutKey.substring(0, dot) : withoutKey;
        return (stem.isBlank() ? "export" : stem) + ".zip";
    }

    private TempStore tempStore(Exchange exchange) {
        TempStore bean = exchange.beans().lookup(TesseraqlProperties.TEMP_STORE_BEAN,
                TempStore.class);
        return bean != null
                ? bean
                : new FileTempStore(
                        java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                                "tesseraql-spool"));
    }

    /** The bound statement with the dialect's pagination clause (size+1 rows) appended. */
    private BoundSql paginated(BoundSql bound, io.tesseraql.pipeline.PageRequest page) {
        io.tesseraql.core.dialect.Dialect paginating = io.tesseraql.core.dialect.Dialect
                .fromId(dialect).orElse(io.tesseraql.core.dialect.Dialect.POSTGRES);
        io.tesseraql.core.dialect.Pagination.Clause clause = io.tesseraql.core.dialect.Pagination
                .clause(paginating, page.size() + 1L, page.offset());
        List<io.tesseraql.core.sql.BoundParameter> parameters = new java.util.ArrayList<>(
                bound.parameters());
        for (Object value : clause.parameters()) {
            parameters.add(new io.tesseraql.core.sql.BoundParameter("page", value, -1));
        }
        return new BoundSql(stripTerminator(bound.sql()) + "\n" + clause.sql(), parameters,
                bound.sourceMap(), bound.coverageTrace(), bound.variant());
    }

    /** The rendered SQL without its optional trailing terminator, so a clause can append. */
    private static String stripTerminator(String sql) {
        String trimmed = sql.stripTrailing();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** The total row count: the rendered query wrapped in {@code select count(*)}. */
    private long countAll(io.tesseraql.core.sql.SqlStatement statements, BoundSql bound) {
        BoundSql counting = new BoundSql(
                "select count(*) as tql_total from (\n" + stripTerminator(bound.sql())
                        + "\n) tql_count",
                bound.parameters(), bound.sourceMap(), bound.coverageTrace(), bound.variant());
        try {
            Object total = statements.read(sqlPath, counting,
                    (resultSet, span) -> resultSet.next() ? resultSet.getObject(1) : 0L);
            return total instanceof Number number ? number.longValue() : 0L;
        } catch (java.sql.SQLException ex) {
            throw executionError(ex);
        }
    }

    private Map<String, Object> executeQuery(io.tesseraql.core.sql.SqlStatement statements,
            BoundSql bound) {
        try {
            List<Map<String, Object>> rows = statements.read(sqlPath, bound,
                    io.tesseraql.core.sql.SqlStatement.cappedRows(dialect, maxRows, overflow()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rows", rows);
            result.put("rowCount", rows.size());
            return result;
        } catch (java.sql.SQLException ex) {
            throw executionError(ex);
        }
    }

    /** The caller's half of the capped read: warn truncates with a log, fail refuses. */
    private io.tesseraql.core.sql.SqlStatement.RowOverflow overflow() {
        return () -> {
            if ("warn".equals(onOverflow)) {
                LOG.log(System.Logger.Level.WARNING,
                        "Result truncated at maxRows={0} for {1}", maxRows,
                        sqlPath);
                return;
            }
            throw TqlException.builder(MATERIALIZATION_OVERFLOW)
                    .message("Result exceeds maxRows=" + maxRows
                            + " (use pagination or query-export)")
                    .source(sqlPath)
                    .build();
        };
    }

    private Map<String, Object> executeUpdate(io.tesseraql.core.sql.SqlStatement statements,
            BoundSql bound) {
        try {
            int affected = statements.update(sqlPath, bound);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("affectedRows", affected);
            return result;
        } catch (java.sql.SQLException ex) {
            throw executionError(ex);
        }
    }

    /** Resolves the datasource for the exchange; see {@link TenantRouting} for the routing rule. */
    private DataSource dataSource(Exchange exchange) {
        return TenantRouting.dataSource(exchange, datasource);
    }

    private TqlException executionError(Exception ex) {
        return TqlException.builder(classifyCode(ex))
                .message("SQL execution failed: " + ex.getMessage())
                .source(sqlPath)
                .cause(ex)
                .build();
    }

    /** Maps a JDBC failure to a portable error code so constraint violations get meaningful statuses. */
    private static TqlErrorCode classifyCode(Exception ex) {
        java.sql.SQLException sql = ex instanceof java.sql.SQLException direct
                ? direct
                : (ex.getCause() instanceof java.sql.SQLException cause ? cause : null);
        if (sql == null) {
            return EXECUTION_ERROR;
        }
        return switch (io.tesseraql.core.dialect.SqlErrors.classify(sql)) {
            case UNIQUE_VIOLATION -> UNIQUE_VIOLATION_CODE;
            case FOREIGN_KEY_VIOLATION -> FOREIGN_KEY_VIOLATION_CODE;
            case NOT_NULL_VIOLATION -> NOT_NULL_VIOLATION_CODE;
            case CHECK_VIOLATION -> CHECK_VIOLATION_CODE;
            case SERIALIZATION_FAILURE -> SERIALIZATION_CODE;
            default -> EXECUTION_ERROR;
        };
    }

}
