package io.tesseraql.operations.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.dialect.ResultRows;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.SpooledRows;
import io.tesseraql.core.spool.SpoolKind;
import io.tesseraql.core.spool.SpoolRef;
import io.tesseraql.core.spool.TempStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The rows a chunk step processes, one at a time, whatever produced them.
 *
 * <p>A chunk reads either its own keyset-ordered SELECT or an earlier step's spool
 * (docs/unified-sources.md decisions 19 and 19a). Routing the second case through a spool rather
 * than teaching the reader an {@code http:} arm leaves the reader with one thing to understand:
 * a spool is a spool, whoever filled it — a cross-datasource extract, or a paged API the
 * acquisition side already knows how to walk.
 *
 * <p>All shapes stream. None holds the result: the cursor is a held cursor and a spool is read
 * a row at a time, so the chunk's memory is its window, not its input.
 */
interface ChunkRows extends AutoCloseable {

    /** TQL-BATCH-5003: the step could not read its input. */
    TqlErrorCode READ_ERROR = new TqlErrorCode(TqlDomain.BATCH, 5003);

    /** Advances to the next row, returning false at the end. */
    boolean next();

    /**
     * The current row, keyed by its {@link ResultRows}-normalized column label — the one key a
     * writer bind or {@code chunk.key} names, the same label a route's rows carry.
     */
    Map<String, Object> row();

    @Override
    void close();

    /** The rows of a held JDBC cursor, labels normalized per dialect ({@link ResultRows}). */
    static ChunkRows of(ResultSet rows, String dialect) {
        return new ChunkRows() {

            @Override
            public boolean next() {
                try {
                    return rows.next();
                } catch (SQLException ex) {
                    throw failure(ex);
                }
            }

            @Override
            public Map<String, Object> row() {
                try {
                    ResultSetMetaData metaData = rows.getMetaData();
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int col = 1; col <= metaData.getColumnCount(); col++) {
                        row.put(ResultRows.label(dialect, metaData.getColumnLabel(col)),
                                rows.getObject(col));
                    }
                    return row;
                } catch (SQLException ex) {
                    throw failure(ex);
                }
            }

            @Override
            public void close() {
                try {
                    rows.close();
                } catch (SQLException ignored) {
                    // closing the connection reclaims the cursor regardless
                }
            }
        };
    }

    /**
     * The rows of an earlier step's spool, whichever encoding filled it.
     *
     * <p>The spool is the consistent snapshot a rerun re-reads — a property a SQL-reading chunk
     * cannot have, since the source table moves on. A SQL extract arrives as
     * {@link SpooledRows}' tagged binary, so a decimal keeps its scale and a temporal its type;
     * an {@code http:} acquisition arrives as JSONL, faithful there because the data was JSON to
     * begin with. Closing releases the reader only — the spool itself outlives the step, which
     * is what lets a rerun hand it to the load step unchanged.
     */
    static ChunkRows of(TempStore tempStore, SpoolRef ref, ObjectMapper mapper) {
        if (ref.kind() == SpoolKind.BINARY) {
            return of(SpooledRows.open(tempStore, ref).iterator());
        }
        InputStream stream;
        try {
            stream = tempStore.openInput(ref);
        } catch (IOException ex) {
            throw TqlException.builder(READ_ERROR)
                    .message("the step's spool could not be opened: " + ex.getMessage())
                    .cause(ex)
                    .build();
        }
        BufferedReader lines = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        return new ChunkRows() {

            private Map<String, Object> current;

            @Override
            @SuppressWarnings("unchecked")
            public boolean next() {
                try {
                    String line;
                    while ((line = lines.readLine()) != null && line.isBlank()) {
                        // a trailing newline is not a row
                    }
                    if (line == null) {
                        current = null;
                        return false;
                    }
                    current = mapper.readValue(line, Map.class);
                    return true;
                } catch (IOException ex) {
                    throw TqlException.builder(READ_ERROR)
                            .message("the step's spool could not be read: " + ex.getMessage())
                            .cause(ex)
                            .build();
                }
            }

            @Override
            public Map<String, Object> row() {
                return current;
            }

            @Override
            public void close() {
                try {
                    lines.close();
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
        };
    }

    /** The rows of an open iterator; closing releases the iterator's stream, never the spool. */
    private static ChunkRows of(Iterator<Map<String, Object>> rows) {
        return new ChunkRows() {

            private Map<String, Object> current;

            @Override
            public boolean next() {
                if (rows.hasNext()) {
                    current = rows.next();
                    return true;
                }
                current = null;
                return false;
            }

            @Override
            public Map<String, Object> row() {
                return current;
            }

            @Override
            public void close() {
                if (rows instanceof AutoCloseable closeable) {
                    try {
                        closeable.close();
                    } catch (Exception ex) {
                        throw TqlException.builder(READ_ERROR)
                                .message("the step's spool reader could not be released: "
                                        + ex.getMessage())
                                .cause(ex)
                                .build();
                    }
                }
            }
        };
    }

    private static TqlException failure(SQLException ex) {
        return TqlException.builder(READ_ERROR)
                .message("the step's reader failed: " + ex.getMessage())
                .cause(ex)
                .build();
    }
}
