package io.tesseraql.cli.modules;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModulesLockTest {

    @Test
    void writesSortedJsonRoundTripsAndVerifies(@TempDir Path dir) {
        List<ModuleCoordinate> declared = List
                .of(ModuleCoordinate.parse("io.tesseraql:tesseraql-pdf"));
        List<ResolvedModule> resolved = List.of(
                new ResolvedModule("org.x:y:1.0", dir.resolve("y.jar"), "aaa"),
                new ResolvedModule("org.a:b:2.0", dir.resolve("b.jar"), "bbb"));

        ModulesLock lock = ModulesLock.from(declared, resolved);
        // Artifacts are sorted by coordinate for byte-stable diffs.
        assertThat(lock.artifacts()).extracting(ModulesLock.Artifact::coordinate)
                .containsExactly("org.a:b:2.0", "org.x:y:1.0");

        Path file = dir.resolve("modules.lock");
        lock.write(file);
        ModulesLock read = ModulesLock.read(file).orElseThrow();
        assertThat(read.modules()).containsExactly("io.tesseraql:tesseraql-pdf");
        assertThat(read.verify(resolved)).isEmpty();

        // A changed checksum is a verification failure.
        List<ResolvedModule> tampered = List.of(
                new ResolvedModule("org.x:y:1.0", dir.resolve("y.jar"), "ZZZ"));
        assertThat(read.verify(tampered)).isNotEmpty();
    }

    @Test
    void readMissingLockIsEmpty(@TempDir Path dir) {
        assertThat(ModulesLock.read(dir.resolve("absent.lock"))).isEmpty();
    }
}
