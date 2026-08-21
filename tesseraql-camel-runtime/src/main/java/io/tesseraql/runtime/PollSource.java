package io.tesseraql.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The directory a {@code poll:} job watches, and the four things one poll cycle asks of it
 * (docs/camel-removal.md slice 1).
 *
 * <p>This is what a file consumer turned out to be once the endpoint URI was taken away: list the
 * directory, re-read a file's fingerprint to decide whether it is still being written, put its
 * bytes somewhere the import can read them, and move it aside afterwards. Everything else the
 * cycle does — the glob, the write-stability rule, the cross-replica claim, the archive
 * directories — is the same for every transport and lives in {@link PollLoop}, so a transport
 * cannot quietly implement one of those rules differently. That is the failure this design is
 * shaped against: the FTPS data channel stayed unencrypted for a year because one transport's
 * settings lived somewhere the other's did not.
 */
interface PollSource extends AutoCloseable {

    /**
     * One file the source is offering.
     *
     * @param name     the file name within the polled directory
     * @param size     its size in bytes
     * @param modified its modification time, in milliseconds since the epoch
     */
    record PolledFile(String name, long size, long modified) {

        /**
         * The key a {@code consumeOnce:} source claims the file under.
         *
         * <p>Name, size and modification time rather than the path alone: a partner legitimately
         * re-sending a file under a name it has used before would otherwise be suppressed for the
         * whole retention window, while an identical re-delivery still is.
         */
        String key() {
            return name + "-" + size + "-" + modified;
        }
    }

    /**
     * Where a fetched file's bytes are, and whether this cycle owns that copy.
     *
     * <p>A local source hands back the polled file itself — the import spools from a file already
     * on disk, and nothing should copy it first. A remote source hands back a download under the
     * work directory, and {@link #release()} deletes it.
     */
    record Fetched(Path path, boolean temporary) {

        void release() {
            if (temporary) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover download is not worth failing a poll cycle over; the work
                    // directory is swept with the rest of the app's working files.
                }
            }
        }
    }

    /** Every plain file directly in the polled directory; sub-directories are not descended into. */
    List<PolledFile> list() throws IOException;

    /** The file's fingerprint as it stands now, or empty when it has gone. */
    Optional<PolledFile> stat(String name) throws IOException;

    /** Puts the file's bytes on local disk and answers where; the caller releases the result. */
    Fetched fetch(PolledFile file) throws IOException;

    /** Moves the file into {@code directory} beneath the polled directory, creating it if needed. */
    void archive(PolledFile file, String directory) throws IOException;

    @Override
    void close();
}
