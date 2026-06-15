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
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default {@link AttachmentService}: streams uploads off-heap into a {@link BlobStore} (computing
 * size and SHA-256 as it copies), enforces the size limit, then records metadata through an
 * {@link AttachmentStore}; downloads load the metadata (owner-scoped) and stream the blob back. The
 * blob write is not transactional — an upload that fails after the blob is written leaves an orphan
 * the retention sweep reclaims (roadmap Phase 30 slice 3).
 */
public final class DefaultAttachmentService implements AttachmentService {

    /** TQL-LD-2841: an upload carried no content. */
    private static final TqlErrorCode EMPTY_UPLOAD = new TqlErrorCode(TqlDomain.LD, 2841);
    /** TQL-LD-2843: the upload exceeded the declared size limit. */
    private static final TqlErrorCode TOO_LARGE = new TqlErrorCode(TqlDomain.LD, 2843);

    private static final int BUFFER = 64 * 1024;

    private final BlobStore blobStore;
    private final AttachmentStore store;

    public DefaultAttachmentService(BlobStore blobStore, AttachmentStore store) {
        this.blobStore = blobStore;
        this.store = store;
    }

    @Override
    public AttachmentStore.Attachment store(StoreRequest request, InputStream content) {
        BlobRef ref = spool(request, content);
        if (ref.byteSize() == 0) {
            safeDelete(ref);
            throw new TqlException(EMPTY_UPLOAD, "attachment upload carried no content");
        }
        try {
            return store.insert(new AttachmentStore.NewAttachment(request.entity(),
                    request.entityId(), request.filename(), request.contentType(), ref.byteSize(),
                    ref.checksum(), ref.key(), request.createdBy()));
        } catch (RuntimeException ex) {
            // The metadata write failed: best-effort reclaim the orphan blob now (the retention
            // sweep is the durable backstop) and surface the failure.
            safeDelete(ref);
            throw ex;
        }
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
