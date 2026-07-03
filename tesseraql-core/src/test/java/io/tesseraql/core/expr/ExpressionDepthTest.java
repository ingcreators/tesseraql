package io.tesseraql.core.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Arithmetic, string functions, and the whitelisted call set (roadmap Phase 40). */
class ExpressionDepthTest {

    private static Object eval(String source, Map<String, Object> scope) {
        return ExpressionParser.parse(source).eval(new EvaluationContext(scope));
    }

    @Test
    void arithmeticIsDecimalExactWithPrecedence() {
        Map<String, Object> scope = Map.of("qty", 3, "price", new BigDecimal("19.99"),
                "budget", new BigDecimal("60"));
        assertThat(eval("qty * price", scope)).isEqualTo(new BigDecimal("59.97"));
        // Precedence: * binds over +, parentheses override.
        assertThat(eval("1 + 2 * 3", Map.of())).isEqualTo(new BigDecimal("7"));
        assertThat(eval("(1 + 2) * 3", Map.of())).isEqualTo(new BigDecimal("9"));
        assertThat(eval("10 % 3", Map.of())).isEqualTo(new BigDecimal("1"));
        assertThat(eval("1 / 3 * 3", Map.of()))
                .isEqualTo(new BigDecimal("0.9999999999999999"));
        // The acceptance rule: a business bound is declarable without SQL.
        assertThat(ExpressionParser.parse("qty * price <= budget")
                .evalBoolean(new EvaluationContext(scope))).isTrue();
    }

    @Test
    void unaryMinusNegates() {
        assertThat(eval("-2 + 5", Map.of())).isEqualTo(new BigDecimal("3"));
        assertThat(eval("5 > -1", Map.of())).isEqualTo(true);
    }

    @Test
    void plusConcatenatesWhenEitherSideIsAString() {
        assertThat(eval("'a' + 'b'", Map.of())).isEqualTo("ab");
        assertThat(eval("'total: ' + 2 * 3", Map.of())).isEqualTo("total: 6");
    }

    @Test
    void nullOperandsPropagateNull() {
        assertThat(eval("missing * 2", Map.of())).isNull();
        assertThat(eval("-missing", Map.of())).isNull();
        assertThat(eval("coalesce(missing, 5)", Map.of())).isEqualTo(5L);
    }

    @Test
    void stringFunctionsCoverTheLobBasics() {
        Map<String, Object> scope = Map.of("mail", "Dev@Example.COM ", "name", "sato");
        assertThat(eval("length(name)", scope)).isEqualTo(4);
        assertThat(eval("lower(trim(mail))", scope)).isEqualTo("dev@example.com");
        assertThat(eval("upper(name)", scope)).isEqualTo("SATO");
        assertThat(eval("contains(name, 'at')", scope)).isEqualTo(true);
        assertThat(eval("startsWith(name, 'sa') && endsWith(name, 'to')", scope))
                .isEqualTo(true);
        assertThat(eval("matches(name, '[a-z]+')", scope)).isEqualTo(true);
        assertThat(eval("matches(name, '\\d+')", scope)).isEqualTo(false);
        // Null-safe: predicates are false, transforms are null.
        assertThat(eval("contains(missing, 'x')", Map.of())).isEqualTo(false);
        assertThat(eval("lower(missing)", Map.of())).isNull();
    }

    @Test
    void numericFunctionsRoundAndClamp() {
        assertThat(eval("round(2.5)", Map.of())).isEqualTo(new BigDecimal("3"));
        assertThat(eval("floor(2.9)", Map.of())).isEqualTo(new BigDecimal("2"));
        assertThat(eval("ceil(2.1)", Map.of())).isEqualTo(new BigDecimal("3"));
        assertThat(eval("abs(-4)", Map.of())).isEqualTo(new BigDecimal("4"));
        assertThat(eval("min(2, 9)", Map.of())).isEqualTo(new BigDecimal("2"));
        assertThat(eval("max(2, 9)", Map.of())).isEqualTo(new BigDecimal("9"));
    }

    @Test
    void unknownFunctionsAndWrongAritiesFailAtParse() {
        // An unknown name never reaches evaluation (whitelist, guardrail ch. 20.6): it parses
        // as a path and then the '(' is a syntax error.
        assertThatThrownBy(() -> ExpressionParser.parse("exec('rm')"))
                .isInstanceOf(TqlException.class);
        assertThatThrownBy(() -> ExpressionParser.parse("length('a', 'b')"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("takes 1 argument");
        assertThatThrownBy(() -> ExpressionParser.parse("min(1)"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("takes 2 arguments");
    }

    @Test
    void aFunctionNameWithoutParensIsStillAPath() {
        assertThat(eval("min", Map.of("min", 7))).isEqualTo(7);
    }

    @Test
    void comparisonsInteroperateWithArithmeticResults() {
        assertThat(ExpressionParser.parse("2 * 3 == 6")
                .evalBoolean(new EvaluationContext(Map.of()))).isTrue();
        assertThat(ExpressionParser.parse("1 / 4 < 0.3")
                .evalBoolean(new EvaluationContext(Map.of()))).isTrue();
    }
}
