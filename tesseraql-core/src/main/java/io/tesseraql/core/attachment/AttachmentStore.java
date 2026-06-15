package io.tesseraql.core.attachment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The persistence seam for attachment metadata (roadmap Phase 30). Consistent with IAM's
 * managed/app realm duality: the managed store owns the framework's {@code tql_attachment} table,
 * while an app-mode store keeps metadata in the application's own schema. Slice 1 ships the managed
 * store; the blob bytes live in a {@link io.tesseraql.core.blob.BlobStore}, addressed by
 * {@link Attachment#storageKey()}.
 */
public interface AttachmentStore {

    /** Inserts one metadata row and returns the stored attachment (with its generated id). */
    Attachment insert(NewAttachment attachment);

    /** Finds an attachment by id, or empty when unknown. */
    Optional<Attachment> find(String id);

    /** Lists the attachments of one owning record, newest first. */
    List<Attachment> list(String entity, String entityId);

    /** A metadata row to insert; the id, scan status, and timestamp are assigned by the store. */
    record NewAttachment(String entity, String entityId, String filename, String contentType,
            long byteSize, String checksum, String storageKey, String createdBy) {
    }

    /** A stored attachment: its metadata and the {@code storageKey} that addresses its blob. */
    record Attachment(String id, String entity, String entityId, String filename,
            String contentType, long byteSize, String checksum, String storageKey,
            String scanStatus, String createdBy, Instant createdAt) {
    }
}
