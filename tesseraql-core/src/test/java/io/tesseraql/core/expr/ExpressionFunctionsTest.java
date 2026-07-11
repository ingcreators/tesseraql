package io.tesseraql.core.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The custom-function hook (ExpressionFunction SPI): installed functions parse and evaluate
 * wherever the expression language runs, installation fails fast on names that would change the
 * meaning of existing expressions, and the built-in whitelist stays untouchable.
 */
class ExpressionFunctionsTest {

    /** A fixed-arity custom function for the tests. */
    private record Fn(String name, int arity,
            java.util.function.Function<List<Object>, Object> body)
            implements
                ExpressionFunction {

        @Override
        public Object apply(List<Object> args) {
            return body.apply(args);
        }
    }

    @AfterEach
    void reset() {
        ExpressionFunctions.reset();
    }

    private static Object eval(String source, Map<String, Object> vars) {
        return ExpressionParser.parse(source).eval(new EvaluationContext(vars));
    }

    @Test
    void installedFunctionsParseAndEvaluate() {
        ExpressionFunctions.install(List.of(
                new Fn("isKatakana", 1, args -> args.get(0) != null
                        && String.valueOf(args.get(0)).matches("[\\u30A0-\\u30FF]+")),
                new Fn("clamp", 3, args -> {
                    java.math.BigDecimal v = new java.math.BigDecimal(String.valueOf(args.get(0)));
                    java.math.BigDecimal lo = new java.math.BigDecimal(String.valueOf(args.get(1)));
                    java.math.BigDecimal hi = new java.math.BigDecimal(String.valueOf(args.get(2)));
                    return v.max(lo).min(hi);
                })));

        assertThat(eval("isKatakana(name)", Map.of("name", "カタカナ"))).isEqualTo(true);
        assertThat(eval("isKatakana(name)", Map.of("name", "sato"))).isEqualTo(false);
        // Arity is not limited to the built-ins' one or two arguments.
        assertThat(eval("clamp(qty, 1, 10)", Map.of("qty", 42)))
                .isEqualTo(new java.math.BigDecimal("10"));
        // Custom functions compose with built-ins and operators like any whitelisted call.
        assertThat(eval("isKatakana(trim(name)) && length(name) > 2", Map.of("name", " カタカナ ")))
                .isEqualTo(true);
    }

    @Test
    void nullArgumentsReachTheFunctionAsNulls() {
        ExpressionFunctions.install(List.of(new Fn("firstIsNull", 1,
                args -> args.get(0) == null)));

        assertThat(eval("firstIsNull(missing)", Map.of())).isEqualTo(true);
    }

    @Test
    void unknownFunctionsStayParseErrorsWithAHelpfulMessage() {
        assertThatThrownBy(() -> ExpressionParser.parse("isKatakana(name)"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Unknown function 'isKatakana()'")
                .hasMessageContaining("tesseraql.modules");
    }

    @Test
    void customAritiesAreEnforcedAtParse() {
        ExpressionFunctions.install(List.of(new Fn("pair", 2, args -> args)));

        assertThatThrownBy(() -> ExpressionParser.parse("pair(1)"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("pair() takes 2 arguments, got 1");
    }

    @Test
    void installationFailsFastOnBadContributions() {
        assertThatThrownBy(() -> ExpressionFunctions.install(
                List.of(new Fn("coalesce", 2, args -> args))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("shadows a built-in");
        assertThatThrownBy(() -> ExpressionFunctions.install(
                List.of(new Fn("true", 0, args -> args))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("shadows a built-in");
        assertThatThrownBy(() -> ExpressionFunctions.install(
                List.of(new Fn("not a name", 1, args -> args))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("not a legal identifier");
        assertThatThrownBy(() -> ExpressionFunctions.install(
                List.of(new Fn("dup", 1, args -> args), new Fn("dup", 1, args -> args))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("contributed twice");
        // A failed installation leaves the registry unchanged (built-ins only).
        assertThat(ExpressionFunctions.arity("dup")).isNull();
    }

    @Test
    void resetReturnsToTheBuiltinsAndStaleCallsFailClearly() {
        ExpressionFunctions.install(List.of(new Fn("gone", 0, args -> "here")));
        Expr parsed = ExpressionParser.parse("gone()");
        assertThat(parsed.eval(new EvaluationContext(Map.of()))).isEqualTo("here");

        ExpressionFunctions.reset();
        // Parsing now rejects the name again...
        assertThatThrownBy(() -> ExpressionParser.parse("gone()"))
                .isInstanceOf(TqlException.class);
        // ...and a tree parsed before the reset fails loudly instead of guessing.
        assertThatThrownBy(() -> parsed.eval(new EvaluationContext(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer installed");
    }

    @Test
    void builtinsAlwaysWinTheNameLookup() {
        assertThat(ExpressionFunctions.arity("coalesce")).isEqualTo(2);
        assertThat(ExpressionFunctions.custom("coalesce")).isNull();
    }
}
