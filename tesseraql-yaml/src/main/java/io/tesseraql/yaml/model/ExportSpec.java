package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@code export:} block of a {@code file-export} route (design ch. 28): the query whose rows
 * stream into a generated file, the format, filename and column layout, and an optional
 * follow-up statement.
 *
 * <p>Workbook output has three modes, keeping the column correspondence in the YAML wherever
 * possible: no template renders a plain grid; a template plus {@code startCell} is placement
 * mode - the template carries only layout and styles while the YAML declares where each column
 * lands; a jx:-annotated template without {@code startCell} is a full jxls report (advanced).
 *
 * <pre>
 * recipe: file-export
 * export:
 *   format: excel
 *   filename: orders.xlsx
 *   template: orders.xlsx      # styles and titles only (placement mode)
 *   sheet: 受注一覧
 *   startCell: B5              # data rows start here
 *   columns:
 *     - { name: order_no, column: B }
 *     - { name: qty,      column: D }
 *   sql:
 *     file: select-orders.sql
 *   after:
 *     timing: extract          # same transaction as the query, or 'download' (first fetch)
 *     sql:
 *       file: mark-extracted.sql
 * </pre>
 *
 * @param format    the file format key ({@code csv}, {@code excel}, ...)
 * @param filename  the download filename (defaults to the route id plus the codec extension)
 * @param template  a workbook template colocated with the route
 * @param sheet     for workbook formats, the sheet to write
 * @param startCell where data rows start (placement mode), e.g. {@code B5}
 * @param columns   column selection/order, header labels and placement positions
 * @param sql       the extraction query
 * @param after     optional follow-up statement and its timing
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExportSpec(String format, String filename, String template, String sheet,
        String startCell, List<ColumnSpec> columns, String locale, String timezone,
        SqlBinding sql, AfterSpec after) {

    public ExportSpec {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /** The core write spec with column and cell references resolved. */
    public io.tesseraql.core.files.FileWriteSpec toWriteSpec(Path templatePath) {
        return new io.tesseraql.core.files.FileWriteSpec(
                columns.stream().map(ColumnSpec::toMapping).toList(),
                sheet, templatePath,
                startCell == null || startCell.isBlank()
                        ? null
                        : io.tesseraql.core.files.CellRef.parse(startCell));
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
