package io.tesseraql.compiler;

import io.tesseraql.core.files.ExportModel;
import io.tesseraql.core.files.FileCodec;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileWriteSpec;
import io.tesseraql.core.files.RowHandler;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A codec by name, media type and extension only: what a compile looks up
 * (docs/codec-discovery.md decision 2) and never calls. The compiler's test classpath carries
 * the csv codec alone, so a declaration naming another format compiles against a set that
 * holds one of these.
 */
public record NamedCodec(String format, String contentType, String extension)
        implements
            FileCodec {

    /** The workbook format, by name: the declaration arms read a template, never a codec. */
    public static NamedCodec excel() {
        return new NamedCodec("excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx");
    }

    /** An application's own format, as a module would register it. */
    public static NamedCodec fixedWidth() {
        return new NamedCodec("fixedwidth", "text/plain", ".txt");
    }

    @Override
    public void read(InputStream in, FileReadSpec spec, RowHandler handler) {
        throw new UnsupportedOperationException("a named codec is looked up, never read with");
    }

    @Override
    public void write(OutputStream out, FileWriteSpec spec, ExportModel model) {
        throw new UnsupportedOperationException("a named codec is looked up, never written with");
    }
}
