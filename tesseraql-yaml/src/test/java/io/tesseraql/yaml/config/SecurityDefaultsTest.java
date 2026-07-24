package io.tesseraql.yaml.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.SecuritySpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecurityDefaultsTest {

    private static AppConfig config(List<Map<String, Object>> rules) {
        return new AppConfig(Map.of("tesseraql", Map.of("security",
                Map.of("defaults", Map.of("routes", rules)))), name -> null);
    }

    private static final AppConfig SCAFFOLD_SHAPE = config(List.of(
            Map.of("match", "/api/**", "auth", "bearer"),
            Map.of("match", "/**", "auth", "browser", "csrf", "auto")));

    @Test
    void firstMatchingRuleWinsInDeclarationOrder() {
        SecurityDefaults defaults = SecurityDefaults.from(SCAFFOLD_SHAPE);

        assertThat(defaults.resolve("GET", "/api/users", null).auth()).isEqualTo("bearer");
        assertThat(defaults.resolve("GET", "/users", null).auth()).isEqualTo("browser");
        // The trailing /** also matches the bare prefix, the ant-path convention.
        assertThat(defaults.resolve("GET", "/api", null).auth()).isEqualTo("bearer");
    }

    @Test
    void csrfAutoRequiresCsrfExactlyOnBrowserWrites() {
        SecurityDefaults defaults = SecurityDefaults.from(SCAFFOLD_SHAPE);

        assertThat(defaults.resolve("POST", "/items/create", null).csrf()).isTrue();
        assertThat(defaults.resolve("GET", "/items", null).csrf()).isNull();
        // Bearer routes never inherit CSRF from auto, whatever the method.
        assertThat(defaults.resolve("POST", "/api/items", null).csrf()).isNull();
    }

    @Test
    void routeLocalKeysAlwaysWin() {
        SecurityDefaults defaults = SecurityDefaults.from(config(List.of(
                Map.of("match", "/**", "auth", "browser", "csrf", "auto", "policy", "app.read"))));

        SecuritySpec declared = new SecuritySpec("bearer", "items.write", null, Boolean.FALSE);
        SecuritySpec effective = defaults.resolve("POST", "/items", declared);

        assertThat(effective.auth()).isEqualTo("bearer");
        assertThat(effective.policy()).isEqualTo("items.write");
        assertThat(effective.csrf()).isFalse();
    }

    @Test
    void declaredKeysMergeWithRuleKeysPerKey() {
        SecurityDefaults defaults = SecurityDefaults.from(config(List.of(
                Map.of("match", "/admin/**", "auth", "browser", "policy", "admin.view"))));

        // The route declares only a policy; auth comes from the rule.
        SecuritySpec effective = defaults.resolve("GET", "/admin/audit",
                new SecuritySpec(null, "admin.audit", null, null));

        assertThat(effective.auth()).isEqualTo("browser");
        assertThat(effective.policy()).isEqualTo("admin.audit");
    }

    @Test
    void publicRoutesNeverInheritAPolicy() {
        SecurityDefaults defaults = SecurityDefaults.from(config(List.of(
                Map.of("match", "/**", "auth", "browser", "csrf", "auto", "policy", "app.read"))));

        SecuritySpec effective = defaults.resolve("POST", "/health",
                new SecuritySpec("public", null, null, null));

        assertThat(effective.auth()).isEqualTo("public");
        assertThat(effective.policy()).isNull();
        // csrf: auto guards browser sessions; a public route has none.
        assertThat(effective.csrf()).isNull();
    }

    @Test
    void unmatchedPathsKeepTheDeclaredSpecUntouched() {
        SecurityDefaults defaults = SecurityDefaults.from(config(List.of(
                Map.of("match", "/api/**", "auth", "bearer"))));

        SecuritySpec declared = new SecuritySpec("browser", null, null, null);
        assertThat(defaults.resolve("GET", "/users", declared)).isSameAs(declared);
        assertThat(defaults.resolve("GET", "/users", null)).isNull();
    }

    @Test
    void singleStarStaysWithinOneSegment() {
        SecurityDefaults defaults = SecurityDefaults.from(config(List.of(
                Map.of("match", "/api/*/export", "auth", "bearer"))));

        assertThat(defaults.matchedRule("/api/users/export")).isPresent();
        assertThat(defaults.matchedRule("/api/users/all/export")).isEmpty();
    }

    @Test
    void absentConfigYieldsNoRules() {
        SecurityDefaults defaults = SecurityDefaults
                .from(new AppConfig(Map.of(), name -> null));

        assertThat(defaults.isEmpty()).isTrue();
        SecuritySpec declared = new SecuritySpec("bearer", null, null, null);
        assertThat(defaults.resolve("GET", "/api/users", declared)).isSameAs(declared);
    }

    @Test
    void malformedRulesFailFast() {
        assertThatThrownBy(() -> SecurityDefaults.from(config(List.of(
                Map.of("auth", "bearer")))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("match");
        assertThatThrownBy(() -> SecurityDefaults.from(config(List.of(
                Map.of("match", "/**", "csrf", "sometimes")))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("csrf");
        assertThatThrownBy(() -> SecurityDefaults.from(config(List.of(
                Map.of("match", "/**")))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("declares nothing");
        assertThatThrownBy(() -> SecurityDefaults.from(new AppConfig(
                Map.of("tesseraql", Map.of("security", Map.of("defaults",
                        Map.of("routes", "everything")))),
                name -> null)))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("list");
    }
}
