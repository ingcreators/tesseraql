package io.tesseraql.pipeline;

import java.io.IOException;
import java.io.InputStream;

/**
 * One uploaded part: a name, a content type, and a stream you may read once
 * (docs/vertx-native.md decision 1).
 *
 * <p>This was {@code jakarta.activation.DataHandler}, the type Camel's attachment API used — kept
 * through the replacement, wrapping anonymous {@code DataSource}s whose write halves existed to
 * throw, on a dependency this module never declared: {@code jakarta.activation} reached it
 * through the identity module, the yaml module, and the mail library the yaml module declares.
 * The three readers only ever asked for these three things.
 */
public interface Part {

    /** The file name the client gave the part, or the field name when it gave none. */
    String filename();

    /** The declared media type, defaulted to {@code application/octet-stream}. */
    String contentType();

    /** Opens the content. Each call opens anew where the source allows it. */
    InputStream open() throws IOException;

    /** A file on disk as an uploaded part, under the name the client gave it. */
    static Part of(java.nio.file.Path file, String contentType, String filename) {
        String type = contentType == null ? "application/octet-stream" : contentType;
        String name = filename == null ? file.getFileName().toString() : filename;
        return new Part() {
            @Override
            public String filename() {
                return name;
            }

            @Override
            public String contentType() {
                return type;
            }

            @Override
            public InputStream open() throws IOException {
                return java.nio.file.Files.newInputStream(file);
            }
        };
    }

}
