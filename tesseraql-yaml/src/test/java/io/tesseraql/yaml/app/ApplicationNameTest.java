package io.tesseraql.yaml.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The application's name is required, because it is an identity rather than a label.
 *
 * <p>It used to default to the literal {@code app}, which made the value required to deploy —
 * {@code AppInstaller} refuses a package without it, and a suite addresses members by it — and
 * optional to run. The default was safe only because installation's requirement kept unnamed
 * applications to one at a time; the things it scopes are not ones a shared constant is safe in.
 */
class ApplicationNameTest {

    @Test
    void theDeclaredNameIsTheName() {
        assertThat(ApplicationName.of(config(Map.of(
                "tesseraql", Map.of("app", Map.of("name", "helpdesk"))))))
                .isEqualTo("helpdesk");
    }

    @Test
    void anAbsentNameIsRefused() {
        assertThatThrownBy(() -> ApplicationName.of(config(Map.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("tesseraql.app.name");
    }

    /** Blank is absent: it would otherwise produce an empty identity nothing could be scoped to. */
    @Test
    void aBlankNameIsRefused() {
        assertThatThrownBy(() -> ApplicationName.of(config(Map.of(
                "tesseraql", Map.of("app", Map.of("name", "   "))))))
                .isInstanceOf(TqlException.class);
    }

    private static AppConfig config(Map<String, Object> root) {
        return new AppConfig(root, name -> null);
    }
}
