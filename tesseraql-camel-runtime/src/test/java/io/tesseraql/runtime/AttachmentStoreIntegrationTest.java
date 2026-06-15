package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.attachment.AttachmentStore;
import io.tesseraql.operations.attachment.JdbcAttachmentStore;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 30 slice 1: the managed {@code tql_attachment} store inserts a metadata row, finds it by id,
 * and lists a record's attachments — verified on a real PostgreSQL via Testcontainers.
 */
@Testcontainers
class AttachmentStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static JdbcAttachmentStore store;

    @BeforeAll
    static void setUp() {
        store = new JdbcAttachmentStore(dataSource());
        store.ensureSchema();
    }

    @Test
    void insertsFindsAndScopesToARecord() {
        AttachmentStore.Attachment a = store.insert(new AttachmentStore.NewAttachment(
                "invoice", "INV-100", "scan.pdf", "application/pdf", 2048, "abc123",
                "uploads/key-1", "alice"));

        assertThat(a.id()).isNotBlank();
        assertThat(a.scanStatus()).isEqualTo("clean");
        assertThat(a.byteSize()).isEqualTo(2048);
        assertThat(a.createdAt()).isNotNull();

        assertThat(store.find(a.id())).isPresent().get()
                .satisfies(found -> {
                    assertThat(found.filename()).isEqualTo("scan.pdf");
                    assertThat(found.storageKey()).isEqualTo("uploads/key-1");
                    assertThat(found.createdBy()).isEqualTo("alice");
                });
        assertThat(store.find("does-not-exist")).isEmpty();
    }

    @Test
    void listsOnlyTheOwningRecordsAttachments() {
        store.insert(new AttachmentStore.NewAttachment("order", "ORD-1", "a.txt", "text/plain", 1,
                "h1", "uploads/a", null));
        store.insert(new AttachmentStore.NewAttachment("order", "ORD-1", "b.txt", "text/plain", 1,
                "h2", "uploads/b", null));
        store.insert(new AttachmentStore.NewAttachment("order", "ORD-2", "c.txt", "text/plain", 1,
                "h3", "uploads/c", null));

        assertThat(store.list("order", "ORD-1")).hasSize(2);
        assertThat(store.list("order", "ORD-2")).hasSize(1);
        assertThat(store.list("order", "ORD-404")).isEmpty();
    }

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}
