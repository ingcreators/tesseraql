package io.tesseraql.core.files;

import io.tesseraql.core.dialect.Labels;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * An extraction's {@link ResultSet} as an iterator of label-normalized row maps, counted
 * against the export's ceiling.
 *
 * <p>This existed three times — the synchronous producer had two private copies (one per call
 * site, differing only in a side effect) and the asynchronous transfer service a third — and a
 * fix to any one of them had to be re-discovered in the others. One class, and the one real
 * variation is explicit: the caller reads {@link #count()} when the walk is done.
 *
 * <p>Values are raw JDBC objects on purpose: a codec's {@code ColumnValues} formatting decides
 * how temporals and numbers render, which is the export contract — unlike a JSON response,
 * which converts through {@code dialect.ResultRows.value}. Labels go through the same
 * {@link Labels#normalize} every other surface uses.
 */
public final class ResultSetRows implements Iterator<Map<String, Object>> {

    private final ResultSet resultSet;
    private final ExportRowCap cap;
    private final TqlErrorCode readError;
    private final List<String> labels;
    private Boolean pending;
    private long count;

    /**
     * @param readError the caller's domain code for a failed read — the one thing the three
     *                  copies legitimately did not share
     */
    public ResultSetRows(ResultSet resultSet, String dialect, ExportRowCap cap,
            TqlErrorCode readError) throws SQLException {
        this.resultSet = resultSet;
        this.cap = cap;
        this.readError = readError;
        ResultSetMetaData metaData = resultSet.getMetaData();
        List<String> columnLabels = new ArrayList<>();
        for (int col = 1; col <= metaData.getColumnCount(); col++) {
            columnLabels.add(Labels.normalize(dialect, metaData.getColumnLabel(col)));
        }
        this.labels = List.copyOf(columnLabels);
    }

    /** How many rows have been handed over. */
    public long count() {
        return count;
    }

    @Override
    public boolean hasNext() {
        try {
            if (pending == null) {
                pending = resultSet.next();
            }
            // The cap is asked before the row is handed over, so warn mode truncates cleanly
            // and fail mode raises before the codec has accepted a row it cannot hold.
            return pending && cap.admits(count);
        } catch (SQLException ex) {
            throw new TqlException(readError, "Export query failed: " + ex.getMessage());
        }
    }

    @Override
    public Map<String, Object> next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        pending = null;
        try {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int col = 1; col <= labels.size(); col++) {
                row.put(labels.get(col - 1), resultSet.getObject(col));
            }
            count++;
            return row;
        } catch (SQLException ex) {
            throw new TqlException(readError, "Export query failed: " + ex.getMessage());
        }
    }
}
