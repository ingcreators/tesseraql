package io.tesseraql.core.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfinedPathTest {

    @Test
    void resolvesUnderTheRootAndRefusesEscape(@TempDir Path dir) {
        ConfinedPath root = ConfinedPath.under(dir);

        assertThat(root.resolve("a/b.txt")).contains(dir.resolve("a/b.txt"));
        assertThat(root.resolve("a/../b.txt")).contains(dir.resolve("b.txt"));
        assertThat(root.resolve("../outside.txt")).isEmpty();
        assertThat(root.resolve("a/../../outside.txt")).isEmpty();
        assertThat(root.resolve("/etc/passwd")).isEmpty();
    }

    /**
     * The defect the survey found live: a guard that normalizes only the candidate compares it
     * against whatever shape the root arrived in, so a relative root made the check vacuous.
     * The root is canonicalized here, once, and the comparison holds.
     */
    @Test
    void aRelativeRootStillConfines() {
        ConfinedPath root = ConfinedPath.under(Path.of("work/scope"));

        assertThat(root.root().isAbsolute()).isTrue();
        assertThat(root.resolve("file.txt")).isPresent();
        assertThat(root.resolve("../../../etc/passwd")).isEmpty();
    }

    /** A {@code ..}-carrying root is folded before it becomes the boundary. */
    @Test
    void aDotDotRootIsFoldedBeforeItConfines(@TempDir Path dir) {
        ConfinedPath root = ConfinedPath.under(dir.resolve("a/../scope"));

        assertThat(root.root()).isEqualTo(dir.resolve("scope"));
        assertThat(root.resolve("../a/secret.txt")).isEmpty();
    }

    @Test
    void confinesACallerBuiltPath(@TempDir Path dir) {
        ConfinedPath root = ConfinedPath.under(dir);

        assertThat(root.confine(dir.resolve("tenant/2026/data.parquet"))).isPresent();
        assertThat(root.confine(dir.resolve("tenant/../../elsewhere"))).isEmpty();
        assertThat(root.contains(dir.resolve("x"))).isTrue();
        assertThat(root.contains(dir.getParent())).isFalse();
    }

    /** The root itself is inside the root — an empty relative resolve answers the root. */
    @Test
    void theRootContainsItself(@TempDir Path dir) {
        ConfinedPath root = ConfinedPath.under(dir);
        assertThat(root.resolve("")).contains(root.root());
        assertThat(root.contains(dir)).isTrue();
    }
}
