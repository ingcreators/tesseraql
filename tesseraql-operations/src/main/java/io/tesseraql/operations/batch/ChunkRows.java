package io.tesseraql.operations.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
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
import java.util.LinkedHashMap;
import java.util.Locale;
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
 * <p>Both shapes stream. Neither holds the result: the cursor is a held cursor and the spool is
 * read line by line, so the chunk's memory is its window, not its input.
 */
interface ChunkRows extends AutoCloseable {

    /** TQL-BATCH-5003: the step could not read its input. */
    TqlErrorCode READ_ERROR = new TqlErrorCode(TqlDomain.BATCH, 5003);

    /** Advances to the next row, returning false at the end. */
    boolean next();

    /** The current row, keyed by column name (lowercase aliases included). */
    Map<String, Object> row();

    @Override
    void close();

    /** The rows of a held JDBC cursor. */
    static ChunkRows of(ResultSet rows) {
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
                        String label = metaData.getColumnLabel(col);
                        Object value = rows.getObject(col);
                        row.put(label, value);
                        // Oracle answers uppercase labels; binds are written lowercase.
                        row.putIfAbsent(label.toLowerCase(Locale.ROOT), value);
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
     * The rows of an earlier step's spool, read as JSONL.
     *
     * <p>The spool is the consistent snapshot a rerun re-reads — a property a SQL-reading chunk
     * cannot have, since the source table moves on. Values round-trip through JSON, so a writer
     * binding a date or a decimal casts in SQL, the same rule {@code chunk.after} already
     * carries; the caveat bites harder here because a JSON number reaching a numeric column is
     * the common case rather than the exception.
     */
    static ChunkRows of(TempStore tempStore, SpoolRef ref, ObjectMapper mapper) {
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
                    Map<String, Object> parsed = mapper.readValue(line, Map.class);
                    Map<String, Object> row = new LinkedHashMap<>(parsed);
                    parsed.forEach(
                            (name, value) -> row.putIfAbsent(name.toLowerCase(Locale.ROOT), value));
                    current = row;
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

    private static TqlException failure(SQLException ex) {
        return TqlException.builder(READ_ERROR)
                .message("the step's reader failed: " + ex.getMessage())
                .cause(ex)
                .build();
    }
}
