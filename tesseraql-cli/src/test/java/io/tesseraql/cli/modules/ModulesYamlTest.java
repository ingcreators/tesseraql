package io.tesseraql.cli.modules;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.config.AppConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModulesYamlTest {

    @Test
    void createsTheModulesBlockWhenAbsent() {
        String yaml = "tesseraql:\n  app:\n    name: demo\n";
        String updated = ModulesYaml.addModule(yaml, "io.tesseraql:tesseraql-pdf");
        assertThat(updated).contains("  modules:\n    - io.tesseraql:tesseraql-pdf");
        assertThat(updated).contains("  app:");
    }

    @Test
    void appendsToTheExistingBlockAndIsIdempotent() {
        String yaml = "tesseraql:\n  modules:\n    - a:b\n  app:\n    name: demo\n";
        String updated = ModulesYaml.addModule(yaml, "c:d");
        assertThat(updated).contains("    - a:b\n    - c:d");
        // Re-adding the same coordinate changes nothing.
        assertThat(ModulesYaml.addModule(updated, "c:d")).isEqualTo(updated);
    }

    @Test
    void readsDeclaredModulesFromConfig() {
        AppConfig config = new AppConfig(
                Map.of("tesseraql", Map.of("modules", List.of("a:b", "c:d:1"))));
        assertThat(ModulesYaml.declared(config)).extracting(ModuleCoordinate::canonical)
                .containsExactly("a:b", "c:d:1");
    }

    @Test
    void isEmptyWhenNoModulesDeclared() {
        assertThat(ModulesYaml.declared(new AppConfig(Map.of()))).isEmpty();
    }
}
