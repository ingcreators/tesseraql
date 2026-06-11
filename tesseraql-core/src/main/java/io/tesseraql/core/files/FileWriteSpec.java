package io.tesseraql.core.files;

import java.nio.file.Path;
import java.util.List;

/**
 * How exported rows render into a file (design ch. 28): the columns (selection, order, header
 * labels and - in placement mode - explicit positions), for workbook formats the sheet name, an
 * optional report template, and for placement mode the cell where data rows start.
 *
 * <p>Workbook modes: no template = a plain grid; template plus {@code startCell} = placement
 * mode (the YAML declares where each column lands, the template only carries layout and styles);
 * template without {@code startCell} = a jxls-annotated report driving its own iteration.
 */
public record FileWriteSpec(List<ColumnMapping> columns, String sheet, Path template,
        CellRef startCell) {

    public FileWriteSpec {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
