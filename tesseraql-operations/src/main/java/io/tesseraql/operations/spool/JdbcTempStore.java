package io.tesseraql.operations.spool;

import io.tesseraql.core.spool.SpoolKind;
import io.tesseraql.core.spool.SpoolRef;
import io.tesseraql.core.spool.SpoolWriter;
import io.tesseraql.core.spool.TempStore;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The main-database {@link TempStore} (docs/deployment.md, "Shared export files"):
 * {@code tesseraql.temp.store: db} spools query-export/query-spool payloads into the
 * {@code tql_temp_spool} table, so a download can be served by any node sharing the database —
 * no session affinity, no shared filesystem. Writes stage through a local scratch file
 * (bounded memory, and the row is inserted whole on close so a reader never sees a partial
 * spool); reads stage back through a scratch file so no connection is held open while a slow
 * client streams. The table is {@code ensureSchema}-owned (outside the Flyway component set,
 * like the inbox); retention rides the same {@link #delete} calls the file store gets.
 *
 * <p>A database is the right home for the modest export sizes LOB apps produce, not for
 * gigabytes — {@code tesseraql.temp.maxBytes} (default 64 MB) fails a larger spool loudly and
 * the docs point at {@code store: blob} for heavy volumes.
 */
public final class JdbcTempStore implements TempStore {

    /** Default per-spool size cap; override with tesseraql.temp.maxBytes. */
    public static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;

    private final DataSource dataSource;
    private final Path scratchDir;
    private final long maxBytes;

    public JdbcTempStore(DataSource dataSource, Path scratchDir, long maxBytes) {
        this.dataSource = dataSource;
        this.scratchDir = scratchDir;
        this.maxBytes = maxBytes;
    }

    /** Creates {@code tql_temp_spool} when absent; tolerant of concurrent creation. */
    public void ensureSchema() {
        // The content column type differs per dialect; issue the portable-first form and fall
        // back per vendor keyword. Kept to the lowest common denominator deliberately.
        String[] contentTypes = {"blob", "bytea", "varbinary(max)"};
        SQLException last = null;
        for (String contentType : contentTypes) {
            try (Connection connection = dataSource.getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("create table tql_temp_spool ("
                        + "spool_id varchar(64) not null primary key, "
                        + "kind varchar(16) not null, "
                        + "byte_size bigint not null, "
                        + "row_count bigint not null, "
                        + "created_at timestamp not null, "
                        + "content " + contentType + " not null)");
                return;
            } catch (SQLException ex) {
                last = ex;
            }
        }
        // Every form failed: either the table already exists, or the schema is unusable.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement probe = connection.prepareStatement(
                        "select count(*) from tql_temp_spool where 1 = 0")) {
            probe.executeQuery().close();
        } catch (SQLException verify) {
            throw new IllegalStateException(
                    "tql_temp_spool is unavailable: " + last.getMessage(), last);
        }
    }

    @Override
    public SpoolWriter createWriter(SpoolKind kind) {
        try {
            Files.createDirectories(scratchDir);
            Path staging = scratchDir.resolve("db-spool-" + UUID.randomUUID() + ".tmp");
            return new StagingWriter(kind, staging);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public InputStream openInput(SpoolRef ref) throws IOException {
        // Stage back to a scratch file so a slow download never pins a pooled connection.
        Files.createDirectories(scratchDir);
        Path staging = Files.createTempFile(scratchDir, "db-spool-read-", ".tmp");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select content from tql_temp_spool where spool_id = ?")) {
            statement.setString(1, ref.id());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    Files.deleteIfExists(staging);
                    throw new IOException("Spool " + ref.id() + " not found");
                }
                try (InputStream content = row.getBinaryStream(1);
                        OutputStream out = new BufferedOutputStream(
                                Files.newOutputStream(staging))) {
                    content.transferTo(out);
                }
            }
        } catch (SQLException ex) {
            Files.deleteIfExists(staging);
            throw new IOException("Spool read failed: " + ex.getMessage(), ex);
        }
        return new java.io.FilterInputStream(Files.newInputStream(staging)) {
            @Override
            public void close() throws IOException {
                super.close();
                Files.deleteIfExists(staging);
            }
        };
    }

    @Override
    public void delete(SpoolRef ref) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "delete from tql_temp_spool where spool_id = ?")) {
            statement.setString(1, ref.id());
            statement.executeUpdate();
        } catch (SQLException ignored) {
            // best effort, like the file store's missing-target tolerance
        }
    }

    /** Streams to a local staging file; the row is inserted whole on close. */
    private final class StagingWriter implements SpoolWriter {

        private final SpoolKind kind;
        private final Path staging;
        private final OutputStream out;
        private long bytes;
        private long rows;
        private SpoolRef ref;

        private StagingWriter(SpoolKind kind, Path staging) throws IOException {
            this.kind = kind;
            this.staging = staging;
            this.out = new BufferedOutputStream(Files.newOutputStream(staging));
        }

        @Override
        public void write(byte[] data) throws IOException {
            bytes += data.length;
            if (bytes > maxBytes) {
                throw new IOException("Spool exceeds tesseraql.temp.maxBytes (" + maxBytes
                        + " bytes); use tesseraql.temp.store: blob for large exports");
            }
            out.write(data);
        }

        @Override
        public void incrementRows(long count) {
            rows += count;
        }

        @Override
        public void close() throws IOException {
            out.close();
            String id = UUID.randomUUID().toString();
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement insert = connection.prepareStatement(
                            "insert into tql_temp_spool (spool_id, kind, byte_size, row_count,"
                                    + " created_at, content) values (?, ?, ?, ?, ?, ?)");
                    InputStream content = Files.newInputStream(staging)) {
                insert.setString(1, id);
                insert.setString(2, kind.name());
                insert.setLong(3, bytes);
                insert.setLong(4, rows);
                insert.setTimestamp(5, java.sql.Timestamp.from(Instant.now()));
                insert.setBinaryStream(6, content, bytes);
                insert.executeUpdate();
            } catch (SQLException ex) {
                throw new IOException("Spool insert failed: " + ex.getMessage(), ex);
            } finally {
                Files.deleteIfExists(staging);
            }
            ref = new SpoolRef(id, kind, URI.create("tql-temp-db:" + id), bytes, rows,
                    Instant.now());
        }

        @Override
        public SpoolRef toRef() {
            if (ref == null) {
                throw new IllegalStateException("Spool writer not closed");
            }
            return ref;
        }
    }
}
