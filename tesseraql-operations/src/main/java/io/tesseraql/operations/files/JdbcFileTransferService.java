package io.tesseraql.operations.files;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.FileCodec;
import io.tesseraql.core.files.FileCodecs;
import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.core.spool.SpoolKind;
import io.tesseraql.core.spool.SpoolRef;
import io.tesseraql.core.spool.SpoolWriter;
import io.tesseraql.core.spool.TempStore;
import io.tesseraql.core.sql.BoundParameter;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.SqlRenderer;
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
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private static final int MAX_RECORDED_ERRORS = 100;

    private final JobRepository jobs;
    private final TempStore tempStore;
    private final DataSource dataSource;
    private final FileCodecs codecs;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public JdbcFileTransferService(JobRepository jobs, TempStore tempStore, DataSource dataSource,
            FileCodecs codecs) {
        this.jobs = jobs;
        this.tempStore = tempStore;
        this.dataSource = dataSource;
        this.codecs = codecs;
    }

    /**
     * Creates the transfer table if absent, from the bundled
     * {@code V1__framework_operations.sql} migration script.
     */
    public void ensureSchema() {
        try {
            io.tesseraql.core.util.SqlScripts.apply(dataSource, JdbcFileTransferService.class,
                    "/tesseraql/db/migration/operations/V1__framework_operations.sql");
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
        String transferId = jobs.startExecution(request.routeId(), request.appName(), "import");
        insertTransfer(transferId, request.routeId(), request.appName(), "IMPORT",
                request.format(), null, null, null, Map.of());
        executor.submit(guarded(transferId, () -> {
            try {
                runImport(transferId, request, codec, upload);
            } finally {
                tempStore.delete(upload);
            }
        }));
        return transferId;
    }

    private SpoolRef spool(java.io.InputStream content) {
        try (SpoolWriter writer = tempStore.createWriter(SpoolKind.BINARY); content) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = content.read(buffer)) >= 0) {
                if (read > 0) {
                    byte[] chunk = new byte[read];
                    System.arraycopy(buffer, 0, chunk, 0, read);
                    writer.write(chunk);
                }
            }
            writer.close();
            return writer.toRef();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public String startExport(ExportRequest request) {
        FileCodec codec = codecs.require(request.format());
        String filename = request.filename() != null && !request.filename().isBlank()
                ? request.filename()
                : request.routeId() + codec.extension();
        String transferId = jobs.startExecution(request.routeId(), request.appName(), "export");
        insertTransfer(transferId, request.routeId(), request.appName(), "EXPORT",
                request.format(), filename, request.afterTiming(),
                request.afterSqlFile() == null ? null : request.afterSqlFile().toString(),
                request.params());
        executor.submit(guarded(transferId, () -> runExport(transferId, request, codec, filename)));
        return transferId;
    }

    @Override
    public Optional<TransferStatus> status(String transferId) {
        // Read the execution status before the transfer detail: the run records its counts and
        // errors before completing, so a terminal status guarantees the detail row is final
        // (reading the other way round can observe COMPLETED with stale counts).
        String executionStatus = jobs.findExecution(transferId)
                .map(execution -> execution.status().name()).orElse("UNKNOWN");
        return findTransfer(transferId).map(transfer -> new TransferStatus(
                transferId, transfer.routeId(), transfer.direction(), executionStatus,
                transfer.rowCount(), transfer.errors(), transfer.filename(),
                transfer.downloadedAt() != null));
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

    /** Stops accepting work and lets running transfers finish. */
    public void close() {
        executor.shutdown();
    }

    /** No failure may leave a transfer RUNNING forever: anything escaping fails the execution. */
    private Runnable guarded(String transferId, Runnable work) {
        return () -> {
            try {
                work.run();
            } catch (Throwable ex) {
                LOG.warn("File transfer {} failed: {}", transferId, ex.toString());
                jobs.failExecution(transferId, ex.toString());
            }
        };
    }

    private void runImport(String transferId, ImportRequest request, FileCodec codec,
            SpoolRef upload) {
        List<SqlNode> rowSql = parse(request.rowSqlFile());
        List<RowError> errors = new ArrayList<>();
        long[] applied = {0};
        try (Connection connection = dataSource.getConnection();
                java.io.InputStream content = tempStore.openInput(upload)) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                codec.read(content, request.readSpec(),
                        (rowNumber, values) -> {
                            Savepoint savepoint = connection.setSavepoint();
                            try {
                                // Typed columns (date/datetime/number) parse before binding, so
                                // bad values surface as row errors, not dialect cast failures.
                                Map<String, Object> typed = io.tesseraql.core.files.ColumnValues
                                        .parseRow(request.readSpec(), values);
                                applied[0] += executeUpdate(connection,
                                        SqlRenderer.render(rowSql, typed));
                                connection.releaseSavepoint(savepoint);
                            } catch (SQLException | RuntimeException ex) {
                                connection.rollback(savepoint);
                                if (errors.size() < MAX_RECORDED_ERRORS) {
                                    errors.add(new RowError(rowNumber, ex.getMessage()));
                                } else if (errors.size() == MAX_RECORDED_ERRORS) {
                                    errors.add(new RowError(rowNumber, "... further errors omitted"));
                                }
                            }
                        });
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
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception ex) {
            LOG.warn("File import {} failed: {}", transferId, ex.getMessage());
            recordRows(transferId, 0, errors);
            jobs.failExecution(transferId, ex.getMessage());
        }
    }

    private void runExport(String transferId, ExportRequest request, FileCodec codec,
            String filename) {
        List<SqlNode> query = parse(request.querySqlFile());
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                BoundSql bound = SqlRenderer.render(query, request.params());
                long rows;
                SpoolWriter writer = tempStore.createWriter(SpoolKind.BINARY);
                try (writer;
                        PreparedStatement statement = prepare(connection, bound);
                        ResultSet results = statement.executeQuery();
                        OutputStream out = new SpoolOutputStream(writer)) {
                    RowIterator iterator = new RowIterator(results);
                    codec.write(out, request.writeSpec(), iterator);
                    rows = iterator.count;
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
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception ex) {
            LOG.warn("File export {} failed: {}", transferId, ex.getMessage());
            jobs.failExecution(transferId, ex.getMessage());
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

    private static int executeUpdate(Connection connection, BoundSql bound) throws SQLException {
        try (PreparedStatement statement = prepare(connection, bound)) {
            return statement.executeUpdate();
        }
    }

    private static PreparedStatement prepare(Connection connection, BoundSql bound)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(bound.sql());
        List<BoundParameter> parameters = bound.parameters();
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, parameters.get(i).value());
        }
        return statement;
    }

    private static List<SqlNode> parse(Path sqlFile) {
        try {
            return Sql2WayParser.parse(Files.readString(sqlFile, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Streams a result set as column-name-to-value maps, counting rows as they pass. */
    private static final class RowIterator implements Iterator<Map<String, Object>> {

        private final ResultSet results;
        private final List<String> labels;
        private Boolean hasNext;
        private long count;

        RowIterator(ResultSet results) throws SQLException {
            this.results = results;
            ResultSetMetaData metaData = results.getMetaData();
            List<String> columnLabels = new ArrayList<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                columnLabels.add(metaData.getColumnLabel(i));
            }
            this.labels = List.copyOf(columnLabels);
        }

        @Override
        public boolean hasNext() {
            if (hasNext == null) {
                try {
                    hasNext = results.next();
                } catch (SQLException ex) {
                    throw new TqlException(TRANSFER_ERROR, "Export query failed: " + ex.getMessage());
                }
            }
            return hasNext;
        }

        @Override
        public Map<String, Object> next() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException();
            }
            hasNext = null;
            count++;
            Map<String, Object> row = new LinkedHashMap<>();
            try {
                for (int i = 0; i < labels.size(); i++) {
                    row.put(labels.get(i), results.getObject(i + 1));
                }
            } catch (SQLException ex) {
                throw new TqlException(TRANSFER_ERROR, "Export query failed: " + ex.getMessage());
            }
            return row;
        }
    }

    /** Adapts the SpoolWriter byte sink to an OutputStream for codecs. */
    private static final class SpoolOutputStream extends OutputStream {

        private final SpoolWriter writer;

        SpoolOutputStream(SpoolWriter writer) {
            this.writer = writer;
        }

        @Override
        public void write(int b) throws IOException {
            writer.write(new byte[] {(byte) b});
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            byte[] chunk = new byte[length];
            System.arraycopy(data, offset, chunk, 0, length);
            writer.write(chunk);
        }
    }

    // --- tql_file_transfer persistence ---

    private record TransferRow(String routeId, String appName, String direction, String format,
            String filename, String spoolUri, long rowCount, List<RowError> errors,
            String afterTiming, String afterSqlFile, Map<String, Object> params,
            Timestamp downloadedAt) {
    }

    private void insertTransfer(String transferId, String routeId, String appName,
            String direction, String format, String filename, String afterTiming,
            String afterSqlFile, Map<String, Object> params) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        insert into tql_file_transfer
                          (transfer_id, route_id, app_name, direction, format, filename,
                           after_timing, after_sql_file, params_json, row_count, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)""")) {
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
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new TqlException(TRANSFER_ERROR,
                    "Failed to record file transfer: " + ex.getMessage());
        }
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

    private void update(String sql, SqlBindings bindings) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
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
