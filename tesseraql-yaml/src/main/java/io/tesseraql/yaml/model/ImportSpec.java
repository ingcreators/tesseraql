package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The {@code import:} block of a {@code file-import} route (design ch. 28): which tabular format
 * the uploaded file uses, how its columns map to SQL parameters, and what happens on a failing
 * row.
 *
 * <pre>
 * recipe: file-import
 * import:
 *   format: csv                # or excel (tesseraql-excel module)
 *   columns: [loginId, email]  # positional when headerRow is false, else matched to the header
 *   headerRow: true            # default true
 *   onError: rollback          # default; 'skip' records failing rows and commits the rest
 *   sql:
 *     file: upsert-user.sql    # runs once per row; params are the column names
 * </pre>
 *
 * @param format    the file format key ({@code csv}, {@code excel}, ...)
 * @param columns   declared column names; empty uses the header names as-is
 * @param headerRow whether the first row is a header (default true)
 * @param sheet     for workbook formats, the sheet to read (null = first)
 * @param onError   {@code rollback} (default: any failing row rolls back the whole import) or
 *                  {@code skip} (failing rows are recorded, the rest commits)
 * @param sql       the per-row statement
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImportSpec(String format, List<String> columns, Boolean headerRow, String sheet,
        String onError, SqlBinding sql) {

    public ImportSpec {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    public boolean effectiveHeaderRow() {
        return headerRow == null || headerRow;
    }

    public String effectiveOnError() {
        return onError == null || onError.isBlank() ? "rollback" : onError;
    }
}
