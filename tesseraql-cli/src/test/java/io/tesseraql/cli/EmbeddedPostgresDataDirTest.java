package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit coverage for the persistent {@code --embedded-db} version marker (no PostgreSQL needed). */
class EmbeddedPostgresDataDirTest {

    @Test
    void freshDirectoryHasNoPin(@TempDir Path dir) {
        assertThat(EmbeddedPostgresDataDir.pinnedVersion(dir)).isEmpty();
        assertThat(EmbeddedPostgresDataDir.isInitialized(dir)).isFalse();
    }

    @Test
    void ephemeralRunNeverPins() {
        assertThat(EmbeddedPostgresDataDir.pinnedVersion(null)).isEmpty();
        // Writing to a null (ephemeral) data directory is a no-op, not a crash.
        EmbeddedPostgresDataDir.writePinnedVersion(null, "17.10.0");
    }

    @Test
    void writtenPinRoundTrips(@TempDir Path dir) {
        EmbeddedPostgresDataDir.writePinnedVersion(dir, "17.10.0");

        assertThat(EmbeddedPostgresDataDir.pinnedVersion(dir)).contains("17.10.0");
        assertThat(dir.resolve(EmbeddedPostgresDataDir.MARKER)).exists();
    }

    @Test
    void rewritingOverwritesThePreviousPin(@TempDir Path dir) {
        EmbeddedPostgresDataDir.writePinnedVersion(dir, "17.10.0");
        EmbeddedPostgresDataDir.writePinnedVersion(dir, "17.14.0");

        assertThat(EmbeddedPostgresDataDir.pinnedVersion(dir)).contains("17.14.0");
    }

    @Test
    void isInitializedTracksThePgVersionStamp(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(EmbeddedPostgresDataDir.PG_VERSION), "17\n");

        assertThat(EmbeddedPostgresDataDir.isInitialized(dir)).isTrue();
        // A legacy directory (PG_VERSION but no marker) reports no pin.
        assertThat(EmbeddedPostgresDataDir.pinnedVersion(dir)).isEmpty();
    }
}
