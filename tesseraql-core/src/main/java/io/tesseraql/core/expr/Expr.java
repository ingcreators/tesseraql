package io.tesseraql.core.expr;

import java.util.List;
import java.util.Objects;

/**
 * Abstract syntax tree for 2-way SQL directive expressions (design ch. 8.1).
 *
 * <p>Expressions are deliberately small: literals, dotted property paths, comparison, equality,
 * logical {@code &&}/{@code ||}/{@code !}, grouping, and — since roadmap Phase 40 — arithmetic
 * ({@code + - * / %}, decimal-exact), string concatenation via {@code +}, and a whitelist of
 * pure functions: the built-ins ({@link Call#FUNCTIONS}) plus any operator-installed
 * {@link ExpressionFunction}s, whose contract is equally side-effect-free. There is still no
 * method invocation, reflection, or assignment, so evaluation cannot trigger side effects
 * (guardrail design ch. 20.6).
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
        public enum Operator {
            AND, OR
        }

        @Override
        public Object eval(EvaluationContext context) {
            boolean leftValue = left.evalBoolean(context);
            if (operator == Operator.AND) {
                return leftValue && right.evalBoolean(context);
            }
            return leftValue || right.evalBoolean(context);
        }
    }

    /**
     * Arithmetic over decimals (roadmap Phase 40): {@code +} concatenates when either side is a
     * string; otherwise both sides must be numbers ({@link java.math.BigDecimal}-exact, so
     * {@code qty * price <= budget} carries no float drift). A {@code null} operand propagates
     * {@code null}, the SQL-friendly reading for optional inputs.
     */
    record Arithmetic(Operator operator, Expr left, Expr right) implements Expr {
        public enum Operator {
            ADD, SUB, MUL, DIV, MOD
        }

        @Override
        public Object eval(EvaluationContext context) {
            Object l = left.eval(context);
            Object r = right.eval(context);
            if (operator == Operator.ADD && (l instanceof String || r instanceof String)) {
                return String.valueOf(l) + String.valueOf(r);
            }
            if (l == null || r == null) {
                return null;
            }
            java.math.BigDecimal a = decimal(l);
            java.math.BigDecimal b = decimal(r);
            return switch (operator) {
                case ADD -> a.add(b);
                case SUB -> a.subtract(b);
                case MUL -> a.multiply(b);
                case DIV -> a.divide(b, java.math.MathContext.DECIMAL64);
                case MOD -> a.remainder(b);
            };
        }

        static java.math.BigDecimal decimal(Object value) {
            if (value instanceof java.math.BigDecimal bd) {
                return bd;
            }
            if (value instanceof Number number) {
                return new java.math.BigDecimal(number.toString());
            }
            throw new IllegalArgumentException("Not a number: " + value);
        }
    }

    /** Arithmetic negation ({@code -x}). */
    record Negate(Expr operand) implements Expr {
        @Override
        public Object eval(EvaluationContext context) {
            Object value = operand.eval(context);
            return value == null ? null : Arithmetic.decimal(value).negate();
        }
    }

    /**
     * A pure function call (roadmap Phase 40). Parse rejects unknown names and wrong arities,
     * so an expression can never reach outside the built-ins —
     * {@code length lower upper trim contains startsWith endsWith matches abs round floor ceil
     * min max coalesce} — plus any operator-installed {@link ExpressionFunction}s (contract:
     * side-effect-free and total, see {@link ExpressionFunctions}).
     */
    record Call(String name, List<Expr> args) implements Expr {

        /** function name → arity. */
        public static final java.util.Map<String, Integer> FUNCTIONS = java.util.Map.ofEntries(
                java.util.Map.entry("length", 1), java.util.Map.entry("lower", 1),
                java.util.Map.entry("upper", 1), java.util.Map.entry("trim", 1),
                java.util.Map.entry("contains", 2), java.util.Map.entry("startsWith", 2),
                java.util.Map.entry("endsWith", 2), java.util.Map.entry("matches", 2),
                java.util.Map.entry("abs", 1), java.util.Map.entry("round", 1),
                java.util.Map.entry("floor", 1), java.util.Map.entry("ceil", 1),
                java.util.Map.entry("min", 2), java.util.Map.entry("max", 2),
                java.util.Map.entry("coalesce", 2));

        private static final java.util.concurrent.ConcurrentHashMap<String, java.util.regex.Pattern> PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();

        public Call {
            args = List.copyOf(args);
        }

        @Override
        public Object eval(EvaluationContext context) {
            if (!FUNCTIONS.containsKey(name)) {
                return evalCustom(context);
            }
            Object a = args.get(0).eval(context);
            Object b = args.size() > 1 ? args.get(1).eval(context) : null;
            return switch (name) {
                case "length" -> a == null ? null : String.valueOf(a).length();
                case "lower" ->
                    a == null ? null : String.valueOf(a).toLowerCase(java.util.Locale.ROOT);
                case "upper" ->
                    a == null ? null : String.valueOf(a).toUpperCase(java.util.Locale.ROOT);
                case "trim" -> a == null ? null : String.valueOf(a).trim();
                case "contains" -> a != null && b != null
                        && String.valueOf(a).contains(String.valueOf(b));
                case "startsWith" -> a != null && b != null
                        && String.valueOf(a).startsWith(String.valueOf(b));
                case "endsWith" -> a != null && b != null
                        && String.valueOf(a).endsWith(String.valueOf(b));
                case "matches" -> a != null && b != null && PATTERNS
                        .computeIfAbsent(String.valueOf(b), java.util.regex.Pattern::compile)
                        .matcher(String.valueOf(a)).matches();
                case "abs" -> a == null ? null : Arithmetic.decimal(a).abs();
                case "round" -> a == null
                        ? null
                        : Arithmetic.decimal(a).setScale(0, java.math.RoundingMode.HALF_UP);
                case "floor" -> a == null
                        ? null
                        : Arithmetic.decimal(a).setScale(0, java.math.RoundingMode.FLOOR);
                case "ceil" -> a == null
                        ? null
                        : Arithmetic.decimal(a).setScale(0, java.math.RoundingMode.CEILING);
                case "min" -> a == null || b == null
                        ? null
                        : Arithmetic.decimal(a).min(Arithmetic.decimal(b));
                case "max" -> a == null || b == null
                        ? null
                        : Arithmetic.decimal(a).max(Arithmetic.decimal(b));
                case "coalesce" -> a != null ? a : b;
                default -> throw new IllegalStateException(name);
            };
        }

        /**
         * Dispatches to the installed {@link ExpressionFunction}. The parser only builds a call
         * for a name known at parse time, so a miss here means the registry changed since —
         * a clear error beats evaluating against the wrong function set.
         */
        private Object evalCustom(EvaluationContext context) {
            ExpressionFunction function = ExpressionFunctions.custom(name);
            if (function == null) {
                throw new IllegalStateException("Expression function '" + name
                        + "' is no longer installed");
            }
            List<Object> values = new java.util.ArrayList<>(args.size());
            for (Expr arg : args) {
                values.add(arg.eval(context));
            }
            return function.apply(java.util.Collections.unmodifiableList(values));
        }
    }

    /** Equality / relational comparison. */
    record Comparison(Operator operator, Expr left, Expr right) implements Expr {
        public enum Operator {
            EQ, NE, LT, GT, LE, GE
        }

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

        private static int compare(Object l, Object r) {
            if (l instanceof Number ln && r instanceof Number rn) {
                return Double.compare(ln.doubleValue(), rn.doubleValue());
            }
            if (l instanceof Comparable<?> && r != null) {
                @SuppressWarnings("unchecked")
                Comparable<Object> lc = (Comparable<Object>) l;
                return lc.compareTo(r);
            }
            throw new IllegalArgumentException("Values are not comparable: " + l + ", " + r);
        }
    }
}
