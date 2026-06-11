package io.tesseraql.core.files;

import java.util.Locale;

/**
 * A workbook cell position in 0-based coordinates, parsed from the familiar {@code B5} notation
 * (design ch. 28): where a placement-mode export starts writing data rows.
 */
public record CellRef(int row, int col) {

    /** Parses {@code B5}-style references; the result is 0-based. */
    public static CellRef parse(String reference) {
        String ref = reference.trim().toUpperCase(Locale.ROOT);
        if (!ref.matches("[A-Z]+[0-9]+")) {
            throw new IllegalArgumentException("Not a cell reference: " + reference);
        }
        int split = 0;
        while (Character.isLetter(ref.charAt(split))) {
            split++;
        }
        int col = ColumnMapping.parseColumn(ref.substring(0, split));
        int row = Integer.parseInt(ref.substring(split)) - 1;
        if (row < 0) {
            throw new IllegalArgumentException("Row numbers are 1-based: " + reference);
        }
        return new CellRef(row, col);
    }
}
