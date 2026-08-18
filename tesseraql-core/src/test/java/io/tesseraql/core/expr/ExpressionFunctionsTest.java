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
        // A failed installation leaves the process default unchanged (built-ins only).
        assertThat(ExpressionFunctions.processDefault().arity("dup")).isNull();
    }

    @Test
    void aParsedTreeKeepsTheFunctionsItWasParsedUnder() {
        // Flipped by design (docs/module-scope.md): this test used to pin the opposite — a tree
        // failing "no longer installed" after reset(). Calls now capture their resolved function
        // at parse, so the set a tree evaluates with is the set it was parsed under, immune to
        // later installs and resets on any thread.
        ExpressionFunctions.install(List.of(new Fn("kept", 0, args -> "first")));
        Expr parsed = ExpressionParser.parse("kept()");
        assertThat(parsed.eval(new EvaluationContext(Map.of()))).isEqualTo("first");

        ExpressionFunctions.install(List.of(new Fn("kept", 0, args -> "second")));
        assertThat(parsed.eval(new EvaluationContext(Map.of()))).isEqualTo("first");

        ExpressionFunctions.reset();
        // Parsing rejects the name again...
        assertThatThrownBy(() -> ExpressionParser.parse("kept()"))
                .isInstanceOf(TqlException.class);
        // ...while the captured tree still answers.
        assertThat(parsed.eval(new EvaluationContext(Map.of()))).isEqualTo("first");
    }

    @Test
    void anExplicitSetOutranksTheProcessDefault() {
        ExpressionFunctions.install(List.of(new Fn("mine", 0, args -> "default")));
        ExpressionFunctions neighbour = ExpressionFunctions.of(
                List.of(new Fn("theirs", 0, args -> "explicit")));

        assertThat(ExpressionParser.parse("theirs()", neighbour)
                .eval(new EvaluationContext(Map.of()))).isEqualTo("explicit");
        // The explicit set does not see the default's functions, and vice versa.
        assertThatThrownBy(() -> ExpressionParser.parse("mine()", neighbour))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Unknown function 'mine()'");
        assertThatThrownBy(() -> ExpressionParser.parse("theirs()"))
                .isInstanceOf(TqlException.class);
    }

    @Test
    void aHandBuiltCustomCallWithoutItsFunctionFailsClearly() {
        assertThatThrownBy(() -> new Expr.Call("ghost", List.of())
                .eval(new EvaluationContext(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without its resolved function");
    }

    @Test
    void builtinsAlwaysWinTheNameLookup() {
        assertThat(ExpressionFunctions.builtInsOnly().arity("coalesce")).isEqualTo(2);
        assertThat(ExpressionFunctions.builtInsOnly().custom("coalesce")).isNull();
    }
}
