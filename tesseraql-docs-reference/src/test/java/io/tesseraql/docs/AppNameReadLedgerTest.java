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
 * The {@code tesseraql.app.name} read ledger (docs/duplication-consolidation.md, campaign 4,
 * the {@code SqlDefaults} single-read precedent): every main source file reading the raw key is
 * named here. The name is an <b>identity</b> — it scopes outbox claims and job ownership, it is
 * what {@code tql.ops.view.<name>} grants are checked against, and in a stack it is the
 * application's address — and the campaign found seven sites defaulting it to three different
 * values, re-introducing exactly the shared identity {@code ApplicationName}'s javadoc records
 * as the bug it exists to prevent (one site named the OpenTelemetry service, so unnamed
 * applications merged in traces).
 *
 * <p><b>A new entry is refused by default.</b> The name is read through
 * {@code io.tesseraql.yaml.app.ApplicationName} — {@code of()} where absence refuses,
 * {@code ifValid()} for a backstop that must not own the refusal. What stays, and why:
 *
 * <ul>
 * <li>{@code ApplicationName} — the accessor itself.</li>
 * <li>{@code ApplicationNameRules}, {@code PolicyCodeRules}, {@code DeclaredRoleRules} — the
 * lint on this very key: it reports absence as a finding rather than throwing, before a boot
 * has to.</li>
 * <li>{@code AppInstaller} — refuses a package without a name under its own
 * {@code INVALID_PACKAGE}, naming the package file rather than the running app.</li>
 * </ul>
 */
class AppNameReadLedgerTest {

    private static final Path REPO = Path.of("..");

    private static final Set<String> LEDGER = new TreeSet<>(List.of(
            "tesseraql-operations/src/main/java/io/tesseraql/operations/app/AppInstaller.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/app/ApplicationName.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/lint/ApplicationNameRules.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/lint/DeclaredRoleRules.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/lint/PolicyCodeRules.java"));

    @Test
    void everyRawReadOfTheNameIsOnTheLedger() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    // Hidden directories are never main sources, and a .claude/ worktree on
                    // disk carries stale copies of exactly the files this ledger greps.
                    .filter(path -> !path.toString().contains("/."))
                    .forEach(path -> {
                        try {
                            // Any config accessor, not getString alone — a navigate() or
                            // typed read of the key is the same raw read wearing another
                            // method. A bare mention (a scaffold key registry, a Studio
                            // settings descriptor) is not a read and stays out.
                            if (Files.readString(path).matches("(?s).*\\.(getString|navigate"
                                    + "|getDouble|getBoolean)\\(\"tesseraql\\.app\\.name\"\\)"
                                    + ".*")) {
                                found.add(REPO.relativize(path).toString().replace('\\', '/'));
                            }
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    });
        }
        assertThat(found)
                .as("main-source files reading tesseraql.app.name raw — the name is an"
                        + " identity: read it through io.tesseraql.yaml.app.ApplicationName"
                        + " (of() to refuse absence, ifValid() for a backstop) instead, or"
                        + " add the file here in review with a reason")
                .containsExactlyInAnyOrderElementsOf(LEDGER);
    }
}
