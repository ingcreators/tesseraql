package io.tesseraql.core.files;

import java.util.List;

/**
 * Shared column-position resolution for tabular codecs (design ch. 28): an explicit
 * {@code column:} position always wins; otherwise a header row resolves each column by its
 * header label (missing labels yield -1, read as null), and without a header row the declared
 * order is the position.
 */
public final class Tables {

    private Tables() {
    }

    /** The 0-based position per column; -1 when a header label is not present in the file. */
    public static int[] positions(List<ColumnMapping> columns, List<String> header) {
        int[] positions = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            ColumnMapping column = columns.get(i);
            if (column.index() != null) {
                positions[i] = column.index();
            } else if (header != null) {
                positions[i] = indexOf(header, column.effectiveHeader());
            } else {
                positions[i] = i;
            }
        }
        return positions;
    }

    private static int indexOf(List<String> header, String label) {
        for (int i = 0; i < header.size(); i++) {
            if (label.equals(header.get(i) == null ? null : header.get(i).trim())) {
                return i;
            }
        }
        return -1;
    }
}
