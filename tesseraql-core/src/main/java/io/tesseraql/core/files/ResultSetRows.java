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
 * <p>Values are each column's kind on purpose (docs/temporal-semantics.md T1): a wall clock as a
 * {@code LocalDateTime}, an instant as an {@code OffsetDateTime}, read through
 * {@link io.tesseraql.core.dialect.JdbcValues} so no value depends on the JVM's zone — and left
 * as objects, because a codec's {@code ColumnValues} formatting decides how temporals and
 * numbers render, which is the export contract. Raw {@code getObject} values used to arrive here,
 * and a zoneless {@code timestamp} then reached the codec as a {@code java.sql.Timestamp} built
 * in the JVM's zone, which {@code type: datetime} moved by (declared zone − host zone). Labels
 * go through the same {@link Labels#normalize} every other surface uses.
 */
public final class ResultSetRows implements Iterator<Map<String, Object>>, NamedRows {

    private final ResultSet resultSet;
    private final ExportRowCap cap;
    private final TqlErrorCode readError;
    private final List<String> labels;
    private final io.tesseraql.core.dialect.JdbcValues.Reader values;
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
        this.values = io.tesseraql.core.dialect.JdbcValues.reader(metaData);
    }

    /** How many rows have been handed over. */
    public long count() {
        return count;
    }

    /** The column labels, from the metadata — known before the first row, and with no row. */
    @Override
    public List<String> columns() {
        return labels;
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
            throw new TqlException(readError, "Export query failed: " + ex.getMessage(), ex);
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
                row.put(labels.get(col - 1), values.read(resultSet, col));
            }
            count++;
            return row;
        } catch (SQLException ex) {
            throw new TqlException(readError, "Export query failed: " + ex.getMessage(), ex);
        }
    }
}
