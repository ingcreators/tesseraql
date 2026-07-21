package io.tesseraql.camel.sql;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.FileCodec;
import io.tesseraql.core.files.FileWriteSpec;
import io.tesseraql.core.spool.FileTempStore;
import io.tesseraql.core.spool.SpoolKind;
import io.tesseraql.core.spool.SpoolRef;
import io.tesseraql.core.spool.SpoolWriter;
import io.tesseraql.core.spool.TempStore;
import io.tesseraql.core.sql.BoundParameter;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.ScopeResolver;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.SqlRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultProducer;

/**
 * Executes a 2-way SQL file against JDBC and publishes the result into the execution context
 * (design ch. 9.1). The SQL is parsed once at startup and rendered per exchange with the resolved
 * bind parameters held in the {@link TesseraqlProperties#SQL_PARAMS} property.
 */
public class TesseraqlSqlProducer extends DefaultProducer {

    /** TQL-SQL-2500: the SQL failed to execute for a reason beyond the portable constraint kinds. */
    private static final TqlErrorCode EXECUTION_ERROR = new TqlErrorCode(TqlDomain.SQL, 2500);
    private static final TqlErrorCode UNSUPPORTED_MODE = new TqlErrorCode(TqlDomain.SQL, 2501);
    private static final TqlErrorCode NO_DATASOURCE = new TqlErrorCode(TqlDomain.SQL, 2502);
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
    private static final System.Logger LOG = System.getLogger(TesseraqlSqlProducer.class.getName());

    private final TesseraqlSqlEndpoint endpoint;
    private List<SqlNode> nodes;

    public TesseraqlSqlProducer(TesseraqlSqlEndpoint endpoint) {
        super(endpoint);
        this.endpoint = endpoint;
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        Path file = io.tesseraql.core.dialect.DialectSqlResolver.resolve(
                Path.of(endpoint.getSqlPath()), endpoint.getDialect());
        this.nodes = Sql2WayParser.parse(Files.readString(file));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        String mode = endpoint.getMode();
        io.tesseraql.core.telemetry.SpanContext parent = exchange.getProperty(
                TesseraqlProperties.TRACE_CONTEXT, io.tesseraql.core.telemetry.SpanContext.class);
        io.tesseraql.core.telemetry.Span span = tracer(exchange)
                .start("tesseraql.sql.execute", parent)
                .attribute("sqlId", endpoint.getSqlPath())
                .attribute("mode", mode);
        try {
            Map<String, Object> params = exchange.getProperty(
                    TesseraqlProperties.SQL_PARAMS, Map.of(), Map.class);
            Map<String, Object> scopeContext = exchange.getProperty(
                    TesseraqlProperties.CONTEXT, Map.of(), Map.class);
            BoundSql bound = SqlRenderer.render(nodes, params, scopeResolver(exchange),
                    scopeContext, filePathResolver(exchange));
            DataSource dataSource = dataSource(exchange);

            if ("query-export".equals(mode)) {
                export(exchange, dataSource, bound);
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
            io.tesseraql.camel.PageRequest page = exchange.getProperty(TesseraqlProperties.PAGE,
                    io.tesseraql.camel.PageRequest.class);
            boolean paged = page != null && "query".equals(mode)
                    && "sql".equals(endpoint.getResultKey());
            Map<String, Object> result;
            if (paged) {
                result = executeQuery(dataSource, paginated(bound, page));
                @SuppressWarnings("unchecked")
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
                if (page.by() != null && !rows.isEmpty()) {
                    info.put("next", rows.get(rows.size() - 1).get(page.by()));
                }
                if (page.count()) {
                    long total = countAll(dataSource, bound);
                    info.put("totalRows", total);
                    info.put("totalPages", Math.max(1,
                            (total + page.size() - 1) / page.size()));
                }
                if (context != null) {
                    context.put("page", info);
                }
            } else {
                result = "update".equals(mode)
                        ? executeUpdate(dataSource, bound)
                        : executeQuery(dataSource, bound);
            }

            String countKey = "update".equals(mode) ? "affectedRows" : "rowCount";
            Object count = result.get(countKey);
            span.attribute(countKey, count);
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            long rows = count instanceof Number number ? number.longValue() : 0L;
            slowSqlLog(exchange).record(new io.tesseraql.core.diag.SqlExecution(
                    endpoint.getSqlPath(), mode, durationMs, rows, startedAt));
            if (context != null) {
                context.put(endpoint.getResultKey(), result);
            }
            exchange.getMessage().setBody(result);
        } catch (RuntimeException ex) {
            span.recordError(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    private io.tesseraql.core.telemetry.Tracer tracer(Exchange exchange) {
        io.tesseraql.core.telemetry.Tracer tracer = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.TRACER_BEAN,
                        io.tesseraql.core.telemetry.Tracer.class);
        return tracer != null ? tracer : io.tesseraql.core.telemetry.NoopTracer.INSTANCE;
    }

    /**
     * The data-scope resolver bound by the runtime (roadmap Phase 29), or a resolver that rejects
     * any {@code /*%scope%/} directive when none is configured — so a scope directive in an app
     * without scopes fails loudly rather than silently bypassing scoping.
     */
    private ScopeResolver scopeResolver(Exchange exchange) {
        ScopeResolver resolver = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.SCOPE_RESOLVER_BEAN, ScopeResolver.class);
        return resolver != null ? resolver : ScopeResolver.UNSUPPORTED;
    }

    /**
     * The file-scope resolver bound by the runtime (docs/duckdb.md), narrowed to this endpoint's
     * datasource. File placeholders only resolve on a duckdb endpoint — on any other dialect, and
     * when no resolver is bound, the renderer's reject-any-placeholder default applies, so a
     * {@code ${scope.*}} outside an analytics datasource fails loudly.
     */
    private io.tesseraql.core.sql.FilePathResolver filePathResolver(Exchange exchange) {
        if (!"duckdb".equals(endpoint.getDialect())) {
            return io.tesseraql.core.sql.FilePathResolver.UNSUPPORTED;
        }
        DatasourceFilePathResolver resolver = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.FILE_PATH_RESOLVER_BEAN,
                        DatasourceFilePathResolver.class);
        if (resolver == null) {
            return io.tesseraql.core.sql.FilePathResolver.UNSUPPORTED;
        }
        return (channel, name, suffix, context) -> resolver.resolve(
                endpoint.getDatasource(), channel, name, suffix, context);
    }

    private io.tesseraql.core.diag.SqlExecutionLog slowSqlLog(Exchange exchange) {
        io.tesseraql.core.diag.SqlExecutionLog log = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.SLOW_SQL_LOG_BEAN,
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
    private void export(Exchange exchange, DataSource dataSource, BoundSql bound) {
        FileCodec codec = exchange.getProperty(TesseraqlProperties.EXPORT_CODEC, FileCodec.class);
        FileWriteSpec spec = exchange.getProperty(TesseraqlProperties.EXPORT_SPEC,
                FileWriteSpec.class);
        if (codec == null || spec == null) {
            throw new TqlException(UNSUPPORTED_MODE,
                    "query-export requires the compiled export binding (codec and write spec)");
        }
        TempStore tempStore = tempStore();
        SpoolRef ref;
        // Stream large exports per the dialect's profile so the driver uses a cursor instead of
        // buffering the whole result set in memory (design ch. 42, 28).
        io.tesseraql.core.dialect.StreamingProfile profile = io.tesseraql.core.dialect.StreamingProfiles
                .forDialect(endpoint.getDialect());
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            if (profile.autoCommitOff()) {
                connection.setAutoCommit(false);
            }
            try (PreparedStatement statement = connection.prepareStatement(bound.sql(),
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                applyTimeout(statement);
                statement.setFetchSize(profile.fetchSize());
                bindParameters(statement, bound.parameters());
                SpoolKind kind = "csv".equals(codec.format()) ? SpoolKind.CSV : SpoolKind.BINARY;
                SpoolWriter writer = tempStore.createWriter(kind);
                // The writer closes first (listed first); toRef() is only valid after close.
                try (writer; ResultSet resultSet = statement.executeQuery()) {
                    codec.write(new SpoolOutputStream(writer), spec,
                            new ResultRows(resultSet, writer));
                }
                ref = writer.toRef();
            } finally {
                if (profile.autoCommitOff()) {
                    connection.commit();
                    connection.setAutoCommit(previousAutoCommit);
                }
            }
        } catch (TqlException ex) {
            throw ex;
        } catch (Exception ex) {
            throw executionError(ex);
        }

        try {
            exchange.getMessage().setBody(tempStore.openInput(ref));
        } catch (java.io.IOException ex) {
            throw executionError(ex);
        }
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, codec.contentType());
        exchange.getMessage().setHeader("Content-Disposition",
                "attachment; filename=\"" + endpoint.getFilename() + "\"");
        exchange.getExchangeExtension().addOnCompletion(new org.apache.camel.spi.Synchronization() {
            @Override
            public void onComplete(Exchange completed) {
                tempStore.delete(ref);
            }

            @Override
            public void onFailure(Exchange failed) {
                tempStore.delete(ref);
            }
        });
    }

    /** Streams result-set rows to the codec as label-normalized maps, counting them as read. */
    private final class ResultRows implements java.util.Iterator<Map<String, Object>> {

        private final ResultSet resultSet;
        private final SpoolWriter writer;
        private final List<String> labels = new ArrayList<>();
        private Boolean pending;

        ResultRows(ResultSet resultSet, SpoolWriter writer) throws java.sql.SQLException {
            this.resultSet = resultSet;
            this.writer = writer;
            ResultSetMetaData metaData = resultSet.getMetaData();
            for (int col = 1; col <= metaData.getColumnCount(); col++) {
                labels.add(io.tesseraql.core.dialect.Labels.normalize(
                        endpoint.getDialect(), metaData.getColumnLabel(col)));
            }
        }

        @Override
        public boolean hasNext() {
            try {
                if (pending == null) {
                    pending = resultSet.next();
                }
                return pending;
            } catch (java.sql.SQLException ex) {
                throw executionError(ex);
            }
        }

        @Override
        public Map<String, Object> next() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException();
            }
            pending = null;
            try {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int col = 1; col <= labels.size(); col++) {
                    // Raw JDBC values, like the asynchronous file-export path: the codec's
                    // ColumnValues formatting decides how temporals and numbers render.
                    row.put(labels.get(col - 1), resultSet.getObject(col));
                }
                writer.incrementRows(1);
                return row;
            } catch (java.sql.SQLException ex) {
                throw executionError(ex);
            }
        }
    }

    /** Adapts the spool writer to the {@link java.io.OutputStream} the codecs write to. */
    private static final class SpoolOutputStream extends java.io.OutputStream {

        private final SpoolWriter writer;

        SpoolOutputStream(SpoolWriter writer) {
            this.writer = writer;
        }

        @Override
        public void write(int b) throws java.io.IOException {
            writer.write(new byte[]{(byte) b});
        }

        @Override
        public void write(byte[] data, int offset, int length) throws java.io.IOException {
            writer.write(java.util.Arrays.copyOfRange(data, offset, offset + length));
        }
    }

    private TempStore tempStore() {
        TempStore bean = endpoint.getCamelContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.TEMP_STORE_BEAN, TempStore.class);
        return bean != null
                ? bean
                : new FileTempStore(
                        java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                                "tesseraql-spool"));
    }

    /** The bound statement with the dialect's pagination clause (size+1 rows) appended. */
    private BoundSql paginated(BoundSql bound, io.tesseraql.camel.PageRequest page) {
        io.tesseraql.core.dialect.Dialect dialect = io.tesseraql.core.dialect.Dialect
                .fromId(endpoint.getDialect()).orElse(io.tesseraql.core.dialect.Dialect.POSTGRES);
        io.tesseraql.core.dialect.Pagination.Clause clause = io.tesseraql.core.dialect.Pagination
                .clause(dialect, page.size() + 1L, page.offset());
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
    private long countAll(DataSource dataSource, BoundSql bound) {
        BoundSql counting = new BoundSql(
                "select count(*) as tql_total from (\n" + stripTerminator(bound.sql())
                        + "\n) tql_count",
                bound.parameters(), bound.sourceMap(), bound.coverageTrace(), bound.variant());
        Map<String, Object> result = executeQuery(dataSource, counting);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        Object total = rows.isEmpty() ? 0L : rows.get(0).values().iterator().next();
        return total instanceof Number number ? number.longValue() : 0L;
    }

    private Map<String, Object> executeQuery(DataSource dataSource, BoundSql bound) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            applyTimeout(statement);
            bindParameters(statement, bound.parameters());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Map<String, Object>> rows = readRows(resultSet);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("rows", rows);
                result.put("rowCount", rows.size());
                return result;
            }
        } catch (java.sql.SQLException ex) {
            throw executionError(ex);
        }
    }

    private Map<String, Object> executeUpdate(DataSource dataSource, BoundSql bound) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            applyTimeout(statement);
            bindParameters(statement, bound.parameters());
            int affected = statement.executeUpdate();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("affectedRows", affected);
            return result;
        } catch (java.sql.SQLException ex) {
            throw executionError(ex);
        }
    }

    /**
     * Resolves the datasource for the exchange. When the request carries a resolved tenant and a
     * {@link io.tesseraql.camel.tenant.TenantDataSourceResolver} is bound, the per-tenant datasource
     * is used (database/schema-per-tenant, design ch. 30.2); otherwise the named datasource is used.
     *
     * <p>Tenant routing replaces only the {@code main} connector: an explicit non-main
     * {@code datasource:} (roadmap Phase 53) is authoritative — named connectors are
     * deployment-shared infrastructure, not tenant homes.
     */
    private DataSource dataSource(Exchange exchange) {
        Object tenant = "main".equals(endpoint.getDatasource())
                ? exchange.getProperty(TesseraqlProperties.TENANT)
                : null;
        if (tenant instanceof io.tesseraql.core.tenant.TenantContext tenantContext) {
            io.tesseraql.camel.tenant.TenantDataSourceResolver resolver = endpoint.getCamelContext()
                    .getRegistry().lookupByNameAndType(
                            TesseraqlProperties.TENANT_DATASOURCE_RESOLVER_BEAN,
                            io.tesseraql.camel.tenant.TenantDataSourceResolver.class);
            if (resolver != null) {
                DataSource resolved = resolver.resolve(tenantContext.id());
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        DataSource dataSource = endpoint.getCamelContext().getRegistry()
                .lookupByNameAndType(endpoint.getDatasource(), DataSource.class);
        if (dataSource == null) {
            throw new TqlException(NO_DATASOURCE,
                    "No DataSource named '" + endpoint.getDatasource() + "' in the registry");
        }
        return dataSource;
    }

    private TqlException executionError(Exception ex) {
        return TqlException.builder(classifyCode(ex))
                .message("SQL execution failed: " + ex.getMessage())
                .source(endpoint.getSqlPath())
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

    /**
     * Bounds the statement by the endpoint's timeout (roadmap Phase 45): the compiler passes
     * the per-binding override or the app default, so a runaway query is cancelled by the
     * driver instead of holding a pool connection forever. 0 = unbounded (explicit opt-out).
     */
    private void applyTimeout(PreparedStatement statement) throws SQLException {
        int seconds = endpoint.getQueryTimeoutSeconds();
        if (seconds > 0) {
            statement.setQueryTimeout(seconds);
        }
    }

    private void bindParameters(PreparedStatement statement, List<BoundParameter> parameters)
            throws java.sql.SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, parameters.get(i).value());
        }
    }

    private List<Map<String, Object>> readRows(ResultSet resultSet) throws java.sql.SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        int maxRows = endpoint.getMaxRows();
        boolean warn = "warn".equals(endpoint.getOnOverflow());
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            if (maxRows >= 0 && rows.size() >= maxRows) {
                if (warn) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Result truncated at maxRows={0} for {1}", maxRows,
                            endpoint.getSqlPath());
                    break;
                }
                throw TqlException.builder(MATERIALIZATION_OVERFLOW)
                        .message("Result exceeds maxRows=" + maxRows
                                + " (use pagination, query-stream, or query-export)")
                        .source(endpoint.getSqlPath())
                        .build();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int col = 1; col <= columnCount; col++) {
                row.put(io.tesseraql.core.dialect.Labels.normalize(
                        endpoint.getDialect(), metaData.getColumnLabel(col)),
                        normalize(resultSet.getObject(col)));
            }
            rows.add(row);
        }
        return rows;
    }

    /** Normalizes JDBC temporal types to ISO-8601 strings for stable JSON output. */
    private static Object normalize(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        return value;
    }
}
