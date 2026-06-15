package io.tesseraql.operations.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.attachment.AttachmentService;
import io.tesseraql.core.attachment.AttachmentStore;
import io.tesseraql.core.blob.BlobRef;
import io.tesseraql.core.blob.BlobSpec;
import io.tesseraql.core.blob.BlobStore;
import io.tesseraql.core.blob.BlobWriter;
import io.tesseraql.core.blob.FileBlobStore;
import io.tesseraql.core.error.TqlException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultAttachmentServiceTest {

    private static AttachmentService service(Path dir, AttachmentStore store) {
        return new DefaultAttachmentService(new FileBlobStore(dir.resolve("blob")), store);
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void storesAndFetchesRoundTrip(@TempDir Path dir) throws Exception {
        InMemoryStore store = new InMemoryStore();
        AttachmentService service = service(dir, store);

        AttachmentStore.Attachment stored = service.store(
                new AttachmentService.StoreRequest("uploads", "invoice", "INV-1", "note.txt",
                        "text/plain", "alice", 1024),
                stream("the body"));

        assertThat(stored.id()).isNotBlank();
        assertThat(stored.byteSize()).isEqualTo("the body".length());
        assertThat(stored.checksum()).isNotBlank();
        assertThat(stored.createdBy()).isEqualTo("alice");

        Optional<AttachmentService.Fetched> fetched = service.fetch(stored.id(), "invoice",
                "INV-1");
        assertThat(fetched).isPresent();
        try (var content = fetched.get().content()) {
            assertThat(new String(content.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("the body");
        }
    }

    @Test
    void fetchIsScopedToTheOwningRecord(@TempDir Path dir) {
        InMemoryStore store = new InMemoryStore();
        AttachmentService service = service(dir, store);
        AttachmentStore.Attachment stored = service.store(
                new AttachmentService.StoreRequest("uploads", "invoice", "INV-1", "n", "text/plain",
                        null, 0),
                stream("x"));

        // A different owning record must not see it (no cross-record leakage).
        assertThat(service.fetch(stored.id(), "invoice", "INV-2")).isEmpty();
        assertThat(service.fetch(stored.id(), "order", "INV-1")).isEmpty();
        assertThat(service.fetch("missing", "invoice", "INV-1")).isEmpty();
    }

    @Test
    void emptyUploadIsRejectedAndLeavesNoMetadata(@TempDir Path dir) {
        InMemoryStore store = new InMemoryStore();
        AttachmentService service = service(dir, store);

        assertThatThrownBy(() -> service.store(
                new AttachmentService.StoreRequest("uploads", "invoice", "INV-1", "n", "text/plain",
                        null, 0),
                stream("")))
                .isInstanceOfSatisfying(TqlException.class,
                        ex -> assertThat(ex.code().number()).isEqualTo(2841));
        assertThat(store.rows).isEmpty();
    }

    @Test
    void oversizeUploadIsRejectedAndLeavesNoMetadata(@TempDir Path dir) {
        InMemoryStore store = new InMemoryStore();
        AttachmentService service = service(dir, store);

        assertThatThrownBy(() -> service.store(
                new AttachmentService.StoreRequest("uploads", "invoice", "INV-1", "n", "text/plain",
                        null, 4),
                stream("0123456789")))
                .isInstanceOfSatisfying(TqlException.class,
                        ex -> assertThat(ex.code().number()).isEqualTo(2843));
        assertThat(store.rows).isEmpty();
    }

    @Test
    void metadataFailureReclaimsTheOrphanBlob(@TempDir Path dir) {
        TrackingBlobStore blobStore = new TrackingBlobStore(new FileBlobStore(dir.resolve("blob")));
        AttachmentStore failing = new InMemoryStore() {
            @Override
            public Attachment insert(NewAttachment a) {
                throw new IllegalStateException("metadata write failed");
            }
        };
        AttachmentService service = new DefaultAttachmentService(blobStore, failing);

        assertThatThrownBy(() -> service.store(
                new AttachmentService.StoreRequest("uploads", "invoice", "INV-1", "n", "text/plain",
                        null, 0),
                stream("data")))
                .isInstanceOf(RuntimeException.class);

        assertThat(blobStore.deleted).isTrue();
    }

    @Test
    void listsAttachmentsOfARecord(@TempDir Path dir) {
        InMemoryStore store = new InMemoryStore();
        AttachmentService service = service(dir, store);
        service.store(new AttachmentService.StoreRequest("uploads", "invoice", "INV-1", "a",
                "text/plain", null, 0), stream("a"));
        service.store(new AttachmentService.StoreRequest("uploads", "invoice", "INV-1", "b",
                "text/plain", null, 0), stream("b"));
        service.store(new AttachmentService.StoreRequest("uploads", "invoice", "INV-2", "c",
                "text/plain", null, 0), stream("c"));

        assertThat(service.list("invoice", "INV-1")).hasSize(2);
        assertThat(service.list("invoice", "INV-2")).hasSize(1);
    }

    /** A {@link BlobStore} that records whether {@code delete} was called, delegating otherwise. */
    private static final class TrackingBlobStore implements BlobStore {
        private final BlobStore delegate;
        boolean deleted;

        TrackingBlobStore(BlobStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public BlobWriter createWriter(BlobSpec spec) {
            return delegate.createWriter(spec);
        }

        @Override
        public InputStream openInput(BlobRef ref) throws IOException {
            return delegate.openInput(ref);
        }

        @Override
        public boolean exists(BlobRef ref) {
            return delegate.exists(ref);
        }

        @Override
        public void delete(BlobRef ref) {
            deleted = true;
            delegate.delete(ref);
        }
    }

    /** A minimal in-memory {@link AttachmentStore} so the service can be tested without a database. */
    private static class InMemoryStore implements AttachmentStore {
        final List<Attachment> rows = new ArrayList<>();

        @Override
        public Attachment insert(NewAttachment a) {
            Attachment row = new Attachment(UUID.randomUUID().toString(), a.entity(), a.entityId(),
                    a.filename(), a.contentType(), a.byteSize(), a.checksum(), a.storageKey(),
                    "clean", a.createdBy(), Instant.now());
            rows.add(row);
            return row;
        }

        @Override
        public Optional<Attachment> find(String id) {
            return rows.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public List<Attachment> list(String entity, String entityId) {
            return rows.stream()
                    .filter(r -> r.entity().equals(entity) && r.entityId().equals(entityId))
                    .toList();
        }
    }
}
