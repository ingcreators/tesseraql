package io.tesseraql.core.files;

import java.nio.file.Path;
import java.util.List;

/**
 * How exported rows render into a file (design ch. 28): the columns (selection, order, header
 * labels, positions, types and formats), for workbook formats the sheet name, an optional report
 * template, for placement mode the cell where data rows start, the app resource root, and the
 * locale/time zone used to render dates and numbers (null = platform defaults). Locale and zone
 * resolve per request - e.g. from the starting user's principal - so the same route serves
 * localized output.
 *
 * <p>Workbook modes: no template = a plain grid; template plus {@code startCell} = placement
 * mode (the YAML declares where each column lands, the template only carries layout and styles);
 * template without {@code startCell} = a jxls-annotated report driving its own iteration.
 *
 * <p>{@code splitBy} names a column that turns the export into one document per value, bundled as
 * a ZIP (docs/export-pipeline.md, decision 12). {@code groupBy} names a column the rows are grouped
 * by; a template reads the groups as
 * {@code groups}, each with its {@code key} and its own {@code rows}
 * (docs/export-pipeline.md, decision 3).
 *
 * <p>{@code resources} is the app home: the confinement boundary for everything a template
 * references beyond itself - stylesheets, images, and the {@code fonts/} directory the PDF codec
 * embeds (roadmap Phase 21). Codecs must never read files outside it.
 *
 * <p>{@code bom} asks a codec that writes a text stream to open it with the encoding's
 * byte-order mark ({@code EF BB BF} for the CSV codec's UTF-8), so a spreadsheet that sniffs
 * the mark decodes the file as UTF-8 rather than in its system code page. It is a property
 * of the stream, not of the rows: an export with no rows still carries it, and a split export
 * carries one per entry. It is a declaration resolved before it reaches here - never derived
 * from the locale (docs/csv-import.md, decision 10, seen from the writing side) - and a codec
 * whose output is not a text stream ignores it.
 */
public record FileWriteSpec(List<ColumnMapping> columns, String sheet, Path template,
        CellRef startCell, Path resources, String locale, String timezone, String groupBy,
        String splitBy, boolean bom) {

    public FileWriteSpec(List<ColumnMapping> columns, String sheet, Path template,
            CellRef startCell) {
        this(columns, sheet, template, startCell, null, null, null, null, null, false);
    }

    public FileWriteSpec(List<ColumnMapping> columns, String sheet, Path template,
            CellRef startCell, String locale, String timezone) {
        this(columns, sheet, template, startCell, null, locale, timezone, null, null, false);
    }

    public FileWriteSpec(List<ColumnMapping> columns, String sheet, Path template,
            CellRef startCell, Path resources, String locale, String timezone) {
        this(columns, sheet, template, startCell, resources, locale, timezone, null, null,
                false);
    }

    public FileWriteSpec {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /** This spec with the per-request locale and time zone resolved. */
    public FileWriteSpec withFormatting(String resolvedLocale, String resolvedTimezone) {
        return new FileWriteSpec(columns, sheet, template, startCell, resources,
                resolvedLocale, resolvedTimezone, groupBy, splitBy, bom);
    }
}
