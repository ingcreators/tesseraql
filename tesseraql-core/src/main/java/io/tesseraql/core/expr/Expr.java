package io.tesseraql.core.expr;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.Objects;

/**
 * Abstract syntax tree for 2-way SQL directive expressions (design ch. 8.1).
 *
 * <p>Expressions are deliberately small: literals, dotted property paths, comparison, equality,
 * logical {@code &&}/{@code ||}/{@code !}, grouping, and — since roadmap Phase 40 — arithmetic
 * ({@code + - * / %}, decimal-exact), string concatenation via {@code +}, and a whitelist of
 * pure functions: the built-ins ({@link Call#FUNCTIONS}) plus any operator-installed
 * {@link ExpressionFunction}s, whose contract is equally side-effect-free. The grammar has no
 * call syntax on a value and no assignment: a dotted path reads a value the way
 * {@link EvaluationContext} says it does, and nothing else is invoked.
 *
 * <p>Evaluation fails as coded (docs/audit-low-leads.md slice 11): an operand an operator
 * cannot evaluate — a relational comparison on a {@code null} or on two values of unrelated
 * kinds, arithmetic on a non-number, a division by zero, a {@code matches()} pattern bound at
 * request time that does not compile — is {@link #UNEVALUABLE_OPERAND}, never a raw
 * {@code IllegalArgumentException} or {@code ClassCastException}. The template is what is
 * defective, not the request, so the code answers 500 the way the empty negated list's
 * refusal does (docs/two-way-sql-parser.md decision 10); the author guards the site
 * ({@code minPrice != null && minPrice > 0}).
 */
public sealed interface Expr {

    /**
     * An operand the expression could not evaluate at request time. The sentence names the
     * operator and the operand kinds, never a value: an operand may be a claim or a row.
     */
    TqlErrorCode UNEVALUABLE_OPERAND = new TqlErrorCode(TqlDomain.SQL, 2122);

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
            try {
                return switch (operator) {
                    case ADD -> a.add(b);
                    case SUB -> a.subtract(b);
                    case MUL -> a.multiply(b);
                    case DIV -> a.divide(b, java.math.MathContext.DECIMAL64);
                    case MOD -> a.remainder(b);
                };
            } catch (ArithmeticException ex) {
                throw refuse(symbol() + ": " + ex.getMessage() + " — guard the divisor"
                        + " (x != 0 && …)");
            }
        }

        private String symbol() {
            return switch (operator) {
                case ADD -> "+";
                case SUB -> "-";
                case MUL -> "*";
                case DIV -> "/";
                case MOD -> "%";
            };
        }

        static java.math.BigDecimal decimal(Object value) {
            if (value instanceof java.math.BigDecimal bd) {
                return bd;
            }
            if (value instanceof Number number && !nonFinite(number)) {
                return new java.math.BigDecimal(number.toString());
            }
            throw refuse("arithmetic needs numbers, and met " + kind(value));
        }

        /** {@code type: number} admits NaN and the infinities, which no decimal can hold. */
        static boolean nonFinite(Number number) {
            return (number instanceof Double d && !Double.isFinite(d))
                    || (number instanceof Float f && !Float.isFinite(f));
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
     * side-effect-free and total, see {@link ExpressionFunctions}). A custom call captures the
     * resolved function at parse, so the tree evaluates with the set it was parsed under —
     * {@code custom} is {@code null} exactly when {@code name} is a built-in.
     */
    record Call(String name, List<Expr> args, ExpressionFunction custom,
            java.util.regex.Pattern regex) implements Expr {

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

        public Call {
            args = List.copyOf(args);
        }

        /** A built-in call; custom calls carry their resolved function. */
        public Call(String name, List<Expr> args) {
            this(name, args, null, null);
        }

        /**
         * A call whose {@code matches()} pattern is not a literal, or a custom call. The parser
         * compiles a literal pattern once and hands it in as {@code regex}: a bad one is its
         * refusal, so lint and the boot see it, and no request compiles it again. A pattern
         * that is not a literal compiles per evaluation and is held nowhere — the cache that
         * keyed on the evaluated text grew by one entry per distinct value a request bound
         * (docs/audit-low-leads.md F121).
         */
        public Call(String name, List<Expr> args, ExpressionFunction custom) {
            this(name, args, custom, null);
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
                case "matches" -> a != null && b != null
                        && (regex != null ? regex : compile(String.valueOf(b)))
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

        private static java.util.regex.Pattern compile(String pattern) {
            try {
                return java.util.regex.Pattern.compile(pattern);
            } catch (java.util.regex.PatternSyntaxException ex) {
                throw refuse("matches(): the pattern bound at request time does not compile ("
                        + ex.getDescription() + " near index " + ex.getIndex() + ")");
            }
        }

        /**
         * Dispatches to the function captured at parse. A miss here means the node was built
         * by hand with the built-in constructor for a non-built-in name — a clear error beats
         * evaluating against the wrong function.
         */
        private Object evalCustom(EvaluationContext context) {
            if (custom == null) {
                throw new IllegalStateException("Expression function '" + name
                        + "' was constructed without its resolved function");
            }
            List<Object> values = new java.util.ArrayList<>(args.size());
            for (Expr arg : args) {
                values.add(arg.eval(context));
            }
            return custom.apply(java.util.Collections.unmodifiableList(values));
        }
    }

    /**
     * Equality / relational comparison. Two numbers compare as decimals, whatever their boxed
     * kinds — {@code 10 == 10.0} holds and two {@code bigint} keys past 2<sup>53</sup> stay
     * apart; {@code ==}/{@code !=} on anything else is {@link Objects#equals}, so {@code null}
     * is equal to itself and to nothing else. A relational operator on a {@code null} or on
     * two values of unrelated kinds is {@link #UNEVALUABLE_OPERAND}: the language propagates
     * {@code null} through arithmetic and answers {@code false} for a predicate, and this is
     * the one place where a silent answer would be either wrong or unknowable (a
     * {@code validate:} rule reading a false as a violation of an optional field), so the
     * site is refused and the guard is named.
     */
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
                return compareNumbers(ln, rn) == 0;
            }
            return Objects.equals(l, r);
        }

        private int compare(Object l, Object r) {
            if (l instanceof Number ln && r instanceof Number rn) {
                return compareNumbers(ln, rn);
            }
            if (l == null || r == null) {
                throw refuse(symbol() + ": " + (l == null ? "the left" : "the right")
                        + " operand is null — guard the site (x != null && …) or declare the"
                        + " input required");
            }
            if (l instanceof Comparable<?> comparable) {
                try {
                    @SuppressWarnings("unchecked")
                    Comparable<Object> lc = (Comparable<Object>) comparable;
                    return lc.compareTo(r);
                } catch (ClassCastException unrelated) {
                    // fall through to the refusal: the kinds are named there
                }
            }
            throw refuse(symbol() + ": " + kind(l) + " and " + kind(r) + " are not comparable"
                    + " — a relational operator takes two numbers, two strings, or two temporal"
                    + " values of one kind");
        }

        /**
         * Decimal-exact, like the arithmetic ({@code compareTo}, so {@code 10 == 10.00}); a
         * non-finite double has no decimal and keeps the IEEE ordering.
         */
        private static int compareNumbers(Number l, Number r) {
            if (Arithmetic.nonFinite(l) || Arithmetic.nonFinite(r)) {
                return Double.compare(l.doubleValue(), r.doubleValue());
            }
            return Arithmetic.decimal(l).compareTo(Arithmetic.decimal(r));
        }

        private String symbol() {
            return switch (operator) {
                case EQ -> "==";
                case NE -> "!=";
                case LT -> "<";
                case GT -> ">";
                case LE -> "<=";
                case GE -> ">=";
            };
        }
    }

    /** The coded refusal every evaluation-time failure is raised as. */
    static TqlException refuse(String sentence) {
        return new TqlException(UNEVALUABLE_OPERAND, sentence);
    }

    /** An operand's kind for a sentence — its class, never its value. */
    static String kind(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
