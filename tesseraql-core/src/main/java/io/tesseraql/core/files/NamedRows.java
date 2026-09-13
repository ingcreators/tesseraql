package io.tesseraql.core.files;

import java.util.List;

/**
 * A row source that knows its column names before the first row (docs/export-hygiene.md P5):
 * a JDBC cursor from its metadata, a spool from its header. A codec asks it for the header of an
 * export with no rows, which a row-derived header could never know — a zero-row csv was 0 bytes,
 * a zero-row grid a cell-less workbook, and the consumers that read them (pandas, PostgreSQL
 * {@code COPY … HEADER MATCH}, this framework's own {@code file-import}) saw no table at all.
 *
 * <p>An enriched row set does not implement it: enrichment adds keys no metadata can know, so
 * an export that enriches and wants a stable header declares {@code columns:}.
 */
public interface NamedRows {

    /** The column names, in the order the rows carry them; empty when the source knows none. */
    List<String> columns();
}
