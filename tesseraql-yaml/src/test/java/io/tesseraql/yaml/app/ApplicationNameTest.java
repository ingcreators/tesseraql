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

    /**
     * The name is the application's address (docs/stack-architecture.md Decision 25), so it must
     * be one safe path segment: no slash, no leading underscore (the framework's fence), no
     * leading dot. It is also an atom position: {@code tql.<family>.<verb>.<name>} parses by
     * splitting on dots, so a dotted name is refused, and {@code tql} — the framework's mark — is
     * reserved outright (docs/stack-shells.md structural decision 1).
     */
    @Test
    void aNameThatCannotBeAnAddressSegmentIsRefused() {
        for (String unsafe : new String[]{"a/b", "_admin", ".hidden", "assets", "orders.eu",
                "v1.2", "tql"}) {
            assertThatThrownBy(() -> ApplicationName.of(config(Map.of(
                    "tesseraql", Map.of("app", Map.of("name", unsafe))))))
                    .as(unsafe)
                    .isInstanceOf(TqlException.class)
                    .hasMessageContaining("TQL-YAML-1405");
        }
    }

    /**
     * Segment safety is not the scaffolder's ASCII pattern: non-ASCII names are legal — the
     * migration history guard measures them in UTF-8 bytes for exactly that reason — and an inner
     * underscore fences nothing. The atom families ({@code ops}, {@code studio}) stay legal names
     * too: the framework marks its own space with {@code tql.} rather than enumerating words users
     * may not have.
     */
    @Test
    void segmentSafetyIsNotAnAsciiPattern() {
        // "assets-portal" beside the reserved "assets": the reservation is one word, not a stem.
        for (String legal : new String[]{"受注管理", "my_app", "ops", "studio", "assets-portal"}) {
            assertThat(ApplicationName.of(config(Map.of(
                    "tesseraql", Map.of("app", Map.of("name", legal))))))
                    .as(legal)
                    .isEqualTo(legal);
        }
    }

    private static AppConfig config(Map<String, Object> root) {
        return new AppConfig(root, name -> null);
    }
}
