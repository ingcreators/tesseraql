package io.tesseraql.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A polled directory on this machine (docs/camel-removal.md slice 1).
 *
 * <p>The directory is anchored under one of {@code tesseraql.connectors.poll.allowedPaths} by
 * {@link io.tesseraql.yaml.connectors.FileConnectors#requireAllowedPath}, deny by default — the
 * local counterpart of {@code allowedHosts}, and not a formality: a poll source <em>moves</em>
 * what it reads, so an unanchored path relocates a live directory's contents.
 */
final class LocalPollSource implements PollSource {

    private final Path directory;

    LocalPollSource(Path directory) {
        this.directory = directory;
    }

    /** The anchored directory, so the wiring rule that produced it can be asserted. */
    Path directory() {
        return directory;
    }

    @Override
    public List<PolledFile> list() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<PolledFile> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                describe(entry).ifPresent(files::add);
            }
        }
        files.sort(Comparator.comparing(PolledFile::name));
        return files;
    }

    @Override
    public Optional<PolledFile> stat(String name) {
        return describe(directory.resolve(name));
    }

    /** The polled file itself: the import spools from disk, so there is nothing to copy first. */
    @Override
    public Fetched fetch(PolledFile file) {
        return new Fetched(directory.resolve(file.name()), false);
    }

    @Override
    public void archive(PolledFile file, String subDirectory) throws IOException {
        Path target = directory.resolve(subDirectory).resolve(file.name());
        Files.createDirectories(target.getParent());
        Files.move(directory.resolve(file.name()), target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void close() {
        // Nothing to release: a local directory is not a connection.
    }

    /**
     * The file's fingerprint, or empty when reading it failed.
     *
     * <p>Empty rather than an exception because a poll races with whoever is writing: a file
     * listed a moment ago and gone now is an ordinary outcome, not a failed cycle.
     */
    private static Optional<PolledFile> describe(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            return Optional.of(new PolledFile(path.getFileName().toString(), Files.size(path),
                    Files.getLastModifiedTime(path).toMillis()));
        } catch (IOException gone) {
            return Optional.empty();
        }
    }
}
