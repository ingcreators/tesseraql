package io.tesseraql.yaml.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComponentPolicyTest {

    private static ComponentPolicy policy(Map<String, Object> components) {
        return ComponentPolicy.from(new AppConfig(Map.of("tesseraql",
                Map.of("camel", Map.of("components", components))), name -> null));
    }

    @Test
    void theBaselineHoldsWithoutAnyConfig() {
        ComponentPolicy policy = ComponentPolicy
                .from(new AppConfig(Map.of(), name -> null));

        for (String name : ComponentPolicy.BASELINE_DENIED) {
            assertThat(policy.refusal(name)).as(name).isPresent();
        }
        assertThat(policy.refusal("smtp")).isEmpty();
        assertThat(policy.refusal("direct")).isEmpty();
    }

    @Test
    void configNarrowsButNeverWidens() {
        ComponentPolicy policy = policy(Map.of(
                "allowed", List.of("smtp"),
                "denied", List.of("ftp", "exec")));

        // denied: adds to the baseline; allowed: restricts non-floor components.
        assertThat(policy.refusal("ftp")).isPresent();
        assertThat(policy.refusal("kafka")).isPresent();
        assertThat(policy.refusal("smtp")).isEmpty();
        // The framework floor always resolves, even when allowed: omits it.
        for (String name : ComponentPolicy.FRAMEWORK_FLOOR) {
            assertThat(policy.refusal(name)).as(name).isEmpty();
        }
        // A config allow cannot resurrect a baseline-denied component.
        ComponentPolicy widened = policy(Map.of("allowed", List.of("exec")));
        assertThat(widened.refusal("exec")).isPresent()
                .hasValueSatisfying(reason -> assertThat(reason).contains("baseline"));
    }

    @Test
    void namesAreCaseInsensitive() {
        ComponentPolicy policy = policy(Map.of("denied", List.of("FTP")));

        assertThat(policy.refusal("ftp")).isPresent();
        assertThat(policy.refusal("Exec")).isPresent();
    }

    @Test
    void withoutAnAllowedListOnlyDenialsApply() {
        ComponentPolicy policy = policy(Map.of("denied", List.of("ftp")));

        assertThat(policy.allowedDeclared()).isFalse();
        assertThat(policy.refusal("kafka")).isEmpty();
        assertThat(policy.refusal("ftp")).isPresent();
    }
}
