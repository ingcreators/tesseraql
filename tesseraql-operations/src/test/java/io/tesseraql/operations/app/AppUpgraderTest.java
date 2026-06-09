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

    @Test
    void upgradeThenRollbackRestoresPreviousVersion(@TempDir Path dir) throws Exception {
        Path installRoot = dir.resolve("apps");
        new AppInstaller().install(pkg(dir.resolve("v1.tqlapp"), "app", "1.0.0", "*"), installRoot);

        new AppUpgrader().upgrade(pkg(dir.resolve("v2.tqlapp"), "app", "2.0.0", ">=0.1.0"),
                installRoot, FRAMEWORK);
        assertThat(new AppCatalog(installRoot).find("app")).get()
                .extracting(InstalledApp::version).isEqualTo("2.0.0");

        new AppUpgrader().rollback("app", installRoot);
        assertThat(new AppCatalog(installRoot).find("app")).get()
                .extracting(InstalledApp::version).isEqualTo("1.0.0");
    }

    @Test
    void canaryStagesWithoutActivatingThenPromotes(@TempDir Path dir) throws Exception {
        Path installRoot = dir.resolve("apps");
        new AppInstaller().install(pkg(dir.resolve("v1.tqlapp"), "app", "1.0.0", "*"), installRoot);

        new AppUpgrader().upgrade(pkg(dir.resolve("v2.tqlapp"), "app", "2.0.0", "*"),
                installRoot, FRAMEWORK, true);
        // Active version is unchanged while the candidate is staged.
        assertThat(new AppCatalog(installRoot).find("app")).get()
                .extracting(InstalledApp::version).isEqualTo("1.0.0");
        // The candidate's files are on disk for side-by-side hosting.
        assertThat(installRoot.resolve("app/2.0.0")).isDirectory();

        new AppUpgrader().promote("app", installRoot);
        assertThat(new AppCatalog(installRoot).find("app")).get()
                .extracting(InstalledApp::version).isEqualTo("2.0.0");
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
