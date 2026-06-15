package io.tesseraql.core.attachment;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates attachment upload and download (roadmap Phase 30): on upload the content streams
 * off-heap into the {@link io.tesseraql.core.blob.BlobStore} — its size and SHA-256 computed while
 * streaming — then a metadata row is written through the {@link AttachmentStore}; on download the
 * metadata is loaded (owner-scoped) and the blob streamed back. The implementation is bound in the
 * runtime registry and looked up by the synthesized routes, like the file-transfer service.
 */
public interface AttachmentService {

    /** An upload to store against an owning record. */
    record StoreRequest(String bucket, String entity, String entityId, String filename,
            String contentType, String createdBy, long maxBytes) {
    }

    /** A fetched attachment: its metadata plus an open stream over its blob. */
    record Fetched(AttachmentStore.Attachment metadata, InputStream content) {
    }

    /**
     * Streams the content into durable storage and records its metadata. The stream is consumed
     * (spooled to the blob store) before this returns, so arbitrarily large uploads never
     * materialize in memory.
     */
    AttachmentStore.Attachment store(StoreRequest request, InputStream content);

    /** Fetches an attachment owned by the given record, or empty when unknown or not owned. */
    Optional<Fetched> fetch(String id, String entity, String entityId);

    /** Lists the attachments of one owning record. */
    List<AttachmentStore.Attachment> list(String entity, String entityId);
}
