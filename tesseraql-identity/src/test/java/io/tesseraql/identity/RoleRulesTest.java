package io.tesseraql.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoleRulesTest {

    private static Map<String, Object> row(String rule, String role, String attr, String kind,
            String value) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("rule_id", rule);
        map.put("role_code", role);
        map.put("attribute_name", attr);
        map.put("match_kind", kind);
        map.put("value", value);
        return map;
    }

    @Test
    void positiveRowsOrTogetherAndAttributesAnd() {
        List<Map<String, Object>> rows = List.of(
                row("r1", "keiri", "department", "in", "accounting"),
                row("r1", "keiri", "department", "in", "finance"),
                row("r1", "keiri", "title", "eq", "manager"));
        assertThat(RoleRules.evaluate(rows,
                Map.of("department", "finance", "title", "manager"), List.of(), (a, b) -> false))
                .containsExactly("keiri");
        assertThat(RoleRules.evaluate(rows,
                Map.of("department", "sales", "title", "manager"), List.of(), (a, b) -> false))
                .isEmpty();
        assertThat(RoleRules.evaluate(rows,
                Map.of("department", "finance"), List.of(), (a, b) -> false)).isEmpty();
    }

    @Test
    void negativeGroupAndSubtreeKinds() {
        assertThat(RoleRules.evaluate(
                List.of(row("r1", "staff", "type", "neq", "contractor")),
                Map.of("type", "contractor"), List.of(), (a, b) -> false)).isEmpty();
        assertThat(RoleRules.evaluate(
                List.of(row("r1", "staff", "type", "neq", "contractor")),
                Map.of("type", "employee"), List.of(), (a, b) -> false))
                .containsExactly("staff");
        assertThat(RoleRules.evaluate(
                List.of(row("r2", "sales", null, "group", "SALES")),
                Map.of(), List.of("SALES"), (a, b) -> false)).containsExactly("sales");
        assertThat(RoleRules.evaluate(
                List.of(row("r3", "east", "org_unit", "subtree", "east-hq")),
                Map.of("org_unit", "east-1"), List.of(),
                (ancestor, descendant) -> "east-hq".equals(ancestor)
                        && "east-1".equals(descendant)))
                .containsExactly("east");
    }

    @Test
    void aConditionlessRuleGrantsUnconditionally() {
        assertThat(RoleRules.evaluate(List.of(row("r1", "everyone", null, null, null)),
                Map.of(), List.of(), (a, b) -> false)).containsExactly("everyone");
    }

    @Test
    void validationRefusesWhatItMust() {
        assertThatThrownBy(() -> RoleRules.validateCondition("a", "regex", "x", true))
                .isInstanceOf(TqlException.class).hasMessageContaining("Unknown condition");
        assertThatThrownBy(() -> RoleRules.validateCondition("a", "eq", " ", true))
                .isInstanceOf(TqlException.class).hasMessageContaining("value");
        assertThatThrownBy(() -> RoleRules.validateCondition("org_unit", "subtree", "hq", false))
                .isInstanceOf(TqlException.class).hasMessageContaining("org-unit foundation");
        RoleRules.validateCondition(null, "group", "SALES", false);
    }
}
