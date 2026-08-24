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
 *
 * <p>The temp's contents are forced to storage before the move, so a crash straight after a
 * replace cannot surface the <em>new</em> name over <em>empty</em> contents — the classic
 * rename-before-data loss. The rename's own durability rides the filesystem's journal, as it
 * does for every writer. And a replaced target keeps the permissions it had: the temp is
 * created {@code 0600} (a safe default for a <em>new</em> file), which must not silently
 * tighten a file another process reads — the host's reconciler reading the catalog JSON is
 * the live case.
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /** Writes {@code bytes} beside {@code target} and atomically replaces it. */
    public static void replace(Path target, byte[] bytes) throws IOException {
        Path temp = tempBeside(target);
        try {
            Files.write(temp, bytes);
            settle(temp, target);
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
            settle(temp, target);
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

    /**
     * Readies the written temp to take the target's place: force the bytes to storage (the
     * rename must never become visible over unwritten contents), and carry over an existing
     * target's permissions ({@code ATOMIC_MOVE} keeps the <em>source's</em> mode, and the
     * temp's restrictive {@code 0600} default must not silently tighten a replaced file).
     */
    private static void settle(Path temp, Path target) throws IOException {
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temp,
                java.nio.file.StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        java.nio.file.attribute.PosixFileAttributeView posix = Files.getFileAttributeView(target,
                java.nio.file.attribute.PosixFileAttributeView.class);
        if (posix != null) {
            try {
                Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target));
            } catch (java.nio.file.NoSuchFileException firstWrite) {
                // No target yet: the temp's restrictive default is the safe mode for a new file.
            }
        }
    }

    private static void move(Path temp, Path target) throws IOException {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }
}
