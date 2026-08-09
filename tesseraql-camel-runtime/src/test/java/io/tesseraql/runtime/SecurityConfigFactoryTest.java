package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.security.SecurityConfig;
import io.tesseraql.yaml.config.AppConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Policy parsing, incl. the deny-all diagnostic for an unrecognized rule (silent-tolerance O10). */
class SecurityConfigFactoryTest {

    @Test
    void aRecognizedRuleBuildsARoleRule() {
        SecurityConfig config = SecurityConfigFactory.build(new AppConfig(Map.of(
                "tesseraql", Map.of("security", Map.of("policies", Map.of(
                        "app.read", Map.of("anyOf", List.of(Map.of("role", "READER")))))))));

        assertThat(config.policies()).containsKey("app.read");
        assertThat(config.policies().get("app.read").anyOf()).hasSize(1);
    }

    @Test
    void anUnrecognizedRuleYieldsAZeroRuleDenyAllPolicy() {
        // `permissions:` (plural) is a typo for `permission:`; the rule is unrecognized and
        // dropped. The policy is now logged, but its deny-all behavior (no rules) is preserved.
        SecurityConfig config = SecurityConfigFactory.build(new AppConfig(Map.of(
                "tesseraql", Map.of("security", Map.of("policies", Map.of(
                        "app.read",
                        Map.of("anyOf", List.of(Map.of("permissions", "items:read")))))))));

        assertThat(config.policies().get("app.read").anyOf()).isEmpty();
    }
}
