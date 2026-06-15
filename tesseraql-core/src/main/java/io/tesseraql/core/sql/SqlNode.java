package io.tesseraql.core.sql;

import io.tesseraql.core.expr.Expr;
import java.util.List;

/**
 * Parsed node of a 2-way SQL template (design ch. 8.1).
 *
 * <p>A template parses into a flat list of nodes; conditional and loop directives nest child
 * node lists. The {@link SqlRenderer} walks this tree to produce a {@link BoundSql}.
 */
public sealed interface SqlNode {

    /** Literal SQL text emitted verbatim. {@code startLine} is the 1-based source line. */
    record Text(String text, int startLine) implements SqlNode {
    }

    /**
     * A bind site: {@code /* expr *}{@code / dummy}. The dummy literal is dropped and replaced by
     * a single {@code ?} placeholder bound to the value of {@code expression}.
     */
    record Bind(String expressionSource, Expr expression, int sourceLine) implements SqlNode {
    }

    /**
     * An IN-list bind site: {@code /* expr *}{@code / (...)}. The value of {@code expression} must
     * be a collection or array; it expands to {@code (?, ?, ...)} with one parameter per element.
     */
    record ListBind(String expressionSource, Expr expression, int sourceLine) implements SqlNode {
    }

    /** A conditional {@code /*%if *}{@code /} chain with optional elseif/else branches. */
    record If(List<Branch> branches) implements SqlNode {
        public If {
            branches = List.copyOf(branches);
        }

        /**
         * One branch of a conditional. {@code condition} is {@code null} for the {@code else} branch.
         */
        public record Branch(Expr condition, int sourceLine, List<SqlNode> body) {
            public Branch {
                body = List.copyOf(body);
            }
        }
    }

    /**
     * A repeated fragment: {@code /*%for item : items *}{@code / ... /*%end*}{@code /}. An
     * optional separator ({@code /*%for item : items separator ',' *}{@code /}) is emitted
     * between iterations - it lives inside the directive comment, so the raw template stays a
     * single, SQL-tool-runnable element (e.g. a multi-row {@code INSERT ... VALUES} list).
     * The loop exposes {@code <item>_index} (0-based) alongside {@code <item>}.
     */
    record For(String itemVar, String listExpressionSource, Expr listExpression, String separator,
            int sourceLine, List<SqlNode> body) implements SqlNode {
        public For {
            body = List.copyOf(body);
        }
    }

    /**
     * A row-level scope injection site: {@code /*%scope name on alias *}{@code / (1=1)} (roadmap
     * Phase 29 — organizational data scoping). The parenthesized dummy keeps the template runnable
     * in a plain SQL tool; at render time a {@link ScopeResolver} replaces it with a predicate
     * derived from the request principal, parameterized. {@code alias} (from {@code on <alias>})
     * qualifies the scoped column for a multi-table query; it is {@code null} for a single table.
     */
    record Scope(String name, String alias, int sourceLine) implements SqlNode {
    }
}
