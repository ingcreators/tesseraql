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
        assertThat(evalBool("q != null && q != \"\"", java.util.Collections.singletonMap("q", null)))
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
}
