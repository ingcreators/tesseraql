package io.tesseraql.core.blob;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Durable object storage for attachments (roadmap Phase 30). A sibling of the ephemeral
 * {@link io.tesseraql.core.spool.TempStore}: blobs are retained and their deletion is governed by
 * retention, never by the store itself. The first implementation is {@link FileBlobStore}; an
 * S3-compatible store plugs in behind the same interface (the opt-in {@code tesseraql-s3} module).
 *
 * <p>The surface is deliberately the minimal portable intersection of every S3-compatible store —
 * put, get, exists, delete, and an optional pre-signed GET — so portability holds across providers.
 * Tagging, ACLs, object-lock, and lifecycle rules are not on the SPI because they vary too widely.
 */
public interface BlobStore {

    /** Opens a writer streaming bytes to a new durable object; its ref is available on close. */
    BlobWriter createWriter(BlobSpec spec);

    /** Opens an input stream over a stored object. */
    InputStream openInput(BlobRef ref) throws IOException;

    /** Whether the object still exists. */
    boolean exists(BlobRef ref);

    /** Deletes the object, ignoring a missing target. Invoked by retention, never implicitly. */
    void delete(BlobRef ref);

    /** A short-lived pre-signed GET URL, or empty when the store does not support one. */
    default Optional<URI> presignGet(BlobRef ref, Duration ttl) {
        return Optional.empty();
    }
}
