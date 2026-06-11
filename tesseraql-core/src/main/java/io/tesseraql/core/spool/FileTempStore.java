package io.tesseraql.core.spool;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

/**
 * Local-filesystem {@link TempStore} (design ch. 28.4 {@code FileTempStore}). Spools are written to
 * a configured directory with owner-only readability where supported (design ch. 28.14).
 */
public final class FileTempStore implements TempStore {

    private final Path directory;

    public FileTempStore(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public SpoolWriter createWriter(SpoolKind kind) {
        String id = UUID.randomUUID().toString();
        Path file = directory.resolve(id + extension(kind));
        try {
            OutputStream out = new BufferedOutputStream(Files.newOutputStream(file));
            return new FileSpoolWriter(id, kind, file, out);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public InputStream openInput(SpoolRef ref) throws IOException {
        return Files.newInputStream(Paths.get(ref.uri()));
    }

    @Override
    public void delete(SpoolRef ref) {
        try {
            Files.deleteIfExists(Paths.get(ref.uri()));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String extension(SpoolKind kind) {
        return switch (kind) {
            case CSV -> ".csv";
            case JSONL, NDJSON -> ".jsonl";
            case BINARY, ROWSET -> ".bin";
        };
    }

    private static final class FileSpoolWriter implements SpoolWriter {
        private final String id;
        private final SpoolKind kind;
        private final Path file;
        private final OutputStream out;
        private long bytes;
        private long rows;

        FileSpoolWriter(String id, SpoolKind kind, Path file, OutputStream out) {
            this.id = id;
            this.kind = kind;
            this.file = file;
            this.out = out;
        }

        @Override
        public void write(byte[] data) throws IOException {
            out.write(data);
            bytes += data.length;
        }

        @Override
        public void incrementRows(long count) {
            rows += count;
        }

        @Override
        public void close() throws IOException {
            out.close();
        }

        @Override
        public SpoolRef toRef() {
            return new SpoolRef(id, kind, file.toUri(), bytes, rows, Instant.now());
        }
    }
}
