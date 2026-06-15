package io.tesseraql.core.blob;

import java.io.Closeable;
import java.io.IOException;

/**
 * Streams bytes into a new durable object, computing its size and checksum as it writes — the
 * durable counterpart of {@link io.tesseraql.core.spool.SpoolWriter}.
 */
public interface BlobWriter extends Closeable {

    /** Appends a chunk to the object. */
    void write(byte[] data) throws IOException;

    /** The reference to the written object. Valid only after {@link #close()}. */
    BlobRef toRef();
}
