package io.tesseraql.core.files;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared read skeleton of every tabular codec (design ch. 28): skip the rows above
 * {@code startRow}, take the header row when one is declared, resolve the declared columns to
 * positions (or derive them from the header when the import declares none), then hand each data
 * row to the {@link RowHandler} with 1-based row numbers.
 *
 * <p>Only cell access differs between formats, so that is all a codec supplies: a
 * {@link Cells} over its own row type reads the header labels and one value at a resolved
 * position. Everything else - the header-match contract of {@link Tables}, the derived-column
 * fallback, the row numbering - is identical for CSV, workbooks, and any format added later.
 */
public final class TabularReader {

    private TabularReader() {
    }

    /**
     * Cell access for one file format, over the format's own row type.
     *
     * @param <R> the format's row type
     */
    public interface Cells<R> {

        /** The file's header labels, in file order, read from the header row. */
        List<String> header(R row);

        /**
         * The value of one column in one data row.
         *
         * @param row      the data row
         * @param position the resolved 0-based position, or -1 when the column matched no header
         * @param column   the column being read (its type drives native cell handling)
         * @return the cell value, or null when the row has no cell at that position
         */
        Object value(R row, int position, ColumnMapping column);
    }

    /**
     * Where data row {@code row} sits in the file, counted the way the author counts
     * (docs/csv-import.md decision 8). It lives here because the skip is here: the reader
     * consumes {@code startRow - 1} rows and then the header row, and any other copy of that
     * arithmetic would drift from the loop the moment either changes.
     *
     * @param row the 1-based data-row ordinal the reader handed the row handler
     */
    public static long fileRow(FileReadSpec spec, long row) {
        return spec.startRow() - 1 + (spec.headerRow() ? 1 : 0) + row;
    }

    /**
     * Reads a file's rows into parameter maps. The iterator is positioned at the first row of the
     * file; the reader consumes the skipped rows, the header row, and every data row after it.
     */
    public static <R> void read(Iterator<R> rows, FileReadSpec spec, Cells<R> cells,
            RowHandler handler) throws Exception {
        for (int skip = 1; skip < spec.startRow() && rows.hasNext(); skip++) {
            rows.next();
        }
        List<String> header = null;
        if (spec.headerRow() && rows.hasNext()) {
            header = cells.header(rows.next());
        }
        boolean declared = !spec.columns().isEmpty();
        List<ColumnMapping> columns = declared || header == null
                ? spec.columns()
                : header.stream().map(ColumnMapping::of).toList();
        int[] positions = Tables.positions(columns, header);
        if (declared) {
            Tables.requireDeclaredHeadersMatched(columns, header, positions);
        }
        long rowNumber = 0;
        while (rows.hasNext()) {
            R row = rows.next();
            rowNumber++;
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                values.put(columns.get(i).name(),
                        cells.value(row, positions[i], columns.get(i)));
            }
            handler.row(rowNumber, values);
        }
    }
}
