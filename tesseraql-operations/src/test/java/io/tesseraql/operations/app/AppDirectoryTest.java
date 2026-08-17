package io.tesseraql.operations.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a directory holds, and the refusals when the flag disagrees (docs/cli-surface.md
 * Decisions 1–3).
 *
 * <p>The case worth the most here is {@link #anApplicationIsNeverScannedForChildren}: every real
 * application home unpacks the five bundled framework surfaces into {@code work/apps/}, so a
 * resolver that looked inside an application would offer them as installable applications.
 */
class AppDirectoryTest {

    @Test
    void aDirectoryWithConfigOrWebIsAnApplication(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("config"));

        assertThat(AppDirectory.resolve(dir).shape()).isEqualTo(AppDirectory.Shape.APPLICATION);
        assertThat(AppDirectory.application(dir, "tesseraql package"))
                .isEqualTo(dir.toAbsolutePath().normalize());

        Path webOnly = Files.createDirectories(dir.resolve("other/web")).getParent();
        assertThat(AppDirectory.resolve(webOnly).shape())
                .isEqualTo(AppDirectory.Shape.APPLICATION);
    }

    @Test
    void aCatalogueIsAnInstallRoot(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("orders/1.2.0/config"));
        Files.writeString(dir.resolve("catalog.json"), """
                [{"id":"orders","version":"1.2.0","path":"orders/1.2.0","entitledTenants":[]}]
                """);

        AppDirectory.Resolved resolved = AppDirectory.resolve(dir);
        assertThat(resolved.shape()).isEqualTo(AppDirectory.Shape.INSTALL_ROOT);
        assertThat(resolved.applications())
                .containsExactly(dir.toAbsolutePath().normalize().resolve("orders/1.2.0"));
    }

    @Test
    void aFolderOfApplicationHomesIsAWorkspace(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("billing/config"));
        Files.createDirectories(dir.resolve("orders/web"));
        Files.createDirectories(dir.resolve("notes"));

        AppDirectory.Resolved resolved = AppDirectory.resolve(dir);
        assertThat(resolved.shape()).isEqualTo(AppDirectory.Shape.WORKSPACE);
        assertThat(resolved.applications())
                .extracting(path -> path.getFileName().toString())
                .containsExactly("billing", "orders");
        assertThat(AppDirectory.suite(dir)).hasSize(2);
    }

    /**
     * The trap the rule order exists for: an application's own {@code work/apps/} holds the five
     * bundled framework surfaces, each of which is an application home.
     */
    @Test
    void anApplicationIsNeverScannedForChildren(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("config"));
        for (String bundled : List.of("account", "auth-ui", "iam-admin", "ops-console", "studio")) {
            Files.createDirectories(dir.resolve("work/apps").resolve(bundled).resolve("config"));
        }

        AppDirectory.Resolved resolved = AppDirectory.resolve(dir);
        assertThat(resolved.shape()).isEqualTo(AppDirectory.Shape.APPLICATION);
        assertThat(resolved.applications()).containsExactly(dir.toAbsolutePath().normalize());
    }

    /** And the scan is one level: two levels down is not a workspace member either. */
    @Test
    void theScanDoesNotRecurse(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("nested/deeper/config"));

        assertThat(AppDirectory.resolve(dir).shape()).isEqualTo(AppDirectory.Shape.NOTHING);
    }

    @Test
    void appOnAMultiApplicationDirectoryListsTheCommandsThatWouldWork(@TempDir Path dir)
            throws IOException {
        Files.createDirectories(dir.resolve("billing/config"));
        Files.createDirectories(dir.resolve("orders/config"));

        assertThatThrownBy(() -> AppDirectory.application(dir, "tesseraql package"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("it holds 2")
                .hasMessageContaining("tesseraql package --app")
                .hasMessageContaining("billing")
                .hasMessageContaining("orders");
    }

    @Test
    void suiteOnASingleApplicationPointsAtTheOtherFlag(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("config"));

        assertThatThrownBy(() -> AppDirectory.suite(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("one application")
                .hasMessageContaining("--app");
    }

    @Test
    void anEmptyDirectoryNamesAllThreeShapes(@TempDir Path dir) {
        assertThatThrownBy(() -> AppDirectory.suite(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("config/ or web/")
                .hasMessageContaining("catalog.json");
        assertThatThrownBy(() -> AppDirectory.application(dir, "tesseraql package"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("holds no application");
    }

    /**
     * A workspace hosts exactly like an install root: entries shaped the same, {@code path}
     * relative to the root either way, so a host needs no branch for where they came from.
     */
    @Test
    void aWorkspaceSynthesisesCatalogueEntriesFromEachApplication(@TempDir Path dir)
            throws IOException {
        writeApplication(dir.resolve("orders"), "orders", "2.1.0");
        writeApplication(dir.resolve("billing"), "billing", null);

        List<InstalledApp> applications = AppDirectory.applications(AppDirectory.resolve(dir));

        assertThat(applications).extracting(InstalledApp::id).containsExactly("billing", "orders");
        assertThat(applications).extracting(InstalledApp::version)
                .containsExactly("0.0.0", "2.1.0");
        // Nothing was installed for anyone, so nothing is entitled.
        assertThat(applications).allSatisfy(app -> assertThat(app.entitledTenants()).isEmpty());
        Path root = dir.toAbsolutePath().normalize();
        assertThat(applications)
                .allSatisfy(app -> assertThat(root.resolve(app.path()).normalize()).exists());
    }

    /** A single application resolves against itself — an empty relative path is still the root. */
    @Test
    void oneApplicationIsAOneMemberSuite(@TempDir Path dir) throws IOException {
        writeApplication(dir, "orders", "1.0.0");

        List<InstalledApp> applications = AppDirectory.applications(AppDirectory.resolve(dir));

        assertThat(applications).singleElement()
                .satisfies(app -> assertThat(app.id()).isEqualTo("orders"));
        Path root = dir.toAbsolutePath().normalize();
        assertThat(root.resolve(applications.get(0).path()).normalize()).isEqualTo(root);
    }

    private static void writeApplication(Path home, String name, String version)
            throws IOException {
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: %s
                """.formatted(name)
                + (version == null ? "" : "    version: " + version + "\n"));
    }

    /**
     * A directory that IS one application answers at the origin root — the single-application
     * shape, with no second mechanism for it (docs/stack-architecture.md Decision 12).
     */
    @Test
    void oneApplicationTakesTheOriginRootAndAWorkspaceMemberTakesItsPrefix(@TempDir Path dir)
            throws IOException {
        writeApplication(dir.resolve("solo"), "solo", null);

        assertThat(AppDirectory.applications(AppDirectory.resolve(dir.resolve("solo"))))
                .singleElement()
                .satisfies(app -> assertThat(app.basePath())
                        .as("the origin root, normalised to the empty prefix").isEmpty());

        assertThat(AppDirectory.applications(AppDirectory.resolve(dir)))
                .singleElement()
                .satisfies(app -> assertThat(app.basePath()).isEqualTo("/apps/solo"));
    }
}
