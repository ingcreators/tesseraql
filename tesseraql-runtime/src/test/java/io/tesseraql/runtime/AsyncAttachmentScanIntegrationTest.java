package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.attachment.AttachmentService;
import io.tesseraql.core.attachment.AttachmentStore;
import io.tesseraql.core.blob.FileBlobStore;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.scan.AttachmentScanner;
import io.tesseraql.core.scan.ScanVerdict;
import io.tesseraql.operations.attachment.AttachmentScanSweeper;
import io.tesseraql.operations.attachment.DefaultAttachmentService;
import io.tesseraql.operations.attachment.JdbcAttachmentStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Asynchronous attachment scanning end to end at the service level (docs/attachments.md,
 * "Scanning"): an async upload records {@code pending} and is held by the existing non-clean
 * download gate; the sweep claims (compare-and-set, lease-aged reclaim), scans, and records
 * the verdict; failures retry to a cap and then fail closed as {@code error}.
 */
@Testcontainers
class AsyncAttachmentScanIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static JdbcAttachmentStore store;

    /** The controllable scanner: the verdict every scan returns. */
    static final AtomicReference<ScanVerdict> VERDICT = new AtomicReference<>();

    static final AttachmentScanner SCANNER = new AttachmentScanner() {
        @Override
        public String id() {
            return "test";
        }

        @Override
        public ScanVerdict scan(io.tesseraql.core.blob.BlobRef ref, ContentSource content) {
            return VERDICT.get();
        }
    };

    @BeforeAll
    static void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        store = new JdbcAttachmentStore(dataSource);
        store.ensureSchema();
    }

    private static AttachmentStore.Attachment upload(AttachmentService service, String entity) {
        return service.store(new AttachmentService.StoreRequest("attachments", entity, "R-1",
                "a.txt", "text/plain", "alice", 0),
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void pendingUploadsAreHeldThenServedOnceTheSweepDecides(@TempDir Path blobs) {
        FileBlobStore blobStore = new FileBlobStore(blobs);
        DefaultAttachmentService service = new DefaultAttachmentService(blobStore, store,
                SCANNER, "quarantine", true);
        AttachmentScanSweeper sweeper = new AttachmentScanSweeper(blobStore, store, SCANNER,
                "quarantine", 5, Duration.ofMinutes(5), 100);

        // The async upload returns immediately as pending — and the gate holds it back.
        AttachmentStore.Attachment a = upload(service, "invoice");
        assertThat(a.scanStatus()).isEqualTo("pending");
        assertThatThrownBy(() -> service.fetch(a.id(), "invoice", "R-1"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("awaiting its malware scan");

        // The sweep decides clean: the very same gate now serves it.
        VERDICT.set(ScanVerdict.clean());
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(service.fetch(a.id(), "invoice", "R-1")).isPresent();
        assertThat(store.find(a.id()).orElseThrow().scanStatus()).isEqualTo("clean");
    }

    @Test
    void anInfectedVerdictDeletesPerPolicyAndStaysRefused(@TempDir Path blobs) {
        FileBlobStore blobStore = new FileBlobStore(blobs);
        DefaultAttachmentService service = new DefaultAttachmentService(blobStore, store,
                SCANNER, "delete", true);
        AttachmentScanSweeper sweeper = new AttachmentScanSweeper(blobStore, store, SCANNER,
                "delete", 5, Duration.ofMinutes(5), 100);

        AttachmentStore.Attachment a = upload(service, "malware");
        VERDICT.set(ScanVerdict.infected("eicar"));
        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(store.find(a.id()).orElseThrow().scanStatus()).isEqualTo("infected");
        assertThatThrownBy(() -> service.fetch(a.id(), "malware", "R-1"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("did not pass malware scanning");
    }

    @Test
    void scannerFailuresRetryToTheCapThenFailClosedAsError(@TempDir Path blobs) {
        FileBlobStore blobStore = new FileBlobStore(blobs);
        DefaultAttachmentService service = new DefaultAttachmentService(blobStore, store,
                SCANNER, "quarantine", true);
        AttachmentScanSweeper sweeper = new AttachmentScanSweeper(blobStore, store, SCANNER,
                "quarantine", 2, Duration.ofMinutes(5), 100);

        AttachmentStore.Attachment a = upload(service, "flaky");
        VERDICT.set(ScanVerdict.error("engine down"));

        // First failure returns the row to pending; the second hits the cap and fails closed.
        assertThat(sweeper.sweep()).isZero();
        assertThat(store.find(a.id()).orElseThrow().scanStatus()).isEqualTo("pending");
        assertThat(sweeper.sweep()).isZero();
        assertThat(store.find(a.id()).orElseThrow().scanStatus()).isEqualTo("error");
        assertThatThrownBy(() -> service.fetch(a.id(), "flaky", "R-1"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("did not pass malware scanning");
    }

    @Test
    void claimsAreExclusiveAndAgedLeasesReclaim(@TempDir Path blobs) {
        FileBlobStore blobStore = new FileBlobStore(blobs);
        DefaultAttachmentService service = new DefaultAttachmentService(blobStore, store,
                SCANNER, "quarantine", true);
        AttachmentStore.Attachment a = upload(service, "claims");

        // The first claim wins; a second claimer sees nothing while the lease is fresh.
        assertThat(store.claimForScan(100, Instant.now().minus(Duration.ofMinutes(5))))
                .extracting(AttachmentStore.Attachment::id).contains(a.id());
        assertThat(store.claimForScan(100, Instant.now().minus(Duration.ofMinutes(5))))
                .extracting(AttachmentStore.Attachment::id).doesNotContain(a.id());
        // A dead node's claim ages out: with the cutoff in the future the row reclaims.
        assertThat(store.claimForScan(100, Instant.now().plus(Duration.ofSeconds(1))))
                .extracting(AttachmentStore.Attachment::id).contains(a.id());
        store.recordScanVerdict(a.id(), "clean");
    }
}
