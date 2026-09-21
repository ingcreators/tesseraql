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
 * @param bom        whether a {@code csv} export opens with the UTF-8 byte-order mark, so a
 *                   spreadsheet that sniffs the mark decodes the file as UTF-8; absent means
 *                   no mark, because a mark is a declaration a reader must expect and many
 *                   machine readers do not. The linter refuses it on {@code excel} and
 *                   {@code pdf} ({@code INAPPLICABLE_EXPORT_OPTION})
 * @param statusWhen conditional statuses a {@code query-export} answers instead of a document:
 *                   the first arm whose condition is truthy over the route's sources
 *                   ({@code header.rowCount == 0}) — judged before the extraction opens,
 *                   and again with {@code main.rowCount} once the rows are written. A
 *                   {@code file-export} answers 202 before its rows are read and a job step
 *                   answers no request, so both refuse the key ({@code TQL-YAML-1041})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExportSpec(String format, String filename, String template, String sheet,
        String startCell, List<ColumnSpec> columns, String locale, String timezone,
        AfterSpec after, Integer maxRows, String onOverflow, String groupBy, String splitBy,
        Boolean bom, List<ResponseSpec.StatusWhen> statusWhen) {

    public ExportSpec {
        columns = columns == null ? List.of() : List.copyOf(columns);
        statusWhen = statusWhen == null ? List.of() : List.copyOf(statusWhen);
    }

    /** The block without status arms — the positional shape every caller before them wrote. */
    public ExportSpec(String format, String filename, String template, String sheet,
            String startCell, List<ColumnSpec> columns, String locale, String timezone,
            AfterSpec after, Integer maxRows, String onOverflow, String groupBy,
            String splitBy, Boolean bom) {
        this(format, filename, template, sheet, startCell, columns, locale, timezone, after,
                maxRows, onOverflow, groupBy, splitBy, bom, List.of());
    }

    /** This block with its columns replaced — how the loader stamps a column's {@code domain:}. */
    public ExportSpec withColumns(List<ColumnSpec> resolved) {
        if (resolved == columns) {
            return this;
        }
        return new ExportSpec(format, filename, template, sheet, startCell, resolved, locale,
                timezone, after, maxRows, onOverflow, groupBy, splitBy, bom, statusWhen);
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
                resources, null, null, groupBy, splitBy, Boolean.TRUE.equals(bom),
                messages(resources));
    }

    /**
     * The message texts a print template reads through {@code #{key}}: the app home's
     * {@code messages/} catalogs over the framework's built-ins, read once per document in
     * the locale the document renders in (docs/printable-documents.md). The live catalog
     * re-parses only when the directory changes, so a Studio message edit reaches the next
     * document without a restart. No app home, no messages.
     */
    private static io.tesseraql.core.files.DocumentMessages messages(Path resources) {
        if (resources == null) {
            return null;
        }
        Path messagesDir = resources.resolve("messages");
        return tag -> {
            io.tesseraql.yaml.i18n.MessageCatalog catalog = io.tesseraql.yaml.i18n.MessageCatalog
                    .live(messagesDir)
                    .withFallback(io.tesseraql.yaml.i18n.I18nSettings.builtinCatalog());
            return key -> catalog.resolve(tag, key);
        };
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
