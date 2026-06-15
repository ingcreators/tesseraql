package io.tesseraql.core.scan;

import io.tesseraql.core.blob.BlobRef;
import java.io.IOException;
import java.io.InputStream;

/**
 * The seam a malware scanner plugs into (roadmap Phase 30 slice 3): the runtime scans an attachment
 * after it is stored and before its metadata is served. The default is a no-op
 * ({@link NoopAttachmentScanner}); a real scanner (ClamAV, a cloud scan service) ships as a
 * {@link java.util.ServiceLoader} provider, discovered by {@link AttachmentScanners}.
 *
 * <p>The content is supplied lazily through a {@link ContentSource} so the no-op default never opens
 * the blob (and so an S3-backed store is not read needlessly when scanning is disabled).
 */
public interface AttachmentScanner {

    /** A short id for logging/diagnostics (e.g. {@code clamav}). */
    String id();

    /** Scans the stored object; the source opens a fresh stream over its bytes. */
    ScanVerdict scan(BlobRef ref, ContentSource content);

    /** Opens a stream over the object's bytes; the caller closes it. */
    @FunctionalInterface
    interface ContentSource {
        InputStream open() throws IOException;
    }
}
