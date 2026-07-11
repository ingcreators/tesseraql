package io.tesseraql.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Optional;

/**
 * The first-login hand-off marker a running {@code serve --embedded-db} leaves at
 * {@code <appHome>/work/embedded-db.jdbc}: one line, the embedded PostgreSQL's JDBC URL. The
 * embedded instance binds a random port by default, so without the marker a second terminal
 * (e.g. {@code identity-schema --app .}) would have to hand-copy the URL {@code serve} printed.
 * {@code work/} is the app's runtime scratch directory (app-layout.md), never committed. The file
 * is overwritten on each start and best-effort deleted on graceful shutdown — a stale marker after
 * a crash is harmless because {@link #pick} only honours a URL that still answers a connection.
 */
final class EmbeddedDbMarker {

    /** The marker file, relative to the app home. */
    static final String RELATIVE_PATH = "work/embedded-db.jdbc";

    /** How {@link #pick} checks whether a candidate datasource answers a trivial connection. */
    @FunctionalInterface
    interface ConnectionProbe {
        boolean reachable(String jdbcUrl, String username, String password);
    }

    private EmbeddedDbMarker() {
    }

    /**
     * Writes (or overwrites) the marker for {@code appHome}. Best-effort: the marker is a
     * convenience hand-off, so a write failure warns on stderr rather than failing {@code serve}.
     */
    static void write(Path appHome, String jdbcUrl) {
        Path marker = appHome.resolve(RELATIVE_PATH);
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, jdbcUrl + System.lineSeparator());
        } catch (IOException ex) {
            System.err.println("Warning: could not write " + marker + ": " + ex.getMessage());
        }
    }

    /** Best-effort removal of the marker on graceful shutdown (a leftover is tolerated by {@link #pick}). */
    static void delete(Path appHome) {
        try {
            Files.deleteIfExists(appHome.resolve(RELATIVE_PATH));
        } catch (IOException ex) {
            // Best-effort by contract: a stale marker fails the freshness probe on the next read.
        }
    }

    /** The marker's JDBC URL, or empty when the file is missing or blank. */
    static Optional<String> read(Path appHome) {
        Path marker = appHome.resolve(RELATIVE_PATH);
        if (!Files.isRegularFile(marker)) {
            return Optional.empty();
        }
        try {
            String url = Files.readString(marker).trim();
            return url.isEmpty() ? Optional.empty() : Optional.of(url);
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    /**
     * Decides whether the marker's URL should back an {@code --app}-resolved command. The
     * precedence is: an explicit {@code --jdbc-url} (the caller never gets here), then the app's
     * configured main datasource when it resolves <em>and</em> answers a connection, then the
     * marker — and only when its own URL still answers, so a stale file left by a crashed
     * {@code serve} is ignored. Without a marker nothing is probed and the caller's existing
     * resolution runs unchanged. {@code configUrl} is {@code null} when the config declares no
     * main URL or its placeholders do not resolve.
     */
    static Optional<String> pick(Path appHome, String configUrl, String configUsername,
            String configPassword, ConnectionProbe probe) {
        Optional<String> marker = read(appHome);
        if (marker.isEmpty()) {
            return Optional.empty();
        }
        if (configUrl != null && probe.reachable(configUrl, configUsername, configPassword)) {
            return Optional.empty();
        }
        return probe.reachable(marker.get(), null, null) ? marker : Optional.empty();
    }

    /**
     * The default {@link ConnectionProbe}: a plain {@link DriverManager} connection attempt with a
     * short login timeout, so an unreachable database answers quickly instead of hanging the CLI.
     */
    static boolean reachable(String jdbcUrl, String username, String password) {
        int previousTimeout = DriverManager.getLoginTimeout();
        DriverManager.setLoginTimeout(5);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            return connection.isValid(5);
        } catch (Exception ex) {
            return false;
        } finally {
            DriverManager.setLoginTimeout(previousTimeout);
        }
    }
}
