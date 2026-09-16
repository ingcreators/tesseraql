package io.tesseraql.core.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpressionParserTest {

    private static boolean evalBool(String source, Map<String, Object> vars) {
        return ExpressionParser.parse(source).evalBoolean(new EvaluationContext(vars));
    }

    private static Object eval(String source, Map<String, Object> vars) {
        return ExpressionParser.parse(source).eval(new EvaluationContext(vars));
    }

    @Test
    void nullAndEmptyStringGuard() {
        assertThat(evalBool("q != null && q != \"\"", Map.of("q", "sato"))).isTrue();
        assertThat(evalBool("q != null && q != \"\"", Map.of("q", ""))).isFalse();
        assertThat(
                evalBool("q != null && q != \"\"", java.util.Collections.singletonMap("q", null)))
                .isFalse();
    }

    @Test
    void shortCircuitAvoidsNullDereference() {
        // status is null; first conjunct is false so the second is never evaluated.
        assertThat(evalBool("status != null && status == \"ACTIVE\"",
                java.util.Collections.singletonMap("status", null))).isFalse();
    }

    @Test
    void numericComparison() {
        assertThat(evalBool("limit > 10", Map.of("limit", 50))).isTrue();
        assertThat(evalBool("limit <= 10", Map.of("limit", 50))).isFalse();
        assertThat(evalBool("limit == 50", Map.of("limit", 50L))).isTrue();
    }

    @Test
    void logicalOrAndNegation() {
        assertThat(evalBool("a || b", Map.of("a", false, "b", true))).isTrue();
        assertThat(evalBool("!a", Map.of("a", false))).isTrue();
    }

    @Test
    void dottedPathAndVirtualProperties() {
        assertThat(eval("user.name", Map.of("user", Map.of("name", "sato")))).isEqualTo("sato");
        assertThat(evalBool("items.size > 0", Map.of("items", List.of(1, 2)))).isTrue();
        assertThat(evalBool("items.empty", Map.of("items", List.of()))).isTrue();
    }

    @Test
    void anAbsentValueIsEmptyAndHasNoSize() {
        // `!ids.empty` is the guard the framework tells authors to write around a list bind, and
        // an unselected optional multi-select binds nothing at all — InputBinder never puts an
        // absent optional array into the map. Answering null there made `!ids.empty` true and the
        // guard fail open on exactly the case it exists for.
        assertThat(evalBool("ids.empty", Map.of())).isTrue();
        assertThat(evalBool("!ids.empty", Map.of())).isFalse();
        assertThat(eval("ids.size", Map.of())).isEqualTo(0);
        assertThat(evalBool("ids.size > 0", Map.of())).isFalse();
    }

    @Test
    void anArrayIsEmptyTheSameWayACollectionIs() {
        // `size` had an array arm and `empty` did not, so the two disagreed about the same value.
        assertThat(evalBool("ids.empty", Map.of("ids", new int[0]))).isTrue();
        assertThat(evalBool("ids.empty", Map.of("ids", new String[]{"a"}))).isFalse();
        assertThat(eval("ids.size", Map.of("ids", new int[0]))).isEqualTo(0);
    }

    @Test
    void literals() {
        assertThat(eval("true", Map.of())).isEqualTo(Boolean.TRUE);
        assertThat(eval("null", Map.of())).isNull();
        assertThat(eval("42", Map.of())).isEqualTo(42L);
    }

    @Test
    void syntaxErrorIsReported() {
        assertThatThrownBy(() -> ExpressionParser.parse("a &&"))
                .isInstanceOf(TqlException.class);
        assertThatThrownBy(() -> ExpressionParser.parse("a == == b"))
                .isInstanceOf(TqlException.class);
    }

    @Test
    void numbersCompareAsDecimals() {
        // Two bigint keys past 2^53 used to compare equal through double (docs/audit-low-leads.md
        // G14): an ownership guard passed for the neighbouring id.
        Map<String, Object> ids = Map.of("owner", 1234567890123456789L, "uid",
                1234567890123456790L);
        assertThat(evalBool("owner == uid", ids)).isFalse();
        assertThat(evalBool("owner != uid", ids)).isTrue();
        assertThat(evalBool("owner < uid", ids)).isTrue();
        assertThat(evalBool("x == 9007199254740993", Map.of("x", 9007199254740992L))).isFalse();
        assertThat(evalBool("amt == cap", Map.of("amt", new java.math.BigDecimal(
                "1234567890123456789.01"), "cap", 1234567890123456789L))).isFalse();
        assertThat(evalBool("amt > cap", Map.of("amt", new java.math.BigDecimal(
                "1234567890123456789.01"), "cap", 1234567890123456789L))).isTrue();
        // The mixed-kind identities that held before still hold: compareTo ignores scale.
        assertThat(evalBool("l == d", Map.of("l", 10L, "d", 10.0d))).isTrue();
        assertThat(evalBool("l == bd", Map.of("l", 10L, "bd", new java.math.BigDecimal("10.00"))))
                .isTrue();
        assertThat(evalBool("i == bd", Map.of("i", 10, "bd", new java.math.BigDecimal("10.00"))))
                .isTrue();
        // The doc's own example: an exact product one unit over the budget is over it.
        assertThat(evalBool("qty * price <= budget", Map.of("qty", 1000000000000L,
                "price", new java.math.BigDecimal("1234567.890123456789"),
                "budget", new java.math.BigDecimal("1234567890123456788")))).isFalse();
        // type: number admits NaN and the infinities; they keep the IEEE ordering, coded nowhere.
        assertThat(evalBool("n == 1", Map.of("n", Double.NaN))).isFalse();
        assertThat(evalBool("n > 1", Map.of("n", Double.POSITIVE_INFINITY))).isTrue();
    }

    @Test
    void aRelationalComparisonOnANullOperandIsRefusedAsCoded() {
        // docs/audit-low-leads.md G11: the site used to throw a raw IllegalArgumentException
        // (an uncoded 500 per request, a stack trace per request in the log).
        Map<String, Object> absent = Map.of("budget", 60);
        assertThatThrownBy(() -> evalBool("minPrice > 0", absent))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2122")
                .hasMessageContaining(">: the left operand is null")
                .hasMessageContaining("x != null &&");
        assertThatThrownBy(() -> evalBool("qty * price <= missing", Map.of("qty", 2, "price", 3)))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("<=: the right operand is null");
        assertThatThrownBy(() -> evalBool("length(x) > 3", Map.of()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2122");
        // Equality stays null-safe, and the documented guard skips the comparison.
        assertThat(evalBool("minPrice == 0", absent)).isFalse();
        assertThat(evalBool("minPrice != null && minPrice > 0", absent)).isFalse();
    }

    @Test
    void aComparisonOfUnrelatedKindsIsRefusedAsCoded() {
        // A raw ClassCastException from String.compareTo used to escape as a 500.
        assertThatThrownBy(() -> evalBool("s > 9", Map.of("s", "10")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2122")
                .hasMessageContaining(">: String and Long are not comparable");
        assertThatThrownBy(() -> evalBool("d > '2026-01-01'",
                Map.of("d", java.time.LocalDate.of(2026, 1, 15))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("LocalDate and String are not comparable");
        assertThatThrownBy(() -> evalBool("1 < 2 < 3", Map.of()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Boolean and Long are not comparable");
        // Two values of one kind compare; equality across kinds is simply false.
        assertThat(evalBool("d > e", Map.of("d", java.time.LocalDate.of(2026, 1, 15),
                "e", java.time.LocalDate.of(2026, 1, 1)))).isTrue();
        assertThat(evalBool("a < b", Map.of("a", "apple", "b", "pear"))).isTrue();
        assertThat(evalBool("d == '2026-01-15'",
                Map.of("d", java.time.LocalDate.of(2026, 1, 15)))).isFalse();
    }

    @Test
    void arithmeticOnANonNumberOrByZeroIsRefusedAsCoded() {
        assertThatThrownBy(() -> eval("s * 2", Map.of("s", "abc")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2122")
                .hasMessageContaining("arithmetic needs numbers, and met String");
        assertThatThrownBy(() -> eval("total / count", Map.of("total", 10, "count", 0)))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2122")
                .hasMessageContaining("/:");
        assertThatThrownBy(() -> eval("total % count", Map.of("total", 10, "count", 0)))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2122");
        assertThatThrownBy(() -> eval("-s", Map.of("s", "abc")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2122");
    }

    @Test
    void aStringLiteralKnowsThreeEscapesAndRefusesTheRest() {
        // docs/audit-low-leads.md G13: the lexer used to keep the character and drop the
        // backslash, so '\d{3}-\d{4}' was the regex d{3}-d{4} and lint saw nothing.
        assertThat(eval("'it\\'s'", Map.of())).isEqualTo("it's");
        assertThat(eval("\"say \\\"hi\\\"\"", Map.of())).isEqualTo("say \"hi\"");
        assertThat(eval("'a\\\\b'", Map.of())).isEqualTo("a\\b");
        assertThatThrownBy(() -> ExpressionParser.parse("matches(zip, '\\d{3}-\\d{4}')"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2101")
                .hasMessageContaining("Unknown escape '\\d'")
                .hasMessageContaining("'\\\\d'");
        assertThatThrownBy(() -> ExpressionParser.parse("'tab\\there'"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Unknown escape '\\t'");
        assertThatThrownBy(() -> ExpressionParser.parse("'\\u3042'"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Unknown escape '\\u'");
        // The doubled spelling is the regex the author meant.
        assertThat(evalBool("matches(zip, '\\\\d{3}-\\\\d{4}')", Map.of("zip", "123-4567")))
                .isTrue();
        assertThat(evalBool("matches(zip, '\\\\d{3}-\\\\d{4}')", Map.of("zip", "ddd-dddd")))
                .isFalse();
        assertThat(evalBool("matches(s, 'a\\\\.b')", Map.of("s", "aXb"))).isFalse();
    }

    @Test
    void aLiteralPatternCompilesOnceAtParseAndABadOneIsAParseError() {
        // docs/audit-low-leads.md unfiled 11 / F121: a literal that does not compile used to
        // pass lint and boot and throw a raw PatternSyntaxException on every request; a
        // literal that did was compiled at first evaluation into a process-wide cache.
        assertThatThrownBy(() -> ExpressionParser.parse("matches(body.zip, '(')"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2101")
                .hasMessageContaining("matches(): the pattern does not compile");
        Expr.Call literal = (Expr.Call) ExpressionParser.parse("matches(body.zip, '[a-z]+')");
        assertThat(literal.regex()).isNotNull();
        assertThat(literal.regex().pattern()).isEqualTo("[a-z]+");
        // A pattern that is not a literal compiles per evaluation, is held nowhere, and a bad
        // one is the coded evaluation refusal.
        Expr.Call bound = (Expr.Call) ExpressionParser.parse("matches(body.zip, body.mask)");
        assertThat(bound.regex()).isNull();
        assertThat(bound.evalBoolean(new EvaluationContext(
                Map.of("body", Map.of("zip", "123", "mask", "[0-9]+"))))).isTrue();
        assertThatThrownBy(() -> bound.evalBoolean(new EvaluationContext(
                Map.of("body", Map.of("zip", "123", "mask", "(")))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2122")
                .hasMessageContaining("matches(): the pattern bound at request time does not"
                        + " compile");
        assertThat(java.util.Arrays.stream(Expr.Call.class.getDeclaredFields())
                .filter(f -> java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .filter(f -> java.util.Map.class.isAssignableFrom(f.getType())
                        && !f.getName().equals("FUNCTIONS")))
                .as("no static map holds a compiled pattern")
                .isEmpty();
    }
}
