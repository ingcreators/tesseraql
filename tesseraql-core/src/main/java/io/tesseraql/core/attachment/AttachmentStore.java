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

    /**
     * Deletes rows created before {@code cutoff} and returns their storage keys, so the caller can
     * reclaim the blobs (roadmap Phase 30 slice 3 retention). The blob delete is the caller's
     * concern — the store only owns the metadata.
     */
    List<String> deleteOlderThan(Instant cutoff);

    /**
     * Claims up to {@code limit} attachments for asynchronous scanning (docs/attachments.md):
     * {@code pending} rows plus {@code scanning} rows whose lease ({@code claimed_at}) is older
     * than {@code leaseCutoff} — a node that died mid-scan releases its claims by aging out.
     * The claim is a compare-and-set, so across nodes each attachment has one scanner.
     */
    List<Attachment> claimForScan(int limit, Instant leaseCutoff);

    /** Records the scan verdict ({@code clean}/{@code infected}/{@code error}) and stamps it. */
    void recordScanVerdict(String id, String scanStatus);

    /**
     * Records a failed scan attempt: increments the attempt count and returns the claim to
     * {@code pending} for a retry. Returns the new attempt count, so the caller can apply the
     * retry cap.
     */
    int recordScanFailure(String id);

    /** A metadata row to insert; the id and timestamp are assigned by the store. */
    record NewAttachment(String entity, String entityId, String filename, String contentType,
            long byteSize, String checksum, String storageKey, String scanStatus,
            String createdBy) {
    }

    /** A stored attachment: its metadata and the {@code storageKey} that addresses its blob. */
    record Attachment(String id, String entity, String entityId, String filename,
            String contentType, long byteSize, String checksum, String storageKey,
            String scanStatus, String createdBy, Instant createdAt) {
    }
}
