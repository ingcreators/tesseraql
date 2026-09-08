package io.tesseraql.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.blob.BlobRef;
import io.tesseraql.core.blob.BlobSpec;
import io.tesseraql.core.blob.BlobWriter;
import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The egress allow-list on the read paths (TQL-LD-2846), without a container.
 *
 * <p>All four reads already funnel through {@code location} into {@code realBucket}, so nothing
 * here is broken. What was missing is the assertion: the store's only allow-list test drives
 * {@code createWriter}, which reaches the check by a different route, so a refactor that inlined
 * or short-circuited {@code location} would have opened {@code exists}, {@code openInput},
 * {@code delete} and {@code presignGet} silently, and the suite would have stayed green.
 *
 * <p>No S3Mock: a refused bucket throws before any client call, so a {@code null} client is enough
 * — and it doubles as the sensitivity control. If the refusal ever stopped happening, these tests
 * would not pass quietly; they would fail with a {@link NullPointerException} from the client the
 * store was never supposed to reach.
 */
class S3BlobStoreTest {

    private static final Duration A_WHILE = Duration.ofMinutes(5);

    /**
     * A store that allows one bucket and has no client at all.
     *
     * <p>The presigner is real, and has to be: {@code presignGet} returns empty on a null
     * presigner <em>before</em> it resolves the bucket, so a null one would make its refusal
     * untestable and the assertion below vacuous. Presigning is local HMAC — no network, no
     * container, and the credentials are never used because the bucket is refused first.
     */
    private static S3BlobStore allowingOnlyUploads() {
        return new S3BlobStore(null, presigner(), Map.of("uploads", "acme-uploads"),
                Set.of("acme-uploads"));
    }

    private static S3Presigner presigner() {
        return S3Presigner.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-key", "test-secret")))
                .build();
    }

    private static BlobRef ref(String logicalBucket) {
        return new BlobRef(logicalBucket + "/an-object-key", "application/octet-stream", 3L,
                "abc", Instant.EPOCH);
    }

    @Test
    void everyReadPathRefusesABucketOutsideTheAllowList() {
        S3BlobStore store = allowingOnlyUploads();
        BlobRef elsewhere = ref("archive");

        assertThatThrownBy(() -> store.exists(elsewhere))
                .isInstanceOf(TqlException.class)
                .satisfies(denied -> assertThat(((TqlException) denied).code().number())
                        .isEqualTo(2846));
        assertThatThrownBy(() -> store.openInput(elsewhere))
                .isInstanceOf(TqlException.class);
        assertThatThrownBy(() -> store.delete(elsewhere))
                .isInstanceOf(TqlException.class);
        assertThatThrownBy(() -> store.presignGet(elsewhere, A_WHILE))
                .isInstanceOf(TqlException.class);
    }

    /**
     * The control, and the reason the {@code null} client is not a shortcut. An allowed bucket
     * gets past the check and reaches for the client — so these paths really do run the check
     * first, rather than refusing everything for some unrelated reason.
     */
    @Test
    void anAllowedBucketGetsPastTheCheckAndReachesForTheClient() {
        S3BlobStore store = allowingOnlyUploads();
        BlobRef allowed = ref("uploads");

        assertThatThrownBy(() -> store.exists(allowed))
                .as("past the allow-list, into the client this store does not have")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.openInput(allowed))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.delete(allowed))
                .isInstanceOf(NullPointerException.class);
    }

    /** A key with no logical prefix resolves to "default", which is refused unless allow-listed. */
    @Test
    void aKeyWithoutALogicalBucketResolvesToDefault() {
        S3BlobStore store = allowingOnlyUploads();
        BlobRef bare = new BlobRef("an-object-key", "application/octet-stream", 3L, "abc",
                Instant.EPOCH);

        assertThatThrownBy(() -> store.exists(bare))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("'default'");
    }

    /** An alias resolves before the membership check, so the allow-list names REAL buckets. */
    @Test
    void theAllowListNamesTheRealBucketNotTheAlias() {
        S3BlobStore aliasNotAllowed = new S3BlobStore(null, presigner(),
                Map.of("uploads", "acme-uploads"), Set.of("uploads"));

        assertThatThrownBy(() -> aliasNotAllowed.exists(ref("uploads")))
                .as("'uploads' is the alias; the allow-list is checked against 'acme-uploads'")
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("acme-uploads");
    }

    /**
     * A failed upload reclaims its spool.
     *
     * <p>The writer buffers to a temp file and deletes it in a {@code finally}. This pins that,
     * and it is the control the flush belongs inside: {@code out.close()} is where a full or
     * failing disk shows up, and outside the {@code try} its failure skipped the delete.
     *
     * <p>The while-open assertion is what stops this being vacuous — it observes the file
     * existing before {@code close} removes it, so "nothing left behind" cannot pass because
     * nothing was ever there.
     */
    @Test
    void aFailedUploadLeavesNoTemporaryFileBehind() throws IOException {
        Set<String> before = spools();

        BlobWriter writer = allowingOnlyUploads()
                .createWriter(new BlobSpec("uploads", "text/csv", "items.csv"));
        writer.write("id,name\n1,a\n".getBytes(StandardCharsets.UTF_8));

        assertThat(spools())
                .as("the spool exists while the writer is open, so the assertion below has"
                        + " something to observe")
                .isNotEqualTo(before);

        // The store has no client, so putObject fails inside the try — the upload failure path.
        assertThatThrownBy(writer::close).isInstanceOf(NullPointerException.class);

        assertThat(spools())
                .as("a failed upload must not leave its buffered spool on disk")
                .isEqualTo(before);
    }

    /** The {@code tql-blob-*} temp files present right now. */
    private static Set<String> spools() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("tql-blob-"))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        }
    }
}
