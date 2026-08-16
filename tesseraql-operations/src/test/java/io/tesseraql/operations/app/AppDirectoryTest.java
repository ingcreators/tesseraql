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
}
