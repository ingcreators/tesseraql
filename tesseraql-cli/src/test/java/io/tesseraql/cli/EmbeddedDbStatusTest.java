package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit coverage for {@code embedded-db info} classification and rendering (no PostgreSQL needed). */
class EmbeddedDbStatusTest {

    @Test
    void uninitializedDirectoryReportsFreshCreate(@TempDir Path dir) {
        EmbeddedDbStatus.Report report = EmbeddedDbStatus.of(dir, "17.10.0");

        assertThat(report.state()).isEqualTo(EmbeddedDbStatus.State.UNINITIALIZED);
        assertThat(report.render()).contains("not initialized").contains("17.10.0");
    }

    @Test
    void sameMajorAsDefaultIsUpToDate(@TempDir Path dir) throws Exception {
        initializedDir(dir, "17");
        EmbeddedPostgresDataDir.writePinnedVersion(dir, "17.10.0");

        EmbeddedDbStatus.Report report = EmbeddedDbStatus.of(dir, "17.14.0");

        assertThat(report.state()).isEqualTo(EmbeddedDbStatus.State.UP_TO_DATE);
        assertThat(report.render()).contains("up to date").doesNotContain("pg_dumpall");
    }

    @Test
    void olderMajorThanDefaultOffersTheUpgradeProcedure(@TempDir Path dir) throws Exception {
        initializedDir(dir, "17");
        EmbeddedPostgresDataDir.writePinnedVersion(dir, "17.10.0");

        EmbeddedDbStatus.Report report = EmbeddedDbStatus.of(dir, "18.4.0");

        assertThat(report.state()).isEqualTo(EmbeddedDbStatus.State.UPGRADE_AVAILABLE);
        String rendered = report.render();
        assertThat(rendered)
                .contains("upgrade is available")
                .contains("pg_dumpall")
                .contains("psql")
                // The dump step uses the directory's own pinned version, the restore the new one.
                .contains("--embedded-db-version 17.10.0")
                .contains("--embedded-db-version 18.4.0")
                .contains(dir + ".bak");
    }

    @Test
    void legacyUnpinnedDirectoryIsFlagged(@TempDir Path dir) throws Exception {
        initializedDir(dir, "17");
        // No marker written — a directory from before version pinning existed.

        EmbeddedDbStatus.Report report = EmbeddedDbStatus.of(dir, "17.10.0");

        assertThat(report.legacyUnpinned()).isTrue();
        assertThat(report.state()).isEqualTo(EmbeddedDbStatus.State.UP_TO_DATE);
        assertThat(report.render()).contains("predates version pinning");
    }

    private static void initializedDir(Path dir, String major) throws Exception {
        Files.writeString(dir.resolve(EmbeddedPostgresDataDir.PG_VERSION), major + "\n");
    }
}
