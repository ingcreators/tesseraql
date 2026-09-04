package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The savepoint ledger: a savepoint's lifecycle is the one JDBC construct this framework cannot
 * treat as portable, and this test names every main source that opens one.
 *
 * <p>Twice now the same defect has shipped. In 0.5.0 the queue dedup insert released its savepoint
 * explicitly and failed on Oracle and SQL Server, whose drivers answer {@code releaseSavepoint}
 * with a {@code SQLFeatureNotSupportedException}. The document-sequence store then did it again,
 * and worse: it released in a {@code finally}, and a {@code finally} that throws discards the
 * value the method was returning, so the <em>success</em> path of a first allocation failed on
 * both dialects. Neither was caught, because no suite exercised the path on those vendors.
 *
 * <p><b>The rule.</b> Do not release a savepoint. The commit that follows releases it implicitly
 * on every dialect, and an unreleased savepoint dies with its transaction. The one recorded
 * exemption is {@code JdbcFileTransferService.releaseQuietly}, which swallows the refusal and
 * says so.
 *
 * <p><b>The second rule.</b> Code that opens a savepoint depends on vendor behaviour, so it owes a
 * live check in {@code DialectRuntimeChecks} that the gated portability suites run, or a stated
 * reason it needs none. A new entry below with neither is the shape both incidents had.
 */
class SavepointLedgerTest {

    private static final Path REPO = Path.of("..");

    /** Main sources that open a savepoint, each with what exercises it on a real vendor. */
    private static final Set<String> LEDGER = new TreeSet<>(List.of(
            // DialectRuntimeChecks.eventChannelRoundTrip.
            "tesseraql-operations/src/main/java/io/tesseraql/operations/messaging/JdbcEventChannelStore.java",
            // DialectRuntimeChecks.fileTransferRoundTrip.
            "tesseraql-operations/src/main/java/io/tesseraql/operations/files/JdbcFileTransferService.java",
            // DialectRuntimeChecks.documentSequenceRoundTrip.
            "tesseraql-operations/src/main/java/io/tesseraql/operations/sequence/JdbcDocumentSequences.java",
            // The chunk step's per-row skip fence. No dialect check of its own: BatchJobIntegrationTest
            // covers the skip path on PostgreSQL only, so the rollback-to-savepoint behaviour on
            // Oracle and SQL Server rests on the two checks above exercising the same construct.
            "tesseraql-operations/src/main/java/io/tesseraql/operations/batch/ChunkStepRunner.java",
            // The declarative suite's transition fence, which runs against whichever dialect the
            // application under test is on — it is exercised by every workflow suite, on that
            // vendor, rather than by a check of its own.
            "tesseraql-test-core/src/main/java/io/tesseraql/test/WorkflowCases.java"));

    /**
     * The single site allowed to call {@code releaseSavepoint}: it catches the refusal and states
     * that the release is best-effort hygiene.
     */
    private static final String RELEASE_EXEMPTION = "tesseraql-operations/src/main/java/io/tesseraql/operations/files/JdbcFileTransferService.java";

    @Test
    void everySavepointSiteIsOnTheLedger() throws IOException {
        assertThat(mainSourcesContaining("setSavepoint("))
                .as("main-source files opening a JDBC savepoint — a NEW entry owes a live check in"
                        + " DialectRuntimeChecks, wired into the Oracle, SQL Server and MySQL"
                        + " portability tests, because a savepoint's behaviour is where this"
                        + " framework has shipped the same dialect bug twice")
                .containsExactlyInAnyOrderElementsOf(LEDGER);
    }

    @Test
    void nothingReleasesASavepointExceptTheRecordedExemption() throws IOException {
        assertThat(mainSourcesContaining("releaseSavepoint("))
                .as("releaseSavepoint is a SQLFeatureNotSupportedException on the Oracle and SQL"
                        + " Server drivers, and in a finally block it discards the enclosing"
                        + " return — the 0.5.0 queue-dedup bug and its 2026 repeat in the document"
                        + " sequence store. The commit releases the savepoint on every dialect;"
                        + " let it")
                .containsExactly(RELEASE_EXEMPTION);
    }

    private static Set<String> mainSourcesContaining(String needle) throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    // Hidden directories are never main sources, and a .claude/ worktree on disk
                    // carries stale copies of exactly the files this ledger greps.
                    .filter(path -> !path.toString().contains("/."))
                    .forEach(path -> {
                        try {
                            if (Files.readString(path).contains(needle)) {
                                found.add(REPO.relativize(path).toString().replace('\\', '/'));
                            }
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    });
        }
        return found;
    }
}
