package io.tesseraql.core.files;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.List;

/**
 * Shared column-position resolution for tabular codecs (design ch. 28): an explicit
 * {@code column:} position always wins; otherwise a header row resolves each column by its
 * header label (missing labels yield -1, read as null), and without a header row the declared
 * order is the position.
 */
public final class Tables {

    /** TQL-LD-2826: an explicitly declared column's header label is absent from the file. */
    private static final TqlErrorCode UNMATCHED_HEADER = new TqlErrorCode(TqlDomain.LD, 2826);

    private Tables() {
    }

    /**
     * Fails an import whose explicitly declared column resolves to no header (position -1) when a
     * header row is present — otherwise every row would silently read {@code null} for that column
     * (a supplier renaming {@code Order No} to {@code OrderNo} imports a full file of nulls). Only
     * declared columns ({@code index() == null}) are checked; columns derived from the header
     * itself always match.
     */
    public static void requireDeclaredHeadersMatched(List<ColumnMapping> columns,
            List<String> header, int[] positions) {
        if (header == null) {
            return;
        }
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).index() == null && positions[i] < 0) {
                throw new TqlException(UNMATCHED_HEADER, "Column '" + columns.get(i).name()
                        + "' maps to header '" + columns.get(i).effectiveHeader()
                        + "' which is not present in the file header");
            }
        }
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
