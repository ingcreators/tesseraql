package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The {@code import:} block of a {@code file-import} route (design ch. 28): which tabular format
 * the uploaded file uses, how its columns map to SQL parameters, where the table starts, and
 * what happens on a failing row.
 *
 * <pre>
 * recipe: file-import
 * import:
 *   format: csv                # or excel (tesseraql-excel module)
 *   columns:                   # see ColumnSpec: header labels and positions stay in the YAML
 *     - { name: productName, header: 商品名 }
 *     - { name: qty, column: D }
 *   headerRow: true            # default true
 *   startRow: 4                # 1-based; rows above the table (titles) are skipped
 *   onError: rollback          # default; 'skip' records failing rows and commits the rest
 *   sql:
 *     file: upsert-product.sql # runs once per row; params are the column names
 * </pre>
 *
 * @param format    the file format key ({@code csv}, {@code excel}, ...)
 * @param columns   declared columns; empty uses the header labels as parameter names
 * @param headerRow whether the table starts with a header row (default true)
 * @param startRow  the 1-based row the table starts at (default 1)
 * @param sheet     for workbook formats, the sheet to read (null = first)
 * @param onError   {@code rollback} (default: any failing row rolls back the whole import) or
 *                  {@code skip} (failing rows are recorded, the rest commits)
 * @param sql       the per-row statement
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImportSpec(String format, List<ColumnSpec> columns, Boolean headerRow,
        Integer startRow, String sheet, String locale, String onError, SqlBinding sql) {

    public ImportSpec {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    public boolean effectiveHeaderRow() {
        return headerRow == null || headerRow;
    }

    public int effectiveStartRow() {
        return startRow == null ? 1 : startRow;
    }

    public String effectiveOnError() {
        return onError == null || onError.isBlank() ? "rollback" : onError;
    }

    /** The core read spec with column references resolved. */
    public io.tesseraql.core.files.FileReadSpec toReadSpec() {
        return new io.tesseraql.core.files.FileReadSpec(
                columns.stream().map(ColumnSpec::toMapping).toList(),
                effectiveHeaderRow(), sheet, effectiveStartRow());
    }
}
