package io.tesseraql.core.expr;

import java.util.List;

/**
 * An operator-installed custom function for the core expression language (design ch. 8.1) — the
 * lightweight Java hook for a domain check that the built-in whitelist cannot express (a
 * checksum, a code-format rule, a business calendar). One implementation contributes one
 * function, discovered via {@link java.util.ServiceLoader} from the {@code tesseraql.modules} /
 * {@code --modules} classpath and installed into {@link ExpressionFunctions} at command start.
 *
 * <p>Once installed, the function is callable wherever the expression language runs: validation
 * {@code rule:} expressions, 2-way SQL {@code /*%if*&#47;} directives, {@code requiredWhen},
 * notify {@code when:} guards, workflow guards, and response shaping.
 *
 * <p><strong>The purity contract.</strong> The expression language is side-effect-free by design
 * (guardrail design ch. 20.6): evaluation may run on every request, inside validation
 * transactions, from lint, and from the Studio sandbox. An implementation must therefore be
 * <em>total</em> (never throw on expected inputs — return {@code null} or {@code false} for
 * absent/mismatched values, like the built-ins), <em>side-effect-free</em> (no I/O, no state
 * mutation, no network), and fast. Deterministic functions are strongly recommended; a function
 * reading ambient state (such as a clock) must at least be stable enough that re-evaluating an
 * expression within one request cannot change a decision already made.
 *
 * <p>Arguments arrive as the expression engine's runtime values: {@link String},
 * {@link Boolean}, {@link java.math.BigDecimal}/{@link Number}, or {@code null}. The name must
 * be a Java identifier and must not collide with a built-in or another installed function —
 * {@link ExpressionFunctions#install(ClassLoader)} fails fast otherwise (TQL-SQL-2110).
 */
public interface ExpressionFunction {

    /** The function name as written in expressions ({@code isKatakana(body.name)}). */
    String name();

    /** The exact number of arguments; parse rejects any other count, like the built-ins. */
    int arity();

    /**
     * Applies the function to the evaluated arguments ({@code arity()} entries, any of which may
     * be {@code null}).
     */
    Object apply(List<Object> args);
}
