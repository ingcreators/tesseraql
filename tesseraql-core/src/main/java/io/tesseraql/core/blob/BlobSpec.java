package io.tesseraql.core.blob;

/**
 * The descriptor for a new object (roadmap Phase 30): the logical {@code bucket} namespace it
 * belongs to, its {@code contentType}, and a {@code filename} hint (for stores that derive a key
 * from it). The store mints the durable key; the caller never chooses it.
 */
public record BlobSpec(String bucket, String contentType, String filename) {
}
