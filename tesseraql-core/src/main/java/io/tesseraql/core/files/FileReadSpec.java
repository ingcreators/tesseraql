package io.tesseraql.core.files;

import java.util.List;

/**
 * How an uploaded file maps to rows (design ch. 28): the declared columns (matched by header
 * label when the file has a header row, positionally or by explicit position otherwise), whether
 * a header row is present, for workbook formats which sheet to read (null = first), the 1-based
 * row the table starts at (the header row when present; rows above are skipped), and the locale
 * used to parse localized numbers (null = platform default).
 */
public record FileReadSpec(List<ColumnMapping> columns, boolean headerRow, String sheet,
        int startRow, String locale) {

    public FileReadSpec(List<ColumnMapping> columns, boolean headerRow, String sheet,
            int startRow) {
        this(columns, headerRow, sheet, startRow, null);
    }

    public FileReadSpec {
        columns = columns == null ? List.of() : List.copyOf(columns);
        if (startRow < 1) {
            throw new IllegalArgumentException("startRow is 1-based");
        }
    }

    /** This spec with the per-request locale resolved (e.g. from the caller's principal). */
    public FileReadSpec withLocale(String resolvedLocale) {
        return new FileReadSpec(columns, headerRow, sheet, startRow, resolvedLocale);
    }
}
