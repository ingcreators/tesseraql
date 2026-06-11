package io.tesseraql.core.files;

import java.util.List;

/**
 * How an uploaded file maps to rows (design ch. 28): the declared columns (matched by header
 * label when the file has a header row, positionally or by explicit position otherwise), whether
 * a header row is present, for workbook formats which sheet to read (null = first), and the
 * 1-based row the table starts at (the header row when present; rows above are skipped).
 */
public record FileReadSpec(List<ColumnMapping> columns, boolean headerRow, String sheet,
        int startRow) {

    public FileReadSpec {
        columns = columns == null ? List.of() : List.copyOf(columns);
        if (startRow < 1) {
            throw new IllegalArgumentException("startRow is 1-based");
        }
    }
}
