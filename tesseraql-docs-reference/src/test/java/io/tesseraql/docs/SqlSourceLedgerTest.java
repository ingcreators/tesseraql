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
 * A new kind of route SQL is a new {@code SqlSource}, not a new {@code Step}
 * (docs/contract-source-seam.md).
 *
 * <p>The regression this refuses is the one the campaign undid. A route contract used to compile to
 * its own step, which took four constructor arguments where the SQL step took nine — so the
 * statement timeout, tracing, declarative pagination and the row bound each had to be carried
 * across that branch by hand, one at a time, and each arrived late or not at all. A source answers
 * only where a statement comes from and inherits every execution axis; a second step inherits
 * nothing and starts the cycle again.
 *
 * <p><b>What this cannot see, said plainly so it is not mistaken for more than it is.</b> It counts
 * source KINDS, not the axes each configures. A source that resolves the wrong connector, or
 * declares a scope resolver it does not honour, passes here and fails in
 * {@code DeclaredReadParityIntegrationTest}, which asks the two kinds the same question over HTTP
 * and compares the answers. This is the cheap structural half; that one is the behavioural half.
 */
class SqlSourceLedgerTest {

    private static final Path REPO = Path.of("..");

    private static final Pattern IMPLEMENTS_SOURCE = Pattern.compile("implements SqlSource\\b");

    /**
     * Every kind of route SQL there is.
     *
     * <ul>
     * <li>{@code FileSqlSource} — a statement the application ships as a file.</li>
     * <li>{@code ContractSqlSource} — a statement a realm satisfies, resolved per exchange because
     * a realm is a runtime bean and a compile-time one reads the wrong application home.</li>
     * </ul>
     */
    private static final Set<String> LEDGER = new TreeSet<>(List.of(
            "tesseraql-pipeline/src/main/java/io/tesseraql/pipeline/iam/ContractSqlSource.java",
            "tesseraql-pipeline/src/main/java/io/tesseraql/pipeline/sql/FileSqlSource.java"));

    @Test
    void everyKindOfRouteSqlIsOnTheLedger() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    // A .claude/ worktree on disk carries stale copies of exactly these files.
                    .filter(path -> !path.toString().contains("/."))
                    .forEach(path -> {
                        try {
                            if (IMPLEMENTS_SOURCE.matcher(Files.readString(path)).find()) {
                                found.add(REPO.relativize(path).toString().replace('\\', '/'));
                            }
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    });
        }

        assertThat(found)
                .as("main-source implementations of SqlSource — a new kind of route SQL is a new"
                        + " SqlSource, not a new Step: a step would have to be handed every"
                        + " execution axis by hand, which is how the contract path spent four"
                        + " retrofits catching up. Add it here in review with a reason, and give"
                        + " it a leg in DeclaredReadParityIntegrationTest")
                .containsExactlyInAnyOrderElementsOf(LEDGER);
    }
}
