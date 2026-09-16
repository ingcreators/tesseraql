package io.tesseraql.operations.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.version.SemanticVersion;
import io.tesseraql.operations.app.AppUpgrader.UpgradeReport;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppUpgraderTest {

    private static final SemanticVersion FRAMEWORK = SemanticVersion.parse("0.1.0");

    @Test
    void preflightRejectsIncompatibleFrameworkAndOlderVersion(@TempDir Path dir) throws Exception {
        Path installRoot = dir.resolve("apps");
        new AppInstaller().install(pkg(dir.resolve("v2.tqlapp"), "app", "2.0.0", "*"), installRoot);

        // Older than the installed 2.0.0.
        UpgradeReport older = new AppUpgrader().preflight(
                pkg(dir.resolve("v1.tqlapp"), "app", "1.0.0", "*"), installRoot, FRAMEWORK);
        assertThat(older.compatible()).isFalse();
        assertThat(older.messages()).anyMatch(m -> m.contains("not newer"));

        // Requires a framework the runtime does not satisfy.
        UpgradeReport incompatible = new AppUpgrader().preflight(
                pkg(dir.resolve("v3.tqlapp"), "app", "3.0.0", ">=1.0.0"), installRoot, FRAMEWORK);
        assertThat(incompatible.compatible()).isFalse();
        assertThat(incompatible.messages()).anyMatch(m -> m.contains("requires framework"));
    }

    /**
     * A direct upgrade writes a replace candidate and leaves the catalogue to the host
     * (docs/runtime-replace.md structural decision 2's addendum): the catalogue names the
     * serving version until a host applies the candidate, so a rollback of an applied candidate
     * targets the version that served, and a rollback of one never applied discards it.
     */
    @Test
    void upgradeWritesACandidateAndRollbackRestoresTheVersionThatServed(@TempDir Path dir)
            throws Exception {
        Path installRoot = dir.resolve("apps");
        new AppInstaller().install(pkg(dir.resolve("v1.tqlapp"), "app", "1.0.0", "*"), installRoot);

        AppUpgrader upgrader = new AppUpgrader();
        upgrader.upgrade(pkg(dir.resolve("v2.tqlapp"), "app", "2.0.0", ">=0.1.0"), installRoot,
                FRAMEWORK);
        assertThat(new AppCatalog(installRoot).find("app")).get()
                .extracting(InstalledApp::version).isEqualTo("1.0.0");
        assertThat(upgrader.pending("app", installRoot)).get()
                .extracting(InstalledApp::version).isEqualTo("2.0.0");
        assertThat(upgrader.canary("app", installRoot)).isEmpty();

        // The host applies it: the catalogue is its write.
        InstalledApp applied = upgrader.pending("app", installRoot).orElseThrow();
        new AppCatalog(installRoot).replace(applied);

        assertThat(upgrader.rollback("app", installRoot).version()).isEqualTo("1.0.0");
        assertThat(upgrader.pending("app", installRoot)).get()
                .extracting(InstalledApp::version).isEqualTo("1.0.0");
        // Still the host's move: nothing here touched the catalogue.
        assertThat(new AppCatalog(installRoot).find("app")).get()
                .extracting(InstalledApp::version).isEqualTo("2.0.0");
        new AppCatalog(installRoot).replace(upgrader.pending("app", installRoot).orElseThrow());
        assertThatThrownBy(() -> upgrader.rollback("app", installRoot))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("No previous version");
    }

    /**
     * The catalogue never names a version no host has started (docs/audit-low-leads.md,
     * XD-08b): two deploys nobody applied, then a rollback, leave the serving version and no
     * candidate — the second deploy was never {@code previous}, because {@code previous} is
     * always the catalogue's entry. It used to name the first deploy's version, which had never
     * served, and every later rollback landed on it.
     */
    @Test
    void rollbackOfACandidateNeverAppliedDiscardsItAndKeepsWhatServes(@TempDir Path dir)
            throws Exception {
        Path installRoot = dir.resolve("apps");
        new AppInstaller().install(pkg(dir.resolve("v1.tqlapp"), "app", "1.0.0", "*"), installRoot);

        AppUpgrader upgrader = new AppUpgrader();
        upgrader.upgrade(pkg(dir.resolve("v2.tqlapp"), "app", "1.0.1", "*"), installRoot,
                FRAMEWORK);
        upgrader.upgrade(pkg(dir.resolve("v3.tqlapp"), "app", "1.0.2", "*"), installRoot,
                FRAMEWORK);
        assertThat(upgrader.pending("app", installRoot)).get()
                .extracting(InstalledApp::version).isEqualTo("1.0.2");

        assertThat(upgrader.rollback("app", installRoot).version()).isEqualTo("1.0.0");
        assertThat(new AppCatalog(installRoot).find("app")).get()
                .extracting(InstalledApp::version).isEqualTo("1.0.0");
        assertThat(upgrader.pending("app", installRoot)).isEmpty();
        // And the preflight floor is the serving version: the never-applied 1.0.2 does not bar
        // a re-deploy of 1.0.1.
        assertThat(upgrader.preflight(pkg(dir.resolve("v2b.tqlapp"), "app", "1.0.1", "*"),
                installRoot, FRAMEWORK).compatible()).isTrue();
    }

    @Test
    void canaryStagesWithoutActivatingThenPromotesAsAReplaceCandidate(@TempDir Path dir)
            throws Exception {
        Path installRoot = dir.resolve("apps");
        new AppInstaller().install(pkg(dir.resolve("v1.tqlapp"), "app", "1.0.0", "*"), installRoot);

        AppUpgrader upgrader = new AppUpgrader();
        upgrader.upgrade(pkg(dir.resolve("v2.tqlapp"), "app", "2.0.0", "*"), installRoot,
                FRAMEWORK, true);
        // Active version is unchanged while the candidate is staged.
        assertThat(new AppCatalog(installRoot).find("app")).get()
                .extracting(InstalledApp::version).isEqualTo("1.0.0");
        assertThat(upgrader.canary("app", installRoot)).get()
                .extracting(c -> c.candidate().version()).isEqualTo("2.0.0");
        assertThat(upgrader.pending("app", installRoot)).isEmpty();
        // The candidate's files are on disk for side-by-side hosting.
        assertThat(installRoot.resolve("app/2.0.0")).isDirectory();

        // A promote is the staged canary rewritten as the replace candidate; the host that runs
        // it promotes it and moves the catalogue.
        upgrader.promote("app", installRoot);
        assertThat(upgrader.canary("app", installRoot)).isEmpty();
        assertThat(upgrader.pending("app", installRoot)).get()
                .extracting(InstalledApp::version).isEqualTo("2.0.0");
        assertThat(new AppCatalog(installRoot).find("app")).get()
                .extracting(InstalledApp::version).isEqualTo("1.0.0");
    }

    /** A state file written before the mode existed is a canary — what it always meant. */
    @Test
    void aStateFileWithoutAModeIsACanary(@TempDir Path dir) throws Exception {
        Path installRoot = dir.resolve("apps");
        new AppInstaller().install(pkg(dir.resolve("v1.tqlapp"), "app", "1.0.0", "*"), installRoot);
        Files.createDirectories(installRoot.resolve(".upgrade"));
        Files.writeString(installRoot.resolve(".upgrade/app.json"), """
                {"previous":null,"candidate":{"name":"app","version":"2.0.0",\
                "path":"app/2.0.0","entitledTenants":[]},"canaryWeight":25}""");

        AppUpgrader upgrader = new AppUpgrader();
        assertThat(upgrader.canary("app", installRoot)).get()
                .extracting(AppUpgrader.CanaryStatus::weightPercent).isEqualTo(25);
        assertThat(upgrader.pending("app", installRoot)).isEmpty();
    }

    @Test
    void upgradeRejectsIncompatiblePackage(@TempDir Path dir) throws Exception {
        Path installRoot = dir.resolve("apps");
        new AppInstaller().install(pkg(dir.resolve("v1.tqlapp"), "app", "1.0.0", "*"), installRoot);

        assertThatThrownBy(() -> new AppUpgrader().upgrade(
                pkg(dir.resolve("bad.tqlapp"), "app", "2.0.0", ">=9.0.0"), installRoot, FRAMEWORK))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("preflight failed");
        assertThat(new AppCatalog(installRoot).find("app")).get()
                .extracting(InstalledApp::version).isEqualTo("1.0.0");
    }

    private static Path pkg(Path output, String id, String version, String requiresFramework)
            throws Exception {
        String yaml = "tesseraql:\n  app:\n    name: " + id + "\n    version: " + version
                + "\n    requires:\n      framework: \"" + requiresFramework + "\"\n";
        Map<String, String> entries = Map.of("config/tesseraql.yml", yaml);
        try (OutputStream out = Files.newOutputStream(output);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output;
    }
}
