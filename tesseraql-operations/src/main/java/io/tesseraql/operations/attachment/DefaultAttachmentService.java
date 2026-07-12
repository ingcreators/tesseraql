package io.tesseraql.operations.attachment;

import io.tesseraql.core.attachment.AttachmentService;
import io.tesseraql.core.attachment.AttachmentStore;
import io.tesseraql.core.blob.BlobRef;
import io.tesseraql.core.blob.BlobSpec;
import io.tesseraql.core.blob.BlobStore;
import io.tesseraql.core.blob.BlobWriter;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.scan.AttachmentScanner;
import io.tesseraql.core.scan.NoopAttachmentScanner;
import io.tesseraql.core.scan.ScanVerdict;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default {@link AttachmentService}: streams uploads off-heap into a {@link BlobStore} (computing
 * size and SHA-256 as it copies), enforces the size limit, scans the stored object, then records
 * metadata through an {@link AttachmentStore}; downloads load the metadata (owner-scoped), refuse a
 * non-clean object, and stream the blob back. The blob write is not transactional — an upload that
 * fails after the blob is written leaves an orphan the retention sweep reclaims (roadmap Phase 30
 * slice 3).
 */
public final class DefaultAttachmentService implements AttachmentService {

    /** TQL-LD-2841: an upload carried no content. */
    private static final TqlErrorCode EMPTY_UPLOAD = new TqlErrorCode(TqlDomain.LD, 2841);
    /** TQL-LD-2843: the upload exceeded the declared size limit. */
    private static final TqlErrorCode TOO_LARGE = new TqlErrorCode(TqlDomain.LD, 2843);
    /** TQL-LD-2847: the scanner could not reach a verdict (fail-closed). */
    private static final TqlErrorCode SCAN_FAILED = new TqlErrorCode(TqlDomain.LD, 2847);
    /** TQL-LD-2848: a download of an object that did not pass scanning. */
    private static final TqlErrorCode INFECTED_DOWNLOAD = new TqlErrorCode(TqlDomain.LD, 2848);

    private static final int BUFFER = 64 * 1024;
    private static final String CLEAN = "clean";
    private static final String INFECTED = "infected";

    private final BlobStore blobStore;
    private final AttachmentStore store;
    private final AttachmentScanner scanner;
    private final boolean asyncScan;
    private final boolean deleteInfected;

    /** Slice-1 constructor: the no-op scanner, quarantine policy. */
    public DefaultAttachmentService(BlobStore blobStore, AttachmentStore store) {
        this(blobStore, store, new NoopAttachmentScanner(), "quarantine", false);
    }

    public DefaultAttachmentService(BlobStore blobStore, AttachmentStore store,
            AttachmentScanner scanner, String onInfected) {
        this(blobStore, store, scanner, onInfected, false);
    }

    /**
     * @param asyncScan record uploads {@code pending} and leave the verdict to the scan sweep
     *                  (docs/attachments.md) — the existing non-clean download gate holds
     *                  pending objects back, so async never weakens the fail-closed posture
     */
    public DefaultAttachmentService(BlobStore blobStore, AttachmentStore store,
            AttachmentScanner scanner, String onInfected, boolean asyncScan) {
        this.blobStore = blobStore;
        this.store = store;
        this.scanner = scanner;
        this.deleteInfected = "delete".equalsIgnoreCase(onInfected);
        this.asyncScan = asyncScan;
    }

    @Override
    public AttachmentStore.Attachment store(StoreRequest request, InputStream content) {
        BlobRef ref = spool(request, content);
        if (ref.byteSize() == 0) {
            safeDelete(ref);
            throw new TqlException(EMPTY_UPLOAD, "attachment upload carried no content");
        }
        // Async mode returns immediately: the upload is pending until the sweep's verdict,
        // and the download gate already refuses anything non-clean.
        String scanStatus = asyncScan ? "pending" : scan(ref);
        try {
            return store.insert(new AttachmentStore.NewAttachment(request.entity(),
                    request.entityId(), request.filename(), request.contentType(), ref.byteSize(),
                    ref.checksum(), ref.key(), scanStatus, request.createdBy()));
        } catch (RuntimeException ex) {
            // The metadata write failed: best-effort reclaim the orphan blob now (the retention
            // sweep is the durable backstop) and surface the failure.
            safeDelete(ref);
            throw ex;
        }
    }

    /**
     * Scans the stored object and returns the {@code scan_status} to record. An infected object is
     * quarantined (kept) or deleted per the policy, but is always recorded {@code infected} so the
     * download gate refuses it; a scanner error fails the upload closed.
     */
    private String scan(BlobRef ref) {
        ScanVerdict verdict = scanner.scan(ref, () -> blobStore.openInput(ref));
        return switch (verdict.status()) {
            case CLEAN -> CLEAN;
            case INFECTED -> {
                if (deleteInfected) {
                    safeDelete(ref);
                }
                yield INFECTED;
            }
            case ERROR -> {
                safeDelete(ref);
                throw new TqlException(SCAN_FAILED,
                        "attachment scan could not complete: " + verdict.detail());
            }
        };
    }

    private BlobRef spool(StoreRequest request, InputStream content) {
        BlobWriter writer = blobStore.createWriter(
                new BlobSpec(request.bucket(), request.contentType(), request.filename()));
        long total = 0;
        boolean ok = false;
        try (writer; content) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = content.read(buffer)) >= 0) {
                if (read > 0) {
                    total += read;
                    if (request.maxBytes() > 0 && total > request.maxBytes()) {
                        throw TqlException.builder(TOO_LARGE)
                                .message("attachment exceeds the " + request.maxBytes()
                                        + "-byte limit")
                                .details(Map.of("maxBytes", request.maxBytes()))
                                .build();
                    }
                    byte[] chunk = new byte[read];
                    System.arraycopy(buffer, 0, chunk, 0, read);
                    writer.write(chunk);
                }
            }
            ok = true;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            if (!ok) {
                // The try-with-resources already closed the writer, so toRef() yields the key.
                safeDelete(writer.toRef());
            }
        }
        return writer.toRef();
    }

    @Override
    public Optional<Fetched> fetch(String id, String entity, String entityId) {
        Optional<AttachmentStore.Attachment> found = store.find(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AttachmentStore.Attachment a = found.get();
        if (!a.entity().equals(entity) || !a.entityId().equals(entityId)) {
            // Owned by a different record: do not leak its existence across records.
            return Optional.empty();
        }
        if (!CLEAN.equalsIgnoreCase(a.scanStatus())) {
            // Never serve an object that is not clean: infected/error stay refused, and a
            // pending async scan holds the object back until its verdict — same gate, same
            // fail-closed posture (docs/attachments.md).
            boolean pending = "pending".equalsIgnoreCase(a.scanStatus())
                    || "scanning".equalsIgnoreCase(a.scanStatus());
            throw new TqlException(INFECTED_DOWNLOAD, pending
                    ? "attachment " + a.id() + " is awaiting its malware scan - retry shortly"
                    : "attachment " + a.id() + " did not pass malware scanning");
        }
        BlobRef ref = new BlobRef(a.storageKey(), a.contentType(), a.byteSize(), a.checksum(),
                a.createdAt());
        try {
            return Optional.of(new Fetched(a, blobStore.openInput(ref)));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public List<AttachmentStore.Attachment> list(String entity, String entityId) {
        return store.list(entity, entityId);
    }

    private void safeDelete(BlobRef ref) {
        try {
            blobStore.delete(ref);
        } catch (RuntimeException ignored) {
            // best-effort; the retention sweep reclaims any orphan
        }
    }
}
