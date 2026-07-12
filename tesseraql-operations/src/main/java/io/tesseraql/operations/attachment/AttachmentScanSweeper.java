package io.tesseraql.operations.attachment;

import io.tesseraql.core.attachment.AttachmentStore;
import io.tesseraql.core.blob.BlobRef;
import io.tesseraql.core.blob.BlobStore;
import io.tesseraql.core.scan.AttachmentScanner;
import io.tesseraql.core.scan.ScanVerdict;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The asynchronous scan sweep (docs/attachments.md, "Scanning"): claims {@code pending}
 * attachments (and {@code scanning} claims whose lease aged out — a node that died mid-scan),
 * runs the installed {@link AttachmentScanner}, and records the verdict. The claim is a
 * compare-and-set on the shared database, so across nodes each attachment has exactly one
 * scanner; a failed attempt returns the row to {@code pending} until the retry cap, after
 * which it is recorded {@code error} — still non-clean, still never served. Infected objects
 * honor the same {@code onInfected: quarantine | delete} policy as the synchronous path.
 */
public final class AttachmentScanSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(AttachmentScanSweeper.class);

    private final BlobStore blobStore;
    private final AttachmentStore store;
    private final AttachmentScanner scanner;
    private final boolean deleteInfected;
    private final int maxAttempts;
    private final Duration lease;
    private final int batchSize;

    public AttachmentScanSweeper(BlobStore blobStore, AttachmentStore store,
            AttachmentScanner scanner, String onInfected, int maxAttempts, Duration lease,
            int batchSize) {
        this.blobStore = blobStore;
        this.store = store;
        this.scanner = scanner;
        this.deleteInfected = "delete".equalsIgnoreCase(onInfected);
        this.maxAttempts = maxAttempts;
        this.lease = lease;
        this.batchSize = batchSize;
    }

    /** One sweep pass; returns how many attachments received a verdict. */
    public int sweep() {
        int decided = 0;
        for (AttachmentStore.Attachment attachment : store.claimForScan(batchSize,
                Instant.now().minus(lease))) {
            if (scanOne(attachment)) {
                decided++;
            }
        }
        return decided;
    }

    private boolean scanOne(AttachmentStore.Attachment attachment) {
        BlobRef ref = new BlobRef(attachment.storageKey(), attachment.contentType(),
                attachment.byteSize(), attachment.checksum(), attachment.createdAt());
        try {
            ScanVerdict verdict = scanner.scan(ref, () -> blobStore.openInput(ref));
            switch (verdict.status()) {
                case CLEAN -> store.recordScanVerdict(attachment.id(), "clean");
                case INFECTED -> {
                    if (deleteInfected) {
                        try {
                            blobStore.delete(ref);
                        } catch (RuntimeException ignored) {
                            // best effort, like the synchronous path
                        }
                    }
                    store.recordScanVerdict(attachment.id(), "infected");
                }
                case ERROR -> throw new IllegalStateException(verdict.detail());
            }
            return true;
        } catch (RuntimeException | java.io.IOError ex) {
            int attempts = store.recordScanFailure(attachment.id());
            if (attempts >= maxAttempts) {
                // Fail closed and stop retrying: error is non-clean, so it is never served,
                // and the operator sees it in the metadata instead of a silent retry loop.
                store.recordScanVerdict(attachment.id(), "error");
                LOG.warn("Attachment {} scan gave up after {} attempts: {}", attachment.id(),
                        attempts, ex.getMessage());
            } else {
                LOG.warn("Attachment {} scan attempt {} failed; will retry: {}",
                        attachment.id(), attempts, ex.getMessage());
            }
            return false;
        }
    }
}
