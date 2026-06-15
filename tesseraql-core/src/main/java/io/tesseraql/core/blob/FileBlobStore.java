package io.tesseraql.core.blob;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Local-filesystem {@link BlobStore} (roadmap Phase 30, the default {@code provider: file}). Objects
 * are written under a configured root, namespaced by bucket: {@code <root>/<bucket>/<uuid>}. The
 * writer computes a SHA-256 checksum and byte count while streaming, so neither needs a second pass.
 */
public final class FileBlobStore implements BlobStore {

    private final Path root;

    public FileBlobStore(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public BlobWriter createWriter(BlobSpec spec) {
        String key = sanitize(spec.bucket()) + "/" + UUID.randomUUID();
        Path file = root.resolve(key);
        try {
            Files.createDirectories(file.getParent());
            OutputStream out = new BufferedOutputStream(Files.newOutputStream(file));
            return new FileBlobWriter(key, spec.contentType(), out);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public InputStream openInput(BlobRef ref) throws IOException {
        return Files.newInputStream(root.resolve(ref.key()));
    }

    @Override
    public boolean exists(BlobRef ref) {
        return Files.exists(root.resolve(ref.key()));
    }

    @Override
    public void delete(BlobRef ref) {
        try {
            Files.deleteIfExists(root.resolve(ref.key()));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Confines a bucket name to a single safe path segment (no traversal, no separators). */
    private static String sanitize(String bucket) {
        if (bucket == null || bucket.isBlank()) {
            return "default";
        }
        String trimmed = bucket.replace('\\', '/');
        if (trimmed.contains("/") || trimmed.contains("..")) {
            throw new IllegalArgumentException("Invalid bucket name: " + bucket);
        }
        return trimmed;
    }

    private static final class FileBlobWriter implements BlobWriter {
        private final String key;
        private final String contentType;
        private final OutputStream out;
        private final MessageDigest digest;
        private long bytes;

        FileBlobWriter(String key, String contentType, OutputStream out) {
            this.key = key;
            this.contentType = contentType;
            this.out = out;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 unavailable", ex);
            }
        }

        @Override
        public void write(byte[] data) throws IOException {
            out.write(data);
            digest.update(data);
            bytes += data.length;
        }

        @Override
        public void close() throws IOException {
            out.close();
        }

        @Override
        public BlobRef toRef() {
            return new BlobRef(key, contentType, bytes,
                    HexFormat.of().formatHex(digest.digest()), Instant.now());
        }
    }
}
