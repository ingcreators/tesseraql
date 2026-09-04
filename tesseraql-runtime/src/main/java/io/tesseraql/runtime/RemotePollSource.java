package io.tesseraql.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A polled directory on a remote server, whichever protocol reaches it
 * (docs/camel-removal.md decision 4).
 *
 * <p>Everything protocol-specific is behind {@link RemoteFiles}; what is left is the one decision
 * that is the same for SFTP and FTPS and different from local: <strong>the bytes come down to disk
 * before the import reads them</strong>. Both remote components defaulted to loading the whole file
 * into memory first, which is why the endpoint asked for a {@code localWorkDirectory}; downloading
 * here keeps that promise for the same reason — the spool that follows is a disk-to-disk copy, a
 * large file never materialises in heap, and the remote file is still there to be retried or moved.
 */
final class RemotePollSource implements PollSource {

    private final RemoteFiles files;
    private final Path workDirectory;

    RemotePollSource(RemoteFiles files, Path workDirectory) {
        this.files = files;
        this.workDirectory = workDirectory;
    }

    @Override
    public List<PolledFile> list() throws IOException {
        List<PolledFile> found = files.list();
        found.sort(Comparator.comparing(PolledFile::name));
        return found;
    }

    @Override
    public Optional<PolledFile> stat(String name) throws IOException {
        return files.stat(name);
    }

    /**
     * Downloads the file to the job's work directory.
     *
     * <p>The name is the remote server's, so the target is confined rather than resolved: the poll
     * loop already refuses a listed name that is not a plain one, and this is the same rule at the
     * mechanism, so the class is safe whichever caller drives it.
     */
    @Override
    public Fetched fetch(PolledFile file) throws IOException {
        Files.createDirectories(workDirectory);
        Path target = io.tesseraql.core.files.ConfinedPath.under(workDirectory)
                .resolve(file.name())
                .orElseThrow(() -> new IOException("Polled file name '" + file.name()
                        + "' escapes the work directory"));
        files.download(file.name(), target);
        return new Fetched(target, true);
    }

    @Override
    public void archive(PolledFile file, String subDirectory) throws IOException {
        files.archive(file.name(), subDirectory);
    }

    @Override
    public void close() {
        files.close();
    }
}
