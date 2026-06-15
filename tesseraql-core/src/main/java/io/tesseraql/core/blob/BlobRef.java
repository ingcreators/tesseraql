package io.tesseraql.core.blob;

import java.time.Instant;

/**
 * An immutable reference to a durable object in a {@link BlobStore} (roadmap Phase 30). It carries
 * the storage {@code key} the store addresses the object by, the {@code contentType} and
 * {@code byteSize} recorded at write time, the {@code checksum} (lowercase-hex SHA-256, computed
 * while streaming), and the creation timestamp. It never carries the bytes.
 */
public record BlobRef(String key, String contentType, long byteSize, String checksum,
        Instant createdAt) {
}
