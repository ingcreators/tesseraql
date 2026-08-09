package io.tesseraql.yaml.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code ui:} block of an MCP Apps UI resource. Every value used to be read with an
 * {@code instanceof} that fell back to the default, so a wrong-typed one disappeared: the host
 * framed nothing, or the iframe kept its default-deny CSP while the fragment's requests failed —
 * with no diagnostic naming the config that was ignored (docs/silent-tolerance.md K-e).
 */
class UiSpecTest {

    @Test
    void readsADeclaredBlock() {
        UiSpec spec = UiSpec.from(Map.of(
                "prefersBorder", true,
                "csp", Map.of("connectDomains", List.of("https://api.example.com"),
                        "resourceDomains", List.of("https://cdn.example.com"))));

        assertThat(spec.prefersBorder()).isTrue();
        assertThat(spec.cspConnectDomains()).containsExactly("https://api.example.com");
        assertThat(spec.cspResourceDomains()).containsExactly("https://cdn.example.com");
    }

    @Test
    void anAbsentBlockIsEmptyNotAnError() {
        assertThat(UiSpec.from(null)).isEqualTo(UiSpec.EMPTY);
        assertThat(UiSpec.from(null).isEmpty()).isTrue();
    }

    @Test
    void rejectsAWrongTypedPrefersBorder() {
        assertThatThrownBy(() -> UiSpec.from(Map.of("prefersBorder", "true")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1026")
                .hasMessageContaining("prefersBorder");
    }

    @Test
    void rejectsAMisShapedCspBlockAndAScalarDomainList() {
        assertThatThrownBy(() -> UiSpec.from(Map.of("csp", List.of("https://api.example.com"))))
                .isInstanceOf(TqlException.class).hasMessageContaining("ui.csp");

        // One host written as a scalar instead of a list left the allow-list empty.
        assertThatThrownBy(() -> UiSpec.from(
                Map.of("csp", Map.of("connectDomains", "https://api.example.com"))))
                .isInstanceOf(TqlException.class).hasMessageContaining("connectDomains");
    }

    @Test
    void rejectsANonBlockUiValue() {
        assertThatThrownBy(() -> UiSpec.from("prefersBorder"))
                .isInstanceOf(TqlException.class).hasMessageContaining("ui:");
    }
}
