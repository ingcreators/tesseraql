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
 * The path-confinement ledger (docs/duplication-consolidation.md, campaign 2, the
 * {@code SqlExecutorLedgerTest} pattern): every main source file where a {@code .normalize()}
 * is followed by a {@code .startsWith(} within a few lines — the shape of a hand-rolled
 * confinement guard — is named here. The campaign found twenty-two hand-written guards that
 * disagreed on the one thing that makes the check hold (both sides absolutized and normalized;
 * one guard was vacuous against a relative root) and retired them onto
 * {@code io.tesseraql.core.files.ConfinedPath}; this ledger keeps them retired.
 *
 * <p><b>A new entry is refused by default.</b> A caller-influenced path stays under its root by
 * going through {@code ConfinedPath}; a build that hand-rolls the sequence again must say so
 * here, in review, with a reason. Removing an entry just shrinks the list.
 *
 * <p>What stays, and why:
 *
 * <ul>
 * <li>{@code ConfinedPath} — the primitive itself.</li>
 * <li>{@code AppPackager}, {@code ViewEjects}, {@code ManifestLoader} — prefix
 * <em>classification</em>, not confinement: skipping the work tree while packaging, choosing a
 * relative reference shape, pruning a walk. Nothing caller-influenced is being confined, so
 * forcing them through the primitive would dress bookkeeping as a security guard.</li>
 * </ul>
 */
class PathGuardLedgerTest {

    private static final Path REPO = Path.of("..");

    private static final Set<String> LEDGER = new TreeSet<>(List.of(
            "tesseraql-apptasks/src/main/java/io/tesseraql/apptasks/AppPackager.java",
            "tesseraql-core/src/main/java/io/tesseraql/core/files/ConfinedPath.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/manifest/ManifestLoader.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/view/ViewEjects.java"));

    @Test
    void everyHandRolledGuardShapeIsOnTheLedger() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .forEach(path -> {
                        try {
                            if (hasGuardShape(Files.readAllLines(path))) {
                                found.add(REPO.relativize(path).toString().replace('\\', '/'));
                            }
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    });
        }
        assertThat(found)
                .as("main-source files where .normalize() meets .startsWith( within three"
                        + " lines — the hand-rolled confinement shape: route it through"
                        + " io.tesseraql.core.files.ConfinedPath instead, or add it here in"
                        + " review with a reason; a REMOVED entry just shrinks this list")
                .containsExactlyInAnyOrderElementsOf(LEDGER);
    }

    private static boolean hasGuardShape(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).contains(".normalize()")) {
                continue;
            }
            for (int j = i; j < Math.min(i + 4, lines.size()); j++) {
                if (lines.get(j).contains(".startsWith(")) {
                    return true;
                }
            }
        }
        return false;
    }
}
