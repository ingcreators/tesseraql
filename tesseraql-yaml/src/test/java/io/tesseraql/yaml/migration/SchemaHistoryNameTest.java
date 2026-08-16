package io.tesseraql.yaml.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The history key every entry point resolves (docs/cli-surface.md Decision 6).
 *
 * <p>The bug this closes was not in any one of them: the runtime read
 * {@code tesseraql.app.name}, {@code tesseraql migrate} read the application's directory name and
 * the Maven goal read {@code ${project.artifactId}}, so migrating from the build wrote a history the
 * runtime ignored and re-ran everything under its own. There is one answer now, and it comes from
 * the application.
 */
class SchemaHistoryNameTest {

    @Test
    void theApplicationNameIsTheKey() {
        assertThat(SchemaHistoryName.of(config(Map.of(
                "tesseraql", Map.of("app", Map.of("name", "helpdesk"))))))
                .isEqualTo("helpdesk");
    }

    /** The escape hatch for an identifier limit, and the only thing that outranks the app name. */
    @Test
    void anExplicitHistoryNameWins() {
        assertThat(SchemaHistoryName.of(config(Map.of("tesseraql", Map.of(
                "app", Map.of("name", "a-very-long-application-name"),
                "migrations", Map.of("historyName", "hd"))))))
                .isEqualTo("hd");
    }

    /** Blank is not a choice: an empty key would silently become the {@code app} fallback anyway. */
    @Test
    void blankValuesFallThrough() {
        assertThat(SchemaHistoryName.of(config(Map.of("tesseraql", Map.of(
                "app", Map.of("name", "helpdesk"),
                "migrations", Map.of("historyName", "   "))))))
                .isEqualTo("helpdesk");
    }

    /** No third branch: an unnamed application is refused before it can reach a history table. */
    @Test
    void anApplicationDeclaringNeitherIsRefused() {
        assertThatThrownBy(() -> SchemaHistoryName.of(config(Map.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("tesseraql.app.name");
    }

    private static AppConfig config(Map<String, Object> root) {
        return new AppConfig(root, name -> null);
    }
}
