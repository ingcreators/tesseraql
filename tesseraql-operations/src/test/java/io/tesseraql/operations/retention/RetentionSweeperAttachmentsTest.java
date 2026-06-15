package io.tesseraql.operations.retention;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.attachment.AttachmentStore;
import io.tesseraql.core.blob.BlobRef;
import io.tesseraql.core.blob.BlobSpec;
import io.tesseraql.core.blob.BlobStore;
import io.tesseraql.core.blob.BlobWriter;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Phase 30 slice 3: the retention sweep's attachments pass (metadata + best-effort blob reclaim). */
class RetentionSweeperAttachmentsTest {

    @Test
    void sweepsAgedAttachmentsAndReclaimsTheirBlobs() {
        RecordingBlobStore blobStore = new RecordingBlobStore();
        StubAttachmentStore store = new StubAttachmentStore(List.of("uploads/a", "uploads/b"));
        RetentionSweeper sweeper = new RetentionSweeper(null, store, blobStore);

        int removed = sweeper.sweepAttachments(Instant.now());

        assertThat(removed).isEqualTo(2);
        assertThat(blobStore.deletedKeys).containsExactly("uploads/a", "uploads/b");
    }

    @Test
    void noAttachmentStoreMeansNoOp() {
        assertThat(new RetentionSweeper(null).sweepAttachments(Instant.now())).isZero();
    }

    private static final class RecordingBlobStore implements BlobStore {
        final List<String> deletedKeys = new ArrayList<>();

        @Override
        public BlobWriter createWriter(BlobSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream openInput(BlobRef ref) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(BlobRef ref) {
            return true;
        }

        @Override
        public void delete(BlobRef ref) {
            deletedKeys.add(ref.key());
        }
    }

    private record StubAttachmentStore(List<String> keys) implements AttachmentStore {
        @Override
        public Attachment insert(NewAttachment attachment) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Attachment> find(String id) {
            return Optional.empty();
        }

        @Override
        public List<Attachment> list(String entity, String entityId) {
            return List.of();
        }

        @Override
        public List<String> deleteOlderThan(Instant cutoff) {
            return keys;
        }
    }
}
