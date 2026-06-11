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
}
