package io.tesseraql.core.files;

import java.util.List;

/**
 * How an uploaded file maps to rows (design ch. 28): the declared column names (positional when
 * the file has no header row, otherwise matched against the header), whether the first row is a
 * header, and for workbook formats which sheet to read (null = first).
 */
public record FileReadSpec(List<String> columns, boolean headerRow, String sheet) {

    public FileReadSpec {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
