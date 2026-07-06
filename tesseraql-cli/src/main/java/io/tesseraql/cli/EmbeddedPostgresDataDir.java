package io.tesseraql.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * The metadata TesseraQL keeps alongside a persistent {@code --embedded-db} data directory. The
 * on-disk PostgreSQL format is tied to the server's major version, so a directory initialized by one
 * major cannot be opened by another (see {@code PG_VERSION}). To keep a persistent dev database from
 * breaking when the CLI's default binary version is bumped, TesseraQL pins each directory to the exact
 * zonky binaries version that last ran it — recorded in a small {@value #MARKER} marker written next
 * to the data files — and re-resolves that version on subsequent starts regardless of the default.
 */
final class EmbeddedPostgresDataDir {

    /** The marker file, written at the root of a persistent data directory. */
    static final String MARKER = "tesseraql-embedded.properties";

    /** PostgreSQL's own on-disk major-version stamp at the root of an initialized data directory. */
    static final String PG_VERSION = "PG_VERSION";

    private static final String VERSION_KEY = "binariesVersion";

    private EmbeddedPostgresDataDir() {
    }

    /**
     * The zonky binaries version this data directory is pinned to, if it carries a TesseraQL marker.
     * Empty for a fresh directory, an ephemeral run ({@code dataDir} null), or a directory created
     * before this feature (a legacy directory carries {@code PG_VERSION} but no marker).
     */
    static Optional<String> pinnedVersion(Path dataDir) {
        if (dataDir == null) {
            return Optional.empty();
        }
        Path marker = dataDir.resolve(MARKER);
        if (!Files.isRegularFile(marker)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(marker)) {
            properties.load(in);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read " + marker, ex);
        }
        String version = properties.getProperty(VERSION_KEY);
        return version == null || version.isBlank()
                ? Optional.empty()
                : Optional.of(version.trim());
    }

    /**
     * Records {@code version} as the pin for {@code dataDir}, overwriting any previous value. Called
     * after a successful start so a directory only ever carries a version that actually opened it.
     * A no-op for an ephemeral run ({@code dataDir} null).
     */
    static void writePinnedVersion(Path dataDir, String version) {
        if (dataDir == null) {
            return;
        }
        Properties properties = new Properties();
        properties.setProperty(VERSION_KEY, version);
        Path marker = dataDir.resolve(MARKER);
        try (OutputStream out = Files.newOutputStream(marker)) {
            properties.store(out, "TesseraQL embedded PostgreSQL data directory — do not edit.");
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to write " + marker, ex);
        }
    }

    /** True when {@code dataDir} holds an already-initialized PostgreSQL cluster (has {@code PG_VERSION}). */
    static boolean isInitialized(Path dataDir) {
        return dataDir != null && Files.isRegularFile(dataDir.resolve(PG_VERSION));
    }

    /**
     * The PostgreSQL major version stamped in the directory's {@code PG_VERSION} file (e.g. {@code 17}),
     * or empty when the directory is uninitialized. This is the on-disk format version and is what
     * determines whether a given server binary can open the directory.
     */
    static Optional<String> onDiskMajor(Path dataDir) {
        if (!isInitialized(dataDir)) {
            return Optional.empty();
        }
        Path stamp = dataDir.resolve(PG_VERSION);
        try {
            String major = Files.readString(stamp).trim();
            return major.isEmpty() ? Optional.empty() : Optional.of(major);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read " + stamp, ex);
        }
    }

    /**
     * The major component of a zonky binaries version — the part before the first dot (PostgreSQL 10+
     * versions its on-disk format by a single major number). {@code "17.10.0"} maps to {@code "17"}.
     */
    static String majorOf(String binariesVersion) {
        int dot = binariesVersion.indexOf('.');
        return dot < 0 ? binariesVersion : binariesVersion.substring(0, dot);
    }
}
