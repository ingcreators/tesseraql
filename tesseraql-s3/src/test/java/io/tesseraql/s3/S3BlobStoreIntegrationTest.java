package io.tesseraql.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import io.tesseraql.core.blob.BlobRef;
import io.tesseraql.core.blob.BlobSpec;
import io.tesseraql.core.blob.BlobStore;
import io.tesseraql.core.blob.BlobWriter;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.blob.BlobStores;
import io.tesseraql.yaml.config.AppConfig;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 30 slice 2: the {@code s3} provider builds an {@link S3BlobStore} that round-trips against a
 * real S3 API (Adobe S3Mock, Apache-2.0 — MinIO is not used), discovered through the
 * {@link BlobStores} ServiceLoader by {@code tesseraql.object-storage.provider: s3}.
 */
@Testcontainers
class S3BlobStoreIntegrationTest {

    @Container
    @SuppressWarnings("resource") // lifecycle is managed by the @Container extension
    static final S3MockContainer S3MOCK = new S3MockContainer("4.12.4")
            .withInitialBuckets("acme-uploads");

    private static AppConfig config() {
        Map<String, Object> objectStorage = Map.of(
                "provider", "s3",
                "buckets", Map.of("uploads", Map.of("bucket", "acme-uploads")),
                "s3", Map.of(
                        "endpoint", S3MOCK.getHttpEndpoint(),
                        "region", "us-east-1",
                        "pathStyle", "true",
                        "credentials", Map.of("accessKey", "test", "secretKey", "test")),
                "allowedBuckets", List.of("acme-uploads"));
        Map<String, Object> root = Map.of("tesseraql", Map.of("object-storage", objectStorage));
        return new AppConfig(root, name -> null);
    }

    @Test
    void providerRoundTripsThroughS3(@TempDir Path appHome) throws Exception {
        BlobStore store = BlobStores.create(config(), appHome);
        assertThat(store).isInstanceOf(S3BlobStore.class);

        byte[] payload = "invoice bytes\n".getBytes(StandardCharsets.UTF_8);
        BlobWriter writer = store
                .createWriter(new BlobSpec("uploads", "application/pdf", "inv.pdf"));
        try (writer) {
            writer.write(payload);
        }
        BlobRef ref = writer.toRef();

        assertThat(ref.key()).startsWith("uploads/");
        assertThat(ref.byteSize()).isEqualTo(payload.length);
        assertThat(ref.checksum()).isEqualTo(sha256Hex(payload));
        assertThat(store.exists(ref)).isTrue();

        try (InputStream in = store.openInput(ref)) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }

        Optional<URI> presigned = store.presignGet(ref, Duration.ofMinutes(5));
        assertThat(presigned).isPresent();
        assertThat(presigned.get().toString()).startsWith("http");

        store.delete(ref);
        assertThat(store.exists(ref)).isFalse();
    }

    @Test
    void aBucketOutsideTheAllowListIsDenied(@TempDir Path appHome) {
        BlobStore store = BlobStores.create(config(), appHome);
        // "secret" maps to no configured bucket and is not in allowedBuckets.
        assertThatThrownBy(() -> store.createWriter(new BlobSpec("secret", "text/plain", "x")))
                .isInstanceOfSatisfying(TqlException.class,
                        ex -> assertThat(ex.code().number()).isEqualTo(2846));
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
