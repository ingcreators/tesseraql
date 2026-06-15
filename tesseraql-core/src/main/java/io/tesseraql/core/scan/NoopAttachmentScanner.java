package io.tesseraql.core.scan;

import io.tesseraql.core.blob.BlobRef;

/**
 * The default {@link AttachmentScanner}: every attachment is reported clean without reading its
 * bytes (roadmap Phase 30 slice 3). Installing a real scanner module replaces it via ServiceLoader.
 */
public final class NoopAttachmentScanner implements AttachmentScanner {

    @Override
    public String id() {
        return "noop";
    }

    @Override
    public ScanVerdict scan(BlobRef ref, ContentSource content) {
        return ScanVerdict.clean();
    }
}
