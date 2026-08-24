package io.tesseraql.core.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFilesTest {

    /**
     * {@code ATOMIC_MOVE} keeps the source's mode, and the temp is created {@code 0600} — a
     * replace must not silently tighten a file another process reads (the host's reconciler
     * reading the catalog JSON is the live case). POSIX-only by nature; on a non-POSIX store
     * the platform default applies and there is nothing to preserve.
     */
    @Test
    void aReplaceKeepsTheTargetsPermissions(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("catalog.json");
        Files.writeString(target, "old");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileAttributeView(target,
                java.nio.file.attribute.PosixFileAttributeView.class) != null);
        java.util.Set<java.nio.file.attribute.PosixFilePermission> mode = java.nio.file.attribute.PosixFilePermissions
                .fromString("rw-r--r--");
        Files.setPosixFilePermissions(target, mode);

        AtomicFiles.replace(target, "new".getBytes(StandardCharsets.UTF_8));

        assertThat(Files.getPosixFilePermissions(target)).isEqualTo(mode);
    }

    @Test
    void replacesBytesAndLeavesNoTemp(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("state.json");
        Files.writeString(target, "old");

        AtomicFiles.replace(target, "new".getBytes(StandardCharsets.UTF_8));

        assertThat(Files.readString(target)).isEqualTo("new");
        assertThat(listing(dir)).containsExactly("state.json");
    }

    @Test
    void streamsIntoPlaceCreatingTheDirectory(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("nested/spool.bin");

        AtomicFiles.replace(target, new ByteArrayInputStream(new byte[]{1, 2, 3}));

        assertThat(Files.readAllBytes(target)).containsExactly(1, 2, 3);
    }

    /** A failed write must not leave a temp file behind, or replace the target. */
    @Test
    void aFailedWriteLeavesTheTargetAndNoTemp(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("state.json");
        Files.writeString(target, "old");
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("torn");
            }
        };

        assertThatThrownBy(() -> AtomicFiles.replace(target, failing))
                .isInstanceOf(IOException.class);

        assertThat(Files.readString(target)).isEqualTo("old");
        assertThat(listing(dir)).containsExactly("state.json");
    }

    @Test
    void theSweepPredicateRecognizesThisClasssInFlightTemps(@TempDir Path dir)
            throws IOException {
        Path target = dir.resolve("data.parquet");
        java.util.List<String> midWrite = new java.util.ArrayList<>();
        InputStream capturing = new InputStream() {
            @Override
            public int read() throws IOException {
                if (midWrite.isEmpty()) {
                    midWrite.addAll(listing(dir));
                }
                return -1;
            }
        };

        AtomicFiles.replace(target, capturing);

        // The names actually on disk mid-write are the ones a sweeper must skip; the coupling
        // this pins is isTemp against tempBeside's real naming, not a hard-coded pattern.
        assertThat(midWrite).isNotEmpty().allMatch(AtomicFiles::isTemp);
        assertThat(AtomicFiles.isTemp(target.getFileName().toString())).isFalse();
    }

    private static java.util.List<String> listing(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
