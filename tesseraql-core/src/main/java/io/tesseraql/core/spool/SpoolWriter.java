package io.tesseraql.core.spool;

import java.io.Closeable;
import java.io.IOException;

/**
 * Writes data into a temp spool, then yields a {@link SpoolRef} (design ch. 28.4).
 *
 * <p>Callers stream content with {@link #write(byte[])} row by row (bounded memory) and call
 * {@link #incrementRows(long)} to track the row count. After {@link #close()} the reference is
 * available from {@link #toRef()}.
 */
public interface SpoolWriter extends Closeable {

    void write(byte[] data) throws IOException;

    void incrementRows(long count);

    /** Returns the spool reference. Valid after {@link #close()}. */
    SpoolRef toRef();
}
