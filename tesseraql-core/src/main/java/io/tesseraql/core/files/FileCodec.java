package io.tesseraql.core.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Map;

/**
 * Reads and writes one tabular file format for the {@code file-import} / {@code file-export}
 * recipes (design ch. 28). CSV ships with the framework; heavier formats (Excel via jxls/POI)
 * arrive as optional modules discovered through {@link java.util.ServiceLoader}, mirroring the
 * SCIM/SAML plugin pattern (design ch. 47).
 */
public interface FileCodec {

    /** The format key referenced as {@code format:} in route definitions, e.g. {@code csv}. */
    String format();

    /** The response content type for downloads. */
    String contentType();

    /** The filename extension including the dot, e.g. {@code .csv}. */
    String extension();

    /** Streams the file's records to the handler, one column-name-to-value map per row. */
    void read(InputStream in, FileReadSpec spec, RowHandler handler) throws Exception;

    /** Writes the rows (column-name-to-value maps) to the output. */
    void write(OutputStream out, FileWriteSpec spec, Iterator<Map<String, Object>> rows)
            throws IOException;

    /**
     * Whether this codec writes the rows through as they arrive for the given spec, rather than
     * holding them (docs/export-pipeline.md, decision 6).
     *
     * <p>The answer takes the spec because one codec can have modes that differ: the Excel codec
     * streams a plain grid and buffers both of its template modes, so a per-format flag would have
     * to be wrong for two of the three. A codec that buffers is exactly as exposed as a
     * materializing query, so an export through it is capped ({@link ExportRowCap}); a streaming
     * one is not, because nothing accumulates and a ceiling there would exist only to be raised.
     *
     * <p>Defaults to true: a codec that holds rows is the exception and should say so.
     */
    default boolean streams(FileWriteSpec spec) {
        return true;
    }
}
