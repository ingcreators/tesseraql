package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Who owns a JDBC transaction (docs/two-way-sql-parser.md decision 17).
 *
 * <p>The regression this refuses is a fifteenth hand-rolled bracket. Restoring autocommit
 * <b>commits an open transaction</b> — the {@link java.sql.Connection#setAutoCommit(boolean)}
 * contract — so a hand-written owner whose catch lists anything less than everything commits the
 * work it was told to abandon. Fourteen existed; the narrowest had no catch at all and landed a
 * partial CSV import under a FAILED verdict.
 *
 * <p><b>What this cannot see.</b> It finds owners by their {@code setAutoCommit(false)}, so it
 * counts brackets rather than judging them: a listed owner that quietly narrows its catch again
 * passes here. It is the structural half. What it does buy is that a new owner cannot appear
 * without someone reading this javadoc and deciding, which is how the fourteen accumulated.
 */
class TransactionOwnerLedgerTest {

    private static final Path REPO = Path.of("..");

    private static final Pattern TAKES_AUTOCOMMIT_OFF = Pattern.compile("setAutoCommit\\(false\\)");

    /**
     * Every file that takes autocommit off, and why it is not a caller of {@code Transactions}.
     *
     * <ul>
     * <li>{@code Transactions} — the primitive itself.</li>
     * <li>{@code TransactionalCommandProcessor}, {@code JdbcSessionStore},
     * {@code JdbcFileTransferService}, {@code SqlStep} — bodies that capture a mutable local,
     * return from inside the transaction, or choose their own commit point, so they cannot be a
     * lambda without restructuring. Each writes the rule out: roll back on any {@code Throwable}
     * before the restore.</li>
     * <li>{@code ChunkStepRunner}, {@code JdbcTotpStore} — take autocommit off and never restore
     * it, so the commit-on-restore hazard cannot fire; they rely on the pool resetting a returned
     * connection.</li>
     * <li>{@code SandboxDataSource} — deliberately holds a transaction open and rolls it back, so
     * a Studio preview never writes.</li>
     * <li>{@code SqlCases}, {@code WorkflowCases} — the suite runner's own per-case transaction,
     * in test-support code that is not on a request path.</li>
     * </ul>
     */
    private static final Set<String> LEDGER = new TreeSet<>(List.of(
            "tesseraql-compiler/src/main/java/io/tesseraql/compiler/binding/"
                    + "TransactionalCommandProcessor.java",
            "tesseraql-core/src/main/java/io/tesseraql/core/sql/Transactions.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/batch/"
                    + "ChunkStepRunner.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/files/"
                    + "JdbcFileTransferService.java",
            "tesseraql-pipeline/src/main/java/io/tesseraql/pipeline/sql/SqlStep.java",
            "tesseraql-security/src/main/java/io/tesseraql/security/session/JdbcSessionStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/credential/"
                    + "JdbcTotpStore.java",
            "tesseraql-studio-runtime/src/main/java/io/tesseraql/studio/runtime/"
                    + "SandboxDataSource.java",
            "tesseraql-test-core/src/main/java/io/tesseraql/test/SqlCases.java",
            "tesseraql-test-core/src/main/java/io/tesseraql/test/WorkflowCases.java"));

    @Test
    void everyTransactionOwnerIsOnTheLedger() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    // A .claude/ worktree on disk carries stale copies of exactly these files.
                    .filter(path -> !path.toString().contains("/."))
                    .forEach(path -> {
                        try {
                            if (TAKES_AUTOCOMMIT_OFF.matcher(Files.readString(path)).find()) {
                                found.add(REPO.relativize(path).toString().replace('\\', '/'));
                            }
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    });
        }

        assertThat(found)
                .as("files that take autocommit off. Call Transactions.run or Transactions.call"
                        + " instead: restoring autocommit commits an open transaction, so a"
                        + " hand-written bracket whose catch lists anything less than everything"
                        + " commits the work it was told to abandon. If a body genuinely cannot"
                        + " be a lambda, add it here with the reason and write the rule out")
                .containsExactlyInAnyOrderElementsOf(LEDGER);
    }
}
