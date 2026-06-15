package io.tesseraql.s3;

import io.tesseraql.core.blob.BlobRef;
import io.tesseraql.core.blob.BlobSpec;
import io.tesseraql.core.blob.BlobStore;
import io.tesseraql.core.blob.BlobWriter;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * S3 (and S3-compatible) {@link BlobStore} on AWS SDK for Java v2 (roadmap Phase 30 slice 2). A
 * logical bucket from the attachment recipe maps to a real S3 bucket and is checked against the
 * deny-by-default egress allow-list; the object key is a UUID. The {@link BlobRef#key()} is
 * {@code <logicalBucket>/<uuid>}, so reads re-resolve the same way. Uploads buffer to a temp file
 * off the heap while computing a SHA-256, then a single {@code putObject} streams the file to S3
 * (the SDK needs a known content length, so an unbounded stream cannot go straight through).
 */
public final class S3BlobStore implements BlobStore {

    /** TQL-LD-2846: an object-storage bucket is outside the egress allow-list. */
    private static final TqlErrorCode DENIED = new TqlErrorCode(TqlDomain.LD, 2846);

    private final S3Client client;
    private final S3Presigner presigner;
    private final Map<String, String> buckets;
    private final Set<String> allowedBuckets;

    public S3BlobStore(S3Client client, S3Presigner presigner, Map<String, String> buckets,
            Set<String> allowedBuckets) {
        this.client = client;
        this.presigner = presigner;
        this.buckets = Map.copyOf(buckets);
        this.allowedBuckets = Set.copyOf(allowedBuckets);
    }

    @Override
    public BlobWriter createWriter(BlobSpec spec) {
        String logical = logical(spec.bucket());
        String realBucket = realBucket(logical);
        String objectKey = UUID.randomUUID().toString();
        return new S3BlobWriter(logical + "/" + objectKey, spec.contentType(), realBucket,
                objectKey);
    }

    @Override
    public InputStream openInput(BlobRef ref) {
        Location loc = location(ref);
        return client.getObject(GetObjectRequest.builder().bucket(loc.bucket()).key(loc.key())
                .build());
    }

    @Override
    public boolean exists(BlobRef ref) {
        Location loc = location(ref);
        try {
            client.headObject(HeadObjectRequest.builder().bucket(loc.bucket()).key(loc.key())
                    .build());
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        }
    }

    @Override
    public void delete(BlobRef ref) {
        Location loc = location(ref);
        client.deleteObject(DeleteObjectRequest.builder().bucket(loc.bucket()).key(loc.key())
                .build());
    }

    @Override
    public Optional<URI> presignGet(BlobRef ref, Duration ttl) {
        if (presigner == null) {
            return Optional.empty();
        }
        Location loc = location(ref);
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(get -> get.bucket(loc.bucket()).key(loc.key()))
                .build();
        return Optional.of(URI.create(presigner.presignGetObject(request).url().toString()));
    }

    private static String logical(String bucket) {
        return bucket == null || bucket.isBlank() ? "default" : bucket;
    }

    private String realBucket(String logical) {
        String real = buckets.getOrDefault(logical, logical);
        if (!allowedBuckets.contains(real)) {
            throw new TqlException(DENIED, "object-storage bucket '" + real
                    + "' is not in tesseraql.object-storage.allowedBuckets (deny by default)");
        }
        return real;
    }

    private Location location(BlobRef ref) {
        int slash = ref.key().indexOf('/');
        String logical = slash < 0 ? "default" : ref.key().substring(0, slash);
        String objectKey = slash < 0 ? ref.key() : ref.key().substring(slash + 1);
        return new Location(realBucket(logical), objectKey);
    }

    private record Location(String bucket, String key) {
    }

    private final class S3BlobWriter implements BlobWriter {
        private final String key;
        private final String contentType;
        private final String realBucket;
        private final String objectKey;
        private final Path temp;
        private final OutputStream out;
        private final MessageDigest digest;
        private long bytes;

        S3BlobWriter(String key, String contentType, String realBucket, String objectKey) {
            this.key = key;
            this.contentType = contentType;
            this.realBucket = realBucket;
            this.objectKey = objectKey;
            try {
                this.temp = Files.createTempFile("tql-blob-", ".tmp");
                this.out = new BufferedOutputStream(Files.newOutputStream(temp));
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
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
            try {
                PutObjectRequest.Builder put = PutObjectRequest.builder()
                        .bucket(realBucket).key(objectKey);
                if (contentType != null && !contentType.isBlank()) {
                    put.contentType(contentType);
                }
                client.putObject(put.build(), RequestBody.fromFile(temp));
            } finally {
                Files.deleteIfExists(temp);
            }
        }

        @Override
        public BlobRef toRef() {
            return new BlobRef(key, contentType, bytes,
                    HexFormat.of().formatHex(digest.digest()), Instant.now());
        }
    }
}
