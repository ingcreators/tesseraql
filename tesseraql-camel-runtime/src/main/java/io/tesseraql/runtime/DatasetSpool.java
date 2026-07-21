package io.tesseraql.runtime;

import io.tesseraql.core.attachment.AttachmentStore;
import io.tesseraql.core.blob.BlobRef;
import io.tesseraql.core.blob.BlobStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The dataset bridge (docs/duckdb.md): materializes a blob-stored dataset as a local,
 * content-addressed file the fenced engine can read. One spool serves every blob-store backend —
 * a filesystem store could serve zero-copy, but the spool keeps the engine's
 * {@code allowed_directories} to exactly one extra directory. Files are keyed by the attachment's
 * checksum, written via a unique temp file and an atomic move (concurrent localizations converge
 * on the same content), touched on every hit, and swept least-recently-used past a cap.
 */
final class DatasetSpool {

    private static final int MAX_ENTRIES = 256;

    private final BlobStore blobStore;
    private final Path directory;

    DatasetSpool(BlobStore blobStore, Path directory) {
        this.blobStore = blobStore;
        this.directory = directory;
    }

    Path directory() {
        return directory;
    }

    /** The local path of the attachment's content, streamed from the blob store on first use. */
    Path localize(AttachmentStore.Attachment attachment) {
        String key = attachment.checksum() == null || attachment.checksum().isBlank()
                ? attachment.id()
                : attachment.checksum();
        Path target = directory.resolve(key.replaceAll("[^A-Za-z0-9._-]", "_") + extensionOf(
                attachment.filename()));
        try {
            if (Files.isRegularFile(target)) {
                Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.from(
                        java.time.Instant.now()));
                return target;
            }
            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, ".spool", ".tmp");
            try (InputStream in = blobStore.openInput(new BlobRef(attachment.storageKey(),
                    attachment.contentType(), attachment.byteSize(), attachment.checksum(),
                    attachment.createdAt()))) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            sweep();
            return target;
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "Could not localize dataset " + attachment.id() + " into the spool", failure);
        }
    }

    /** Deletes the least-recently-touched spool files beyond the cap (best effort). */
    private void sweep() throws IOException {
        List<Path> entries;
        try (Stream<Path> files = Files.list(directory)) {
            entries = files.filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().startsWith(".spool"))
                    .sorted(Comparator.comparingLong(f -> f.toFile().lastModified()))
                    .toList();
        }
        for (int i = 0; i < entries.size() - MAX_ENTRIES; i++) {
            try {
                Files.deleteIfExists(entries.get(i));
            } catch (IOException ignored) {
                // an entry another node/thread holds open just survives this sweep
            }
        }
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).replaceAll("[^A-Za-z0-9.]", "");
    }
}
