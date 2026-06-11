package io.tesseraql.core.files;

import java.nio.file.Path;
import java.util.List;

/**
 * How exported rows render into a file (design ch. 28): the column order (empty = the query's
 * column order), for workbook formats the sheet name, and optionally a report template (e.g. a
 * jxls-annotated workbook colocated with the route) that replaces the plain tabular layout.
 */
public record FileWriteSpec(List<String> columns, String sheet, Path template) {

    public FileWriteSpec {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
