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
 * <p>{@code resources} is the app home: the confinement boundary for everything a template
 * references beyond itself - stylesheets, images, and the {@code fonts/} directory the PDF codec
 * embeds (roadmap Phase 21). Codecs must never read files outside it.
 */
public record FileWriteSpec(List<ColumnMapping> columns, String sheet, Path template,
        CellRef startCell, Path resources, String locale, String timezone) {

    public FileWriteSpec(List<ColumnMapping> columns, String sheet, Path template,
            CellRef startCell) {
        this(columns, sheet, template, startCell, null, null, null);
    }

    public FileWriteSpec(List<ColumnMapping> columns, String sheet, Path template,
            CellRef startCell, String locale, String timezone) {
        this(columns, sheet, template, startCell, null, locale, timezone);
    }

    public FileWriteSpec {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /** This spec with the per-request locale and time zone resolved. */
    public FileWriteSpec withFormatting(String resolvedLocale, String resolvedTimezone) {
        return new FileWriteSpec(columns, sheet, template, startCell, resources,
                resolvedLocale, resolvedTimezone);
    }
}
