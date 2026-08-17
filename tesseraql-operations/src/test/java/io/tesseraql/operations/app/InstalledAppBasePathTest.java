package io.tesseraql.operations.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An application's address is derived from its name, always — {@code /<name>}, not a declarable
 * field (docs/stack-architecture.md Decision 25).
 *
 * <p>{@code basePath()} is the one producer of an address, so an install, an upgrade and a
 * neighbour's absolute link can never disagree about where an application answers. What remained
 * of a declarable address was the vanity rename, and a renamed address breaks every neighbour's
 * links — so a catalogue that still declares one is refused loudly rather than quietly
 * re-addressed.
 */
class InstalledAppBasePathTest {

    @Test
    void theAddressIsTheName() {
        assertThat(new InstalledApp("orders", "1.0.0", "orders/1.0.0", List.of()).basePath())
                .isEqualTo("/orders");
    }

    /** Non-ASCII names are legal (TQL-YAML-1405 is segment safety), and they derive the same way. */
    @Test
    void aNonAsciiNameDerivesItsAddressTheSameWay() {
        assertThat(new InstalledApp("受注管理", "1.0.0", "orders/1.0.0", List.of()).basePath())
                .isEqualTo("/受注管理");
    }

    @Test
    void aCatalogueDeclaringABasePathIsRefused() {
        assertThatThrownBy(() -> new ObjectMapper().readValue("""
                {"name":"orders","version":"1.0.0","path":"orders/1.0.0",
                 "entitledTenants":[],"basePath":"/shop"}
                """, InstalledApp.class))
                .hasMessageContaining("addresses are not declarable")
                .hasMessageContaining("/shop");
    }

    /** The derived address never enters the JSON, so nothing on disk can drift from the name. */
    @Test
    void theAddressIsNotSerialised() throws Exception {
        String json = new ObjectMapper().writeValueAsString(
                new InstalledApp("orders", "1.0.0", "orders/1.0.0", List.of()));
        assertThat(json).doesNotContain("basePath");
    }
}
