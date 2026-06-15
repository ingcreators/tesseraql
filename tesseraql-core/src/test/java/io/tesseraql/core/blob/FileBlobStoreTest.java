package io.tesseraql.core.blob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBlobStoreTest {

    @Test
    void writesComputesChecksumReadsAndDeletes(@TempDir Path dir) throws Exception {
        FileBlobStore store = new FileBlobStore(dir.resolve("blob"));
        byte[] payload = "hello, attachments\n".getBytes(StandardCharsets.UTF_8);

        BlobWriter writer = store.createWriter(new BlobSpec("uploads", "text/plain", "note.txt"));
        try (writer) {
            writer.write(payload);
        }
        BlobRef ref = writer.toRef();

        assertThat(ref.contentType()).isEqualTo("text/plain");
        assertThat(ref.byteSize()).isEqualTo(payload.length);
        assertThat(ref.key()).startsWith("uploads/");
        assertThat(ref.checksum()).isEqualTo(sha256Hex(payload));
        assertThat(store.exists(ref)).isTrue();

        try (InputStream in = store.openInput(ref)) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }

        store.delete(ref);
        assertThat(store.exists(ref)).isFalse();
    }

    @Test
    void checksumSpansMultipleWrites(@TempDir Path dir) throws Exception {
        FileBlobStore store = new FileBlobStore(dir.resolve("blob"));
        BlobWriter writer = store.createWriter(new BlobSpec("uploads", "application/octet-stream",
                null));
        try (writer) {
            writer.write("ab".getBytes(StandardCharsets.UTF_8));
            writer.write("cd".getBytes(StandardCharsets.UTF_8));
        }
        BlobRef ref = writer.toRef();

        assertThat(ref.byteSize()).isEqualTo(4);
        assertThat(ref.checksum()).isEqualTo(sha256Hex("abcd".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void blankBucketFallsBackToDefaultNamespace(@TempDir Path dir) throws Exception {
        FileBlobStore store = new FileBlobStore(dir.resolve("blob"));
        BlobWriter writer = store.createWriter(new BlobSpec(null, "text/plain", null));
        try (writer) {
            writer.write("x".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(writer.toRef().key()).startsWith("default/");
    }

    @Test
    void rejectsBucketWithPathTraversal(@TempDir Path dir) {
        FileBlobStore store = new FileBlobStore(dir.resolve("blob"));
        assertThatThrownBy(() -> store.createWriter(new BlobSpec("../etc", "text/plain", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void presignIsUnsupportedForTheFileStore(@TempDir Path dir) {
        FileBlobStore store = new FileBlobStore(dir.resolve("blob"));
        BlobRef ref = new BlobRef("uploads/x", "text/plain", 1, "00", java.time.Instant.now());
        assertThat(store.presignGet(ref, java.time.Duration.ofMinutes(5))).isEmpty();
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
