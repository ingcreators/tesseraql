package io.tesseraql.operations.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppInstallerTest {

    @Test
    void installsPackageAndRecordsInCatalog(@TempDir Path dir) throws Exception {
        Path pkg = pack(dir.resolve("user-admin.tqlapp"), Map.of(
                "config/tesseraql.yml", "tesseraql:\n  app:\n    name: user-admin\n    version: 1.2.0\n",
                "web/api/users/get.yml", "version: tesseraql/v1\nid: users.search\n"));
        Path installRoot = dir.resolve("apps");

        InstalledApp app = new AppInstaller().install(pkg, installRoot);

        assertThat(app.id()).isEqualTo("user-admin");
        assertThat(app.version()).isEqualTo("1.2.0");
        assertThat(installRoot.resolve(app.path()).resolve("web/api/users/get.yml")).exists();

        AppCatalog catalog = new AppCatalog(installRoot);
        assertThat(catalog.find("user-admin")).get()
                .extracting(InstalledApp::version).isEqualTo("1.2.0");
        assertThat(catalog.list()).hasSize(1);
    }

    @Test
    void verifiesPackageIntegrityBeforeInstall(@TempDir Path dir) throws Exception {
        Path pkg = pack(dir.resolve("user-admin.tqlapp"), Map.of(
                "config/tesseraql.yml", "tesseraql:\n  app:\n    name: user-admin\n    version: 1.2.0\n",
                "web/api/users/get.yml", "version: tesseraql/v1\nid: users.search\n"));
        Path installRoot = dir.resolve("apps");
        String good = io.tesseraql.core.util.Hashing.sha256(pkg);

        // The matching hash installs normally.
        assertThat(new AppInstaller().install(pkg, installRoot, good).id()).isEqualTo("user-admin");

        // A mismatched hash is rejected before extraction and nothing is catalogued.
        Path otherRoot = dir.resolve("apps2");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new AppInstaller().install(pkg, otherRoot, "deadbeef"))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("integrity check failed");
        assertThat(new AppCatalog(otherRoot).list()).isEmpty();
    }

    @Test
    void appliesConfigOverlayAndEntitlements(@TempDir Path dir) throws Exception {
        Path pkg = pack(dir.resolve("a.tqlapp"), Map.of(
                "config/tesseraql.yml", "tesseraql:\n  app:\n    name: shop\n    version: 2.0.0\n"));
        Path overlay = dir.resolve("overlay.yml");
        Files.writeString(overlay, "tenancy:\n  enabled: true\n");
        Path installRoot = dir.resolve("apps");

        InstalledApp app = new AppInstaller().install(pkg, installRoot, overlay, List.of("acme"));

        assertThat(installRoot.resolve(app.path()).resolve("config/overlay.yml")).exists();
        assertThat(app.isEntitled("acme")).isTrue();
        assertThat(app.isEntitled("globex")).isFalse();

        AppCatalog catalog = new AppCatalog(installRoot);
        assertThat(catalog.isEntitled("shop", "acme")).isTrue();
        assertThat(catalog.isEntitled("shop", "globex")).isFalse();
    }

    @Test
    void rejectsZipSlipEntries(@TempDir Path dir) throws Exception {
        Path pkg = pack(dir.resolve("evil.tqlapp"), Map.of(
                "../escape.txt", "pwned",
                "config/tesseraql.yml", "tesseraql:\n  app:\n    name: x\n"));

        assertThatThrownBy(() -> new AppInstaller().install(pkg, dir.resolve("apps")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("escapes install root");
        assertThat(dir.resolve("escape.txt")).doesNotExist();
    }

    @Test
    void reinstallReplacesCatalogEntry(@TempDir Path dir) throws Exception {
        Path installRoot = dir.resolve("apps");
        new AppInstaller().install(pack(dir.resolve("v1.tqlapp"), Map.of(
                "config/tesseraql.yml", "tesseraql:\n  app:\n    name: app\n    version: 1.0.0\n")),
                installRoot);
        new AppInstaller().install(pack(dir.resolve("v2.tqlapp"), Map.of(
                "config/tesseraql.yml", "tesseraql:\n  app:\n    name: app\n    version: 2.0.0\n")),
                installRoot);

        AppCatalog catalog = new AppCatalog(installRoot);
        assertThat(catalog.list()).hasSize(1);
        assertThat(catalog.find("app")).get().extracting(InstalledApp::version).isEqualTo("2.0.0");
    }

    private static Path pack(Path output, Map<String, String> entries) throws Exception {
        Map<String, String> ordered = new LinkedHashMap<>(entries);
        try (OutputStream out = Files.newOutputStream(output);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : ordered.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output;
    }
}
