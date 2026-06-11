package io.tesseraql.core.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Phase 19 validation rule engine: cross-field expression rules, when-guards,
 * the stable violation shape (rule id, field path, rule code, message key), and the fail-fast
 * shape checks. SQL rules execute against a live database and are covered by the integration
 * tests in tesseraql-test-core and tesseraql-camel-runtime.
 */
class ValidationRulesTest {

    @Test
    void passingExpressionRuleYieldsNoViolation() throws Exception {
        ValidationRules rules = new ValidationRules(List.of(ValidationRules.expression(
                "dateOrder", null, "body.endDate >= body.startDate",
                "endDate", null, null)));

        List<Map<String, Object>> violations = rules.evaluate(
                Map.of("body", Map.of("startDate", "2026-01-01", "endDate", "2026-12-31")), null);

        assertThat(violations).isEmpty();
    }

    @Test
    void failingExpressionRuleReportsRuleFieldCodeAndMessage() throws Exception {
        ValidationRules rules = new ValidationRules(List.of(ValidationRules.expression(
                "dateOrder", null, "body.endDate >= body.startDate",
                "endDate", "end-before-start", "members.dates.end-before-start")));

        List<Map<String, Object>> violations = rules.evaluate(
                Map.of("body", Map.of("startDate", "2026-12-31", "endDate", "2026-01-01")), null);

        assertThat(violations).containsExactly(Map.of(
                "rule", "dateOrder",
                "field", "endDate",
                "code", "end-before-start",
                "message", "members.dates.end-before-start"));
    }

    @Test
    void ruleCodeDefaultsToTheRuleIdAndMessageIsOmittedWhenUndeclared() throws Exception {
        ValidationRules rules = new ValidationRules(List.of(ValidationRules.expression(
                "quantityPositive", null, "body.quantity > 0", "quantity", null, null)));

        List<Map<String, Object>> violations = rules.evaluate(
                Map.of("body", Map.of("quantity", -1)), null);

        assertThat(violations).containsExactly(Map.of(
                "rule", "quantityPositive",
                "field", "quantity",
                "code", "quantityPositive"));
    }

    @Test
    void falsyWhenGuardSkipsTheRule() throws Exception {
        ValidationRules rules = new ValidationRules(List.of(ValidationRules.expression(
                "dateOrder", "body.endDate != null", "body.endDate >= body.startDate",
                "endDate", null, null)));

        // No endDate in the body: the guard is falsy, so the comparison never runs.
        List<Map<String, Object>> violations = rules.evaluate(
                Map.of("body", Map.of("startDate", "2026-01-01")), null);

        assertThat(violations).isEmpty();
    }

    @Test
    void everyRuleRunsAndAllViolationsAreCollected() throws Exception {
        ValidationRules rules = new ValidationRules(List.of(
                ValidationRules.expression("a", null, "body.x > 0", "x", null, null),
                ValidationRules.expression("b", null, "body.y > 0", "y", null, null)));

        List<Map<String, Object>> violations = rules.evaluate(
                Map.of("body", Map.of("x", -1, "y", -1)), null);

        assertThat(violations).extracting(v -> v.get("rule")).containsExactly("a", "b");
    }

    @Test
    void expressionRuleRequiresRuleAndField() {
        assertThatThrownBy(() -> ValidationRules.expression("r", null, " ", "f", null, null))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-FIELD-2003")
                .hasMessageContaining("rule 'r'")
                .hasMessageContaining("needs a rule:");
        assertThatThrownBy(() -> ValidationRules.expression("r", null, "body.x > 0", null, null,
                null))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-FIELD-2003")
                .hasMessageContaining("field:");
    }

    @Test
    void sqlRuleMustBeASelect() {
        assertThatThrownBy(() -> ValidationRules.sql("r", null,
                "update users set status = 'X'", "check.sql", Map.of(), "f", null, null))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-FIELD-2003")
                .hasMessageContaining("must be a SELECT");
    }

    @Test
    void malformedExpressionFailsAtCompileTime() {
        assertThatThrownBy(() -> ValidationRules.expression("r", null, "body.x >", "x", null,
                null))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2101");
    }

    @Test
    void selectDetectionSkipsLeadingCommentsAndAcceptsWithClauses() {
        assertThat(ValidationRules.isSelect("select 1")).isTrue();
        assertThat(ValidationRules.isSelect("  \n-- existence check\nSELECT 1")).isTrue();
        assertThat(ValidationRules.isSelect("/* header */ with v as (select 1) select * from v"))
                .isTrue();
        assertThat(ValidationRules.isSelect("update users set status = 'X'")).isFalse();
        assertThat(ValidationRules.isSelect(null)).isFalse();
    }
}
