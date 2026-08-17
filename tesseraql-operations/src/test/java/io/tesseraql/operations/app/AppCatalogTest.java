package io.tesseraql.operations.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The catalogue is keyed by the application's name, and the name is the stack's contract
 * (docs/stack-architecture.md Decision 23): what the deployment addresses, entitles and grants
 * against. So a second application does not take an installed name by being installed — the
 * silent {@code apps.put} replacement was how a stack lost an application without anyone
 * deleting it.
 */
class AppCatalogTest {

    @Test
    void registeringAnInstalledNameIsRefusedNotReplaced(@TempDir Path root) {
        new AppCatalog(root).register(entry("orders", "1.0.0", "orders/1.0.0"));

        assertThatThrownBy(() -> new AppCatalog(root)
                .register(entry("orders", "0.9.0", "elsewhere/orders")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-APP-4213")
                .hasMessageContaining("orders")
                .hasMessageContaining("1.0.0");

        assertThat(new AppCatalog(root).find("orders").orElseThrow().path())
                .as("the installed application is still the catalogued one")
                .isEqualTo("orders/1.0.0");
    }

    /** Re-registering the identical entry stays idempotent, so a re-install is not an error. */
    @Test
    void registeringTheIdenticalEntryIsANoOp(@TempDir Path root) {
        InstalledApp app = entry("orders", "1.0.0", "orders/1.0.0");
        new AppCatalog(root).register(app);

        assertThatCode(() -> new AppCatalog(root).register(app)).doesNotThrowAnyException();
        assertThat(new AppCatalog(root).list()).hasSize(1);
    }

    /** Replacement is an explicit act — the upgrade lifecycle's, after its preflight. */
    @Test
    void replaceMovesTheNameToTheNewVersion(@TempDir Path root) {
        new AppCatalog(root).register(entry("orders", "1.0.0", "orders/1.0.0"));

        new AppCatalog(root).replace(entry("orders", "2.0.0", "orders/2.0.0"));

        assertThat(new AppCatalog(root).find("orders").orElseThrow().version())
                .isEqualTo("2.0.0");
    }

    /**
     * The identity field is {@code "name"}; a catalogue written before the rename spells it
     * {@code "id"} and is refused with a message naming the change, never read as an entry with
     * no identity (pre-1.0 format change).
     */
    @Test
    void aPreRenameCatalogueIsRefusedWithTheRenameNamed(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("catalog.json"), """
                [{"id":"orders","version":"1.0.0","path":"orders/1.0.0","entitledTenants":[]}]
                """);

        assertThatThrownBy(() -> new AppCatalog(root))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Failed to read catalog")
                .hasMessageContaining("\"id\"");
    }

    private static InstalledApp entry(String name, String version, String path) {
        return new InstalledApp(name, version, path, List.of());
    }
}
