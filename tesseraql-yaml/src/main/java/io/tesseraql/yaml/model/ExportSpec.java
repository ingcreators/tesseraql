package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The {@code export:} block of a {@code file-export} route (design ch. 28): the query whose rows
 * stream into a generated file, the format and filename, and an optional follow-up statement.
 *
 * <pre>
 * recipe: file-export
 * export:
 *   format: csv                  # or excel (tesseraql-excel module)
 *   filename: users.csv
 *   template: report.xlsx        # excel only: a jxls workbook colocated with the route
 *   sql:
 *     file: select-users.sql
 *     params:
 *       status: query.status
 *   after:
 *     timing: extract            # same transaction as the query, or 'download' (first fetch)
 *     sql:
 *       file: mark-extracted.sql
 * </pre>
 *
 * @param format   the file format key ({@code csv}, {@code excel}, ...)
 * @param filename the download filename (defaults to the route id plus the codec extension)
 * @param template for workbook formats, a report template colocated with the route
 * @param sheet    for workbook formats, the sheet name to write
 * @param columns  column order (empty = the query's column order)
 * @param sql      the extraction query
 * @param after    optional follow-up statement and its timing
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExportSpec(String format, String filename, String template, String sheet,
        List<String> columns, SqlBinding sql, AfterSpec after) {

    public ExportSpec {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /**
     * The follow-up statement: {@code extract} runs it in the extraction transaction (reliable,
     * prevents double extraction), {@code download} runs it once on the first file fetch.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AfterSpec(String timing, SqlBinding sql) {

        public String effectiveTiming() {
            return timing == null || timing.isBlank() ? "extract" : timing;
        }
    }
}
