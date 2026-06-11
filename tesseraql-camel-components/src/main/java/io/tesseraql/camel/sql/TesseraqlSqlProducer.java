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
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.SqlRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
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
            BoundSql bound = SqlRenderer.render(nodes, params);
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
            Map<String, Object> result = "update".equals(mode)
                    ? executeUpdate(dataSource, bound)
                    : executeQuery(dataSource, bound);

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

    private Map<String, Object> executeQuery(DataSource dataSource, BoundSql bound) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(bound.sql())) {
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
     */
    private DataSource dataSource(Exchange exchange) {
        Object tenant = exchange.getProperty(TesseraqlProperties.TENANT);
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
