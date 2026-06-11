package io.tesseraql.core.spool;

import java.io.IOException;
import java.io.InputStream;

/**
 * Temp storage for large rowsets and payloads kept off-heap (design ch. 28.4).
 *
 * <p>This is the SPI behind {@code query-spool} / {@code query-export} and batch intermediate
 * results. The first implementation is {@link FileTempStore}; object-storage and database-backed
 * stores plug in behind the same interface later.
 */
public interface TempStore {

    /** Opens a writer for a new spool of the given kind. */
    SpoolWriter createWriter(SpoolKind kind);

    /** Opens an input stream over previously spooled data. */
    InputStream openInput(SpoolRef ref) throws IOException;

    /** Deletes the spooled data, ignoring a missing target. */
    void delete(SpoolRef ref);
}
