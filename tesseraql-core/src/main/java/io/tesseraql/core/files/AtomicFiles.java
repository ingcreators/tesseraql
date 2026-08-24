package io.tesseraql.core.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Write-then-atomically-replace, the way five sites did it by copy
 * (docs/duplication-consolidation.md, campaign 4): the bytes land in a temp file <em>beside</em>
 * the target — an atomic move needs one filesystem — and the move is
 * {@code ATOMIC_MOVE + REPLACE_EXISTING}, so a reader never sees a half-written file. One copy
 * had drifted to a plain move (a crash mid-replace could leave a torn file), and the copies
 * disagreed on whether a failed write leaves its temp behind; here it never survives.
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /** Writes {@code bytes} beside {@code target} and atomically replaces it. */
    public static void replace(Path target, byte[] bytes) throws IOException {
        Path temp = tempBeside(target);
        try {
            Files.write(temp, bytes);
            move(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Streams {@code content} into a temp beside {@code target} and atomically replaces it. */
    public static void replace(Path target, InputStream content) throws IOException {
        Path temp = tempBeside(target);
        try {
            Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
            move(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Whether {@code fileName} is one of this class's in-flight temps — for a sweeper listing a
     * directory this class writes into: a write still in progress must neither be counted as an
     * entry nor deleted mid-copy. Coupled to {@link #tempBeside}'s naming.
     */
    public static boolean isTemp(String fileName) {
        return fileName.endsWith(".tmp");
    }

    private static Path tempBeside(Path target) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        return Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
    }

    private static void move(Path temp, Path target) throws IOException {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }
}
