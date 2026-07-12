package io.tesseraql.core.spool;

import io.tesseraql.core.blob.BlobRef;
import io.tesseraql.core.blob.BlobSpec;
import io.tesseraql.core.blob.BlobStore;
import io.tesseraql.core.blob.BlobWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;

/**
 * A {@link TempStore} over the durable {@link BlobStore} (docs/deployment.md, "Shared export
 * files"): {@code tesseraql.temp.store: blob} spools exports into the configured object store
 * — S3 via the opt-in {@code tesseraql-s3} module — so any node serves the download and heavy
 * export volumes stay out of the database. The spool id doubles as the blob key, so a ref
 * round-trips with no side table.
 */
public final class BlobTempStore implements TempStore {

    private final BlobStore blobStore;
    private final String bucket;

    public BlobTempStore(BlobStore blobStore, String bucket) {
        this.blobStore = blobStore;
        this.bucket = bucket;
    }

    @Override
    public SpoolWriter createWriter(SpoolKind kind) {
        BlobWriter writer = blobStore.createWriter(new BlobSpec(bucket,
                "application/octet-stream", "spool-" + kind.name().toLowerCase() + ".tmp"));
        return new SpoolWriter() {
            private long rows;
            private SpoolRef ref;

            @Override
            public void write(byte[] data) throws IOException {
                writer.write(data);
            }

            @Override
            public void incrementRows(long count) {
                rows += count;
            }

            @Override
            public void close() throws IOException {
                writer.close();
                BlobRef blob = writer.toRef();
                ref = new SpoolRef(blob.key(), kind, URI.create("tql-temp-blob:" + blob.key()),
                        blob.byteSize(), rows, Instant.now());
            }

            @Override
            public SpoolRef toRef() {
                if (ref == null) {
                    throw new IllegalStateException("Spool writer not closed");
                }
                return ref;
            }
        };
    }

    @Override
    public InputStream openInput(SpoolRef ref) throws IOException {
        return blobStore.openInput(blobRef(ref));
    }

    @Override
    public void delete(SpoolRef ref) {
        blobStore.delete(blobRef(ref));
    }

    /** The spool id is the blob key; size/checksum metadata is not needed to address it. */
    private static BlobRef blobRef(SpoolRef ref) {
        return new BlobRef(ref.id(), "application/octet-stream", ref.bytes(), null,
                ref.createdAt());
    }
}
