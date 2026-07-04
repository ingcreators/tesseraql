package io.tesseraql.yaml.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The declared preference groups (roadmap Phase 48 slice 5): parsing, the acceptance
 * rules the account surface enforces, and the TQL-YAML-1030..1033 failure modes lint
 * surfaces pre-deploy.
 */
class PreferencesSpecTest {

    private static Path app(Path dir, String yaml) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/preferences.yml"), yaml);
        return dir;
    }

    @Test
    void parsesFieldsAndAnAbsentFileDeclaresNothing(@TempDir Path dir) throws Exception {
        assertThat(PreferencesSpec.load(dir).isEmpty()).isTrue();

        PreferencesSpec spec = PreferencesSpec.load(app(dir, """
                preferences:
                  - { key: pageSize, label: app.pref.pageSize, type: choice,
                      options: ["10", "25"], default: "25" }
                  - { key: beta, type: boolean }
                  - { key: signature, type: text }
                """));
        assertThat(spec.fields()).hasSize(3);
        assertThat(spec.field("pageSize").options()).containsExactly("10", "25");
        assertThat(spec.field("beta").label()).isEqualTo("beta"); // label falls back to key
        assertThat(spec.field("nope")).isNull();
    }

    @Test
    void acceptanceFollowsTheDeclaredType(@TempDir Path dir) throws Exception {
        PreferencesSpec spec = PreferencesSpec.load(app(dir, """
                preferences:
                  - { key: pageSize, type: choice, options: ["10", "25"] }
                  - { key: beta, type: boolean }
                  - { key: signature, type: text }
                """));
        assertThat(PreferencesSpec.accepts(spec.field("pageSize"), "25")).isTrue();
        assertThat(PreferencesSpec.accepts(spec.field("pageSize"), "99")).isFalse();
        assertThat(PreferencesSpec.accepts(spec.field("beta"), "true")).isTrue();
        assertThat(PreferencesSpec.accepts(spec.field("beta"), "yes")).isFalse();
        assertThat(PreferencesSpec.accepts(spec.field("signature"), "x".repeat(2000)))
                .isTrue();
        assertThat(PreferencesSpec.accepts(spec.field("signature"), "x".repeat(2001)))
                .isFalse();
    }

    @Test
    void theFourFailureModesCarryTheirCodes(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> PreferencesSpec.load(app(dir, """
                preferences:
                  - { key: "1bad", type: text }
                """)))
                .isInstanceOfSatisfying(TqlException.class,
                        ex -> assertThat(ex.code().toString()).isEqualTo("TQL-YAML-1030"));
        assertThatThrownBy(() -> PreferencesSpec.load(app(dir, """
                preferences:
                  - { key: a, type: text }
                  - { key: a, type: text }
                """)))
                .isInstanceOfSatisfying(TqlException.class,
                        ex -> assertThat(ex.code().toString()).isEqualTo("TQL-YAML-1030"));
        assertThatThrownBy(() -> PreferencesSpec.load(app(dir, """
                preferences:
                  - { key: a, type: dropdown }
                """)))
                .isInstanceOfSatisfying(TqlException.class,
                        ex -> assertThat(ex.code().toString()).isEqualTo("TQL-YAML-1031"));
        assertThatThrownBy(() -> PreferencesSpec.load(app(dir, """
                preferences:
                  - { key: a, type: choice }
                """)))
                .isInstanceOfSatisfying(TqlException.class,
                        ex -> assertThat(ex.code().toString()).isEqualTo("TQL-YAML-1032"));
        assertThatThrownBy(() -> PreferencesSpec.load(app(dir, """
                preferences:
                  - { key: a, type: choice, options: ["x"], default: "y" }
                """)))
                .isInstanceOfSatisfying(TqlException.class,
                        ex -> assertThat(ex.code().toString()).isEqualTo("TQL-YAML-1033"));
    }
}
