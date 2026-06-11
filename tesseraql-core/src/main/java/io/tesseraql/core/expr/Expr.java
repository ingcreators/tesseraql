package io.tesseraql.core.expr;

import java.util.List;
import java.util.Objects;

/**
 * Abstract syntax tree for 2-way SQL directive expressions (design ch. 8.1).
 *
 * <p>Expressions are deliberately small: literals, dotted property paths, comparison, equality,
 * logical {@code &&}/{@code ||}/{@code !}, and grouping. There is no method invocation or
 * assignment, so evaluation cannot trigger side effects (guardrail design ch. 20.6).
 */
public sealed interface Expr {

    /** Evaluates this expression against the given variable scope. */
    Object eval(EvaluationContext context);

    /** Evaluates this expression and coerces the result to a boolean (design ch. 8.1). */
    default boolean evalBoolean(EvaluationContext context) {
        return truthy(eval(context));
    }

    /** Coerces a value to boolean: {@code null} is false, {@link Boolean} as-is, else true. */
    static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return true;
    }

    /** A literal value: string, number, boolean, or {@code null}. */
    record Literal(Object value) implements Expr {
        @Override
        public Object eval(EvaluationContext context) {
            return value;
        }
    }

    /** A dotted property path such as {@code q} or {@code principal.claim.tenant_id}. */
    record Path(List<String> segments) implements Expr {
        public Path {
            segments = List.copyOf(segments);
        }

        @Override
        public Object eval(EvaluationContext context) {
            return context.resolve(segments);
        }
    }

    /** Logical negation. */
    record Not(Expr operand) implements Expr {
        @Override
        public Object eval(EvaluationContext context) {
            return !operand.evalBoolean(context);
        }
    }

    /** Short-circuiting logical {@code &&} / {@code ||}. */
    record Logical(Operator operator, Expr left, Expr right) implements Expr {
        public enum Operator { AND, OR }

        @Override
        public Object eval(EvaluationContext context) {
            boolean leftValue = left.evalBoolean(context);
            if (operator == Operator.AND) {
                return leftValue && right.evalBoolean(context);
            }
            return leftValue || right.evalBoolean(context);
        }
    }

    /** Equality / relational comparison. */
    record Comparison(Operator operator, Expr left, Expr right) implements Expr {
        public enum Operator { EQ, NE, LT, GT, LE, GE }

        @Override
        public Object eval(EvaluationContext context) {
            Object l = left.eval(context);
            Object r = right.eval(context);
            return switch (operator) {
                case EQ -> equalValues(l, r);
                case NE -> !equalValues(l, r);
                case LT -> compare(l, r) < 0;
                case GT -> compare(l, r) > 0;
                case LE -> compare(l, r) <= 0;
                case GE -> compare(l, r) >= 0;
            };
        }

        private static boolean equalValues(Object l, Object r) {
            if (l instanceof Number ln && r instanceof Number rn) {
                return Double.compare(ln.doubleValue(), rn.doubleValue()) == 0;
            }
            return Objects.equals(l, r);
        }

        @SuppressWarnings("unchecked")
        private static int compare(Object l, Object r) {
            if (l instanceof Number ln && r instanceof Number rn) {
                return Double.compare(ln.doubleValue(), rn.doubleValue());
            }
            if (l instanceof Comparable lc && r != null) {
                return lc.compareTo(r);
            }
            throw new IllegalArgumentException("Values are not comparable: " + l + ", " + r);
        }
    }
}
