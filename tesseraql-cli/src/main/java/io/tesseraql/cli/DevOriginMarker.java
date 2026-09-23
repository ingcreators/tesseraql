package io.tesseraql.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where a running {@code dev} answers, left at {@code <appHome>/work/dev.origin}: one line, the
 * gateway's origin ({@code http://localhost:<port>}); the application is under
 * {@code /<name>/} of it. A {@code dev --port 0} binds a free port, so an agent's session or a
 * second terminal that did not read the console has nowhere else to learn it
 * (docs/host-development.md decision 7).
 *
 * <p>The embedded-database marker's shape ({@link EmbeddedDbMarker}): written on start,
 * overwritten by the next, best-effort deleted on a graceful stop. A marker left by a crash names
 * a port nothing answers on, so a reader trusts it only if it answers.
 */
final class DevOriginMarker {

    private DevOriginMarker() {
    }

    /** The marker file under the app's resolved work home (docs/config-consumers.md). */
    static Path marker(Path appHome) {
        return io.tesseraql.yaml.config.WorkHome
                .resolve(appHome, io.tesseraql.yaml.manifest.ManifestLoader.configOnly(appHome))
                .resolve("dev.origin");
    }

    /**
     * Writes (or overwrites) the marker for {@code appHome}. Best-effort: a write failure warns on
     * stderr rather than failing {@code dev}, whose console still prints the address.
     */
    static void write(Path appHome, String origin) {
        Path marker = marker(appHome);
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, origin + System.lineSeparator());
        } catch (IOException ex) {
            System.err.println("Warning: could not write " + marker + ": " + ex.getMessage());
        }
    }

    /** Best-effort removal on a graceful stop; a leftover names a port nothing answers on. */
    static void delete(Path appHome) {
        try {
            Files.deleteIfExists(marker(appHome));
        } catch (IOException ex) {
            // Best-effort by contract: a stale marker answers nothing, which its reader checks.
        }
    }
}
