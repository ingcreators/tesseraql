package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@code export:} block of a {@code file-export} route (design ch. 28): how the rows stream
 * into a generated file - the format, filename and column layout - and an optional follow-up
 * statement. It never says what to read: the rows come from {@code sources.main} beside it, on
 * this recipe as on every other (docs/unified-sources.md decision 7).
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
 *   after:
 *     timing: extract          # same transaction as the query, or 'download' (first fetch)
 *     sql:
 *       file: mark-extracted.sql
 *
 * sources:                     # the extraction, beside the output block
 *   main:
 *     sql:
 *       file: select-orders.sql
 * </pre>
 *
 * @param format    the file format key ({@code csv}, {@code excel}, ...)
 * @param filename  the download filename (defaults to the route id plus the codec extension)
 * @param template  a workbook template colocated with the route
 * @param sheet     for workbook formats, the sheet to write
 * @param startCell where data rows start (placement mode), e.g. {@code B5}
 * @param columns   column selection/order, header labels and placement positions
 * @param locale    the locale date and number patterns render in
 * @param timezone  the zone date and time values render in
 * @param after     optional follow-up statement and its timing
 * @param maxRows    the ceiling for a format that holds every row before it writes (pdf, and the
 *                   workbook template modes); defaults to
 *                   {@code tesseraql.resultMaterialization.maxRows}, and a negative value opts
 *                   out. A streaming format is never capped
 *                   (docs/export-pipeline.md, decision 7)
 * @param onOverflow {@code fail} (default) or {@code warn}, which truncates at the cap
 * @param splitBy    a column that splits the export into one document per value, delivered as a
 *                   single ZIP; {@code filename} must carry {@code {key}}
 *                   (docs/export-pipeline.md, decision 12)
 * @param groupBy    a column the rows are grouped by, exposed to the template as {@code groups};
 *                   the extraction must be ordered by it (docs/export-pipeline.md, decision 3)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExportSpec(String format, String filename, String template, String sheet,
        String startCell, List<ColumnSpec> columns, String locale, String timezone,
        AfterSpec after, Integer maxRows, String onOverflow, String groupBy, String splitBy) {

    public ExportSpec {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /**
     * The core write spec with column and cell references resolved; {@code resources} is the
     * app home, the confinement root for template-referenced resources (fonts, stylesheets).
     */
    public io.tesseraql.core.files.FileWriteSpec toWriteSpec(Path templatePath, Path resources) {
        return new io.tesseraql.core.files.FileWriteSpec(
                columns.stream().map(ColumnSpec::toMapping).toList(),
                sheet, templatePath,
                startCell == null || startCell.isBlank()
                        ? null
                        : io.tesseraql.core.files.CellRef.parse(startCell),
                resources, null, null, groupBy, splitBy);
    }

    /**
     * The follow-up statement: {@code extract} runs it in the extraction transaction (reliable,
     * prevents double extraction), {@code download} runs it once on the first file fetch.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AfterSpec(String timing, Binding.SqlArm sql) {

        public String effectiveTiming() {
            return timing == null || timing.isBlank() ? "extract" : timing;
        }
    }
}
