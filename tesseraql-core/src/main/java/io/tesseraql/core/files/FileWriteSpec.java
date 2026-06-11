package io.tesseraql.core.files;

import java.nio.file.Path;
import java.util.List;

/**
 * How exported rows render into a file (design ch. 28): the columns (selection, order, header
 * labels, positions, types and formats), for workbook formats the sheet name, an optional report
 * template, for placement mode the cell where data rows start, and the locale/time zone used to
 * render dates and numbers (null = platform defaults). Locale and zone resolve per request -
 * e.g. from the starting user's principal - so the same route serves localized output.
 *
 * <p>Workbook modes: no template = a plain grid; template plus {@code startCell} = placement
 * mode (the YAML declares where each column lands, the template only carries layout and styles);
 * template without {@code startCell} = a jxls-annotated report driving its own iteration.
 */
public record FileWriteSpec(List<ColumnMapping> columns, String sheet, Path template,
        CellRef startCell, String locale, String timezone) {

    public FileWriteSpec(List<ColumnMapping> columns, String sheet, Path template,
            CellRef startCell) {
        this(columns, sheet, template, startCell, null, null);
    }

    public FileWriteSpec {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /** This spec with the per-request locale and time zone resolved. */
    public FileWriteSpec withFormatting(String resolvedLocale, String resolvedTimezone) {
        return new FileWriteSpec(columns, sheet, template, startCell,
                resolvedLocale, resolvedTimezone);
    }
}
