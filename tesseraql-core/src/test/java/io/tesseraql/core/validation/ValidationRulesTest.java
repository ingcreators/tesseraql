package io.tesseraql.core.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.sql.ScopeResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Phase 19 validation rule engine: cross-field expression rules, when-guards,
 * the stable violation shape (rule id, field path, rule code, message key), and the fail-fast
 * shape checks. SQL rules execute against a live database and are covered by the integration
 * tests in tesseraql-test-core and tesseraql-runtime.
 */
class ValidationRulesTest {

    @Test
    void passingExpressionRuleYieldsNoViolation() throws Exception {
        ValidationRules rules = new ValidationRules(List.of(ValidationRules.expression(
                "dateOrder", null, "body.endDate >= body.startDate",
                "endDate", null, null)));

        List<Map<String, Object>> violations = rules.evaluate(
                Map.of("body", Map.of("startDate", "2026-01-01", "endDate", "2026-12-31")), null,
                ScopeResolver.UNSUPPORTED, null, null);

        assertThat(violations).isEmpty();
    }

    @Test
    void failingExpressionRuleReportsRuleFieldCodeAndMessage() throws Exception {
        ValidationRules rules = new ValidationRules(List.of(ValidationRules.expression(
                "dateOrder", null, "body.endDate >= body.startDate",
                "endDate", "end-before-start", "members.dates.end-before-start")));

        List<Map<String, Object>> violations = rules.evaluate(
                Map.of("body", Map.of("startDate", "2026-12-31", "endDate", "2026-01-01")), null,
                ScopeResolver.UNSUPPORTED, null, null);

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
                Map.of("body", Map.of("quantity", -1)), null, ScopeResolver.UNSUPPORTED, null,
                null);

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
                Map.of("body", Map.of("startDate", "2026-01-01")), null, ScopeResolver.UNSUPPORTED,
                null, null);

        assertThat(violations).isEmpty();
    }

    @Test
    void everyRuleRunsAndAllViolationsAreCollected() throws Exception {
        ValidationRules rules = new ValidationRules(List.of(
                ValidationRules.expression("a", null, "body.x > 0", "x", null, null),
                ValidationRules.expression("b", null, "body.y > 0", "y", null, null)));

        List<Map<String, Object>> violations = rules.evaluate(
                Map.of("body", Map.of("x", -1, "y", -1)), null, ScopeResolver.UNSUPPORTED, null,
                null);

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
    void anUnguardedRuleOnAnAbsentOptionalFieldIsRefusedAsCoded() {
        // docs/audit-low-leads.md G11: the rule answered 422 with the field present and, with it
        // absent, a raw IllegalArgumentException the command processor wrapped as TQL-SQL-2600.
        // The refusal is coded now, names the guard, and is not a violation — a false here would
        // report an optional field the caller left out as invalid.
        ValidationRules rules = new ValidationRules(List.of(ValidationRules.expression(
                "priceCap", null, "body.minPrice < 1000", "minPrice", null, null)));

        assertThatThrownBy(() -> rules.evaluate(Map.of("body", Map.of("name", "x")), null,
                ScopeResolver.UNSUPPORTED, null, null))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2122")
                .hasMessageContaining("the left operand is null");
    }

    @Test
    void theDocumentedWhenGuardSkipsTheComparisonOnAnAbsentField() throws Exception {
        ValidationRules rules = new ValidationRules(List.of(ValidationRules.expression(
                "priceCap", "body.minPrice != null", "body.minPrice < 1000", "minPrice", null,
                null)));

        assertThat(rules.evaluate(Map.of("body", Map.of("name", "x")), null,
                ScopeResolver.UNSUPPORTED, null, null)).isEmpty();
    }

    @Test
    void aBadLiteralPatternFailsAtCompileTime() {
        // docs/audit-low-leads.md unfiled 11: it used to pass lint and boot and throw a raw
        // PatternSyntaxException on every request.
        assertThatThrownBy(() -> ValidationRules.expression("zip", null,
                "matches(body.zip, '(')", "zip", null, null))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2101")
                .hasMessageContaining("does not compile");
    }

    @Test
    void malformedExpressionFailsAtCompileTime() {
        assertThatThrownBy(() -> ValidationRules.expression("r", null, "body.x >", "x", null,
                null))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2101");
    }

    @Test
    void customExpressionFunctionsWorkInValidationRules() throws Exception {
        io.tesseraql.core.expr.ExpressionFunctions.install(List.of(
                new io.tesseraql.core.expr.ExpressionFunction() {
                    @Override
                    public String name() {
                        return "isKatakana";
                    }

                    @Override
                    public int arity() {
                        return 1;
                    }

                    @Override
                    public Object apply(List<Object> args) {
                        return args.get(0) != null
                                && String.valueOf(args.get(0)).matches("[\\u30A0-\\u30FF]+");
                    }
                }));
        try {
            ValidationRules rules = new ValidationRules(List.of(ValidationRules.expression(
                    "kanaName", null, "isKatakana(body.kanaName)", "kanaName", "not-kana", null)));

            assertThat(rules.evaluate(Map.of("body", Map.of("kanaName", "カタカナ")), null,
                    ScopeResolver.UNSUPPORTED, null, null))
                    .isEmpty();
            assertThat(rules.evaluate(Map.of("body", Map.of("kanaName", "sato")), null,
                    ScopeResolver.UNSUPPORTED, null, null))
                    .singleElement()
                    .satisfies(violation -> assertThat(violation)
                            .containsEntry("code", "not-kana"));
        } finally {
            io.tesseraql.core.expr.ExpressionFunctions.reset();
        }
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

    /**
     * The same strip, for what lint reads. The scaffolded shape is the regression: every
     * generated file opens with its own checksum line, and the optimistic-locking nudges had
     * therefore never once reached a statement this framework wrote.
     */
    @Test
    void statementBodyDropsLeadingComments() {
        assertThat(ValidationRules.statementBody("update t set a = 1"))
                .isEqualTo("update t set a = 1");
        assertThat(ValidationRules.statementBody("""
                -- tesseraql-scaffold-checksum: sha256:abc
                -- Scaffolded update for the items table.
                update items set a = 1""")).isEqualTo("update items set a = 1");
        assertThat(ValidationRules.statementBody("/* header */ UPDATE t SET a = 1"))
                .isEqualTo("UPDATE t SET a = 1");
        // An unterminated comment leaves nothing behind rather than looping or throwing.
        assertThat(ValidationRules.statementBody("/* never closed")).isEmpty();
        assertThat(ValidationRules.statementBody(null)).isEmpty();
    }
}
