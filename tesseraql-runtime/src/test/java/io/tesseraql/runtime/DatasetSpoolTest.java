package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.attachment.AttachmentStore;
import io.tesseraql.core.blob.BlobRef;
import io.tesseraql.core.blob.BlobSpec;
import io.tesseraql.core.blob.BlobStore;
import io.tesseraql.core.blob.BlobWriter;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sweep and the in-flight temp. {@code AtomicFiles} names its temps
 * {@code <target><random>.tmp}, and the sweep's old {@code .spool} prefix exclusion matched
 * nothing once localize adopted it — a half-copied temp counted toward the cap and, as the
 * oldest-looking file, could be swept mid-copy, failing the localize whose atomic move
 * expected it.
 */
class DatasetSpoolTest {

    @Test
    void theSweepSkipsInFlightTempsAndStillEvictsTheOldestEntry(@TempDir Path dir)
            throws Exception {
        DatasetSpool spool = new DatasetSpool(new FixedBlobStore("payload"), dir);
        // At the cap already: 256 aged entries, plus an in-flight temp older than all of them.
        for (int i = 0; i < 256; i++) {
            Path entry = dir.resolve("entry-" + i + ".parquet");
            Files.writeString(entry, "old");
            Files.setLastModifiedTime(entry, FileTime.fromMillis(1_000_000 + i * 1_000L));
        }
        Path inFlight = Files.createTempFile(dir, "entry-big.parquet", ".tmp");
        Files.setLastModifiedTime(inFlight, FileTime.fromMillis(0));

        Path localized = spool.localize(attachment());

        // The new entry pushed the count past the cap: the oldest real entry went, while the
        // even-older temp neither counted toward the cap nor got swept mid-copy.
        assertThat(localized).exists();
        assertThat(inFlight).exists();
        assertThat(dir.resolve("entry-0.parquet")).doesNotExist();
        assertThat(dir.resolve("entry-255.parquet")).exists();
    }

    private static AttachmentStore.Attachment attachment() {
        return new AttachmentStore.Attachment("att-1", "dataset", "d1", "data.parquet",
                "application/vnd.apache.parquet", 7, "cafebabe", "key-1", null, null,
                Instant.now());
    }

    /** Serves one fixed payload; nothing else is the sweep's business. */
    private record FixedBlobStore(String payload) implements BlobStore {

        @Override
        public BlobWriter createWriter(BlobSpec spec) {
            throw new UnsupportedOperationException("not the subject");
        }

        @Override
        public InputStream openInput(BlobRef ref) {
            return new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public boolean exists(BlobRef ref) {
            return true;
        }

        @Override
        public void delete(BlobRef ref) {
            throw new UnsupportedOperationException("not the subject");
        }
    }
}
