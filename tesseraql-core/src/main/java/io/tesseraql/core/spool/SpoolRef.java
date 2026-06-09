package io.tesseraql.core.spool;

import java.net.URI;
import java.time.Instant;

/**
 * A reference to spooled data held outside the heap (design ch. 28.4). It carries only the
 * location and metadata, never the data itself, so large results can be passed between steps and
 * to endpoints without materializing them in memory.
 *
 * @param id        unique spool id
 * @param kind      the content kind
 * @param uri       the location of the spooled data
 * @param bytes     size in bytes
 * @param rows      number of rows written
 * @param createdAt creation time
 */
public record SpoolRef(String id, SpoolKind kind, URI uri, long bytes, long rows, Instant createdAt) {
}
