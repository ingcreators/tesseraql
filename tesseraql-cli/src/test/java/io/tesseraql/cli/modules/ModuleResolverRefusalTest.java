package io.tesseraql.cli.modules;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.cli.UsageRefusal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A module that cannot be resolved is a shaped refusal (docs/codec-discovery.md decision 5):
 * one line naming the coordinates, the resolver's reason and where versions come from, thrown
 * as the CLI's usage refusal so {@code dev}, {@code host}, {@code lint}, {@code job run} and
 * {@code modules resolve} exit 2 before any work — where the resolver's own exception used to
 * escape as an eighteen-frame stack trace with no code.
 */
class ModuleResolverRefusalTest {

    @Test
    void anUnresolvableModuleIsRefusedInOneLineNamingWhatAndWhere() {
        // Offline against the local repository: the artifact is not there, and the BOM is not
        // consulted for a pinned version, so the resolver's answer is the one this shapes.
        ModuleResolver resolver = new ModuleResolver("io.tesseraql:tesseraql-bom:0.0.0-none", true);

        assertThatThrownBy(() -> resolver.resolve(
                List.of(ModuleCoordinate.parse("io.example:no-such-module:1.0.0"))))
                .isInstanceOf(UsageRefusal.class)
                .hasMessageContaining("TQL-APP-4221")
                .hasMessageContaining("io.example:no-such-module:1.0.0")
                .hasMessageContaining("offline")
                .hasMessageContaining("io.tesseraql:tesseraql-bom:0.0.0-none")
                .hasMessageContaining("--repo");
    }
}
