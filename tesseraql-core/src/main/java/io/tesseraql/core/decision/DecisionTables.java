package io.tesseraql.core.decision;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.expr.Expr;
import io.tesseraql.core.expr.ExpressionParser;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A route's compiled {@code decide:} block (docs/decision-tables.md): named decision tables
 * turning a combination of typed input conditions into declared output values, evaluated once
 * per operation and published under the {@code decision.*} namespace.
 *
 * <p>A row is the <em>conjunction</em> of its cells; alternatives are separate rows resolved in
 * authored order. Cells are comparisons — equality, an inclusive range, membership in a small
 * fixed set, a boolean — never expressions, so overlap between rows stays computable and a
 * table-backed source (a later slice) can evaluate the same semantics as one generated SELECT.
 * A derivation ("the caller holds the officer role") belongs in the {@code decide:} wiring,
 * which is a whitelist expression evaluated against the request context.
 *
 * <p>There is no silent null: a lookup that no row matches is {@code TQL-DECISION-4721} unless
 * the decision declares a default, and a {@code hitPolicy: unique} lookup matched by more than
 * one conditional row is {@code TQL-DECISION-4720}.
 */
public final class DecisionTables {

    /** TQL-DECISION-4702: the decision's contract is malformed (inputs/outputs/policies). */
    private static final TqlErrorCode CONTRACT = new TqlErrorCode(TqlDomain.DECISION, 4702);
    /** TQL-DECISION-4703: a row is malformed (unknown cell, bad literal, wrong outputs). */
    private static final TqlErrorCode ROW = new TqlErrorCode(TqlDomain.DECISION, 4703);
    /** TQL-DECISION-4704: the miss policy disagrees with the declared rows. */
    private static final TqlErrorCode MISS_POLICY = new TqlErrorCode(TqlDomain.DECISION, 4704);
    /** TQL-DECISION-4714: two rows of a unique decision can match the same inputs. */
    private static final TqlErrorCode OVERLAP = new TqlErrorCode(TqlDomain.DECISION, 4714);
    /** TQL-DECISION-4720: a unique decision matched more than one row at runtime. */
    private static final TqlErrorCode MULTI_HIT = new TqlErrorCode(TqlDomain.DECISION, 4720);
    /** TQL-DECISION-4721: no row matched and the decision declares no default. */
    private static final TqlErrorCode MISS = new TqlErrorCode(TqlDomain.DECISION, 4721);
    /** TQL-DECISION-4723: the generated lookup of a table-backed decision failed. */
    private static final TqlErrorCode LOOKUP_FAILED = new TqlErrorCode(TqlDomain.DECISION, 4723);

    /** How one input's cells compare (docs/decision-tables.md "The condition model"). */
    public enum MatchKind {
        EQ, BETWEEN, IN, BOOL, ORG_SUBTREE;

        /** Parses a declared {@code match:} keyword; null/blank defaults to {@code eq}. */
        public static MatchKind parse(String declared, String decision, String input) {
            if (declared == null || declared.isBlank()) {
                return EQ;
            }
            return switch (declared) {
                case "eq" -> EQ;
                case "between" -> BETWEEN;
                case "in" -> IN;
                case "bool" -> BOOL;
                case "subtree" -> ORG_SUBTREE;
                default -> throw new TqlException(CONTRACT, "Decision '" + decision
                        + "' input '" + input + "': unknown match kind '" + declared
                        + "' — one of eq, between, in, bool, subtree");
            };
        }
    }

    /**
     * One compiled cell: a computable comparison over one input. Wildcards are the absence of
     * a cell, so a condition always constrains.
     */
    public sealed interface Condition {

        boolean matches(Object value);

        /** Whether some input value satisfies both conditions — the overlap-lint primitive. */
        boolean intersects(Condition other);

        /** Whether every value this condition accepts, the other accepts too. */
        boolean containedIn(Condition other);

        record Eq(Object value) implements Condition {
            @Override
            public boolean matches(Object candidate) {
                return sameValue(value, candidate);
            }

            @Override
            public boolean intersects(Condition other) {
                return other.matches(value);
            }

            @Override
            public boolean containedIn(Condition other) {
                return other.matches(value);
            }
        }

        /** An inclusive range; a null end is open. At least one end is set. */
        record Range(BigDecimal min, BigDecimal max) implements Condition {
            @Override
            public boolean matches(Object candidate) {
                BigDecimal number = toNumber(candidate);
                if (number == null) {
                    return false;
                }
                return (min == null || number.compareTo(min) >= 0)
                        && (max == null || number.compareTo(max) <= 0);
            }

            @Override
            public boolean intersects(Condition other) {
                return switch (other) {
                    case Range range -> (min == null || range.max() == null
                            || range.max().compareTo(min) >= 0)
                            && (max == null || range.min() == null
                                    || range.min().compareTo(max) <= 0);
                    default -> other.intersects(this);
                };
            }

            @Override
            public boolean containedIn(Condition other) {
                return switch (other) {
                    case Range range -> (range.min() == null
                            || (min != null && min.compareTo(range.min()) >= 0))
                            && (range.max() == null
                                    || (max != null && max.compareTo(range.max()) <= 0));
                    default -> false;
                };
            }
        }

        record InSet(Set<Object> values) implements Condition {
            @Override
            public boolean matches(Object candidate) {
                return values.stream().anyMatch(value -> sameValue(value, candidate));
            }

            @Override
            public boolean intersects(Condition other) {
                return values.stream().anyMatch(other::matches);
            }

            @Override
            public boolean containedIn(Condition other) {
                return values.stream().allMatch(other::matches);
            }
        }
    }

    /** One compiled conditional row: cells by input name (absent = wildcard) and its outputs. */
    public record Row(Map<String, Condition> cells, Map<String, Object> out) {

        boolean matches(Map<String, Object> inputs) {
            return cells.entrySet().stream()
                    .allMatch(cell -> cell.getValue().matches(inputs.get(cell.getKey())));
        }

        /** Whether some input combination can match both rows (wildcards intersect anything). */
        boolean intersects(Row other) {
            for (Map.Entry<String, Condition> cell : cells.entrySet()) {
                Condition theirs = other.cells().get(cell.getKey());
                if (theirs != null && !cell.getValue().intersects(theirs)) {
                    return false;
                }
            }
            return true;
        }

        /** Whether every input combination this row matches, the other row matches too. */
        public boolean containedIn(Row other) {
            for (Map.Entry<String, Condition> cell : other.cells().entrySet()) {
                Condition ours = cells.get(cell.getKey());
                if (ours == null || !ours.containedIn(cell.getValue())) {
                    return false;
                }
            }
            return true;
        }
    }

    /** One authored row before compilation: the YAML {@code when:}/{@code out:} pair. */
    public record RowSpec(Map<String, Object> when, Map<String, Object> out) {
        public RowSpec {
            when = when == null ? Map.of() : Map.copyOf(when);
            out = out == null ? Map.of() : Map.copyOf(out);
        }
    }

    /**
     * One compiled decision table: the contract plus its compiled rows. {@code defaultOut} is
     * the trailing when-less row's outputs (or null); it never counts toward a unique
     * decision's hits — it answers only when nothing else does.
     */
    public record Table(String name, Map<String, MatchKind> inputs, List<String> outputs,
            boolean unique, Map<String, Object> defaultOut, List<Row> rows) {

        /** Evaluates the table against resolved input values. */
        public Map<String, Object> evaluate(Map<String, Object> inputValues) {
            List<Row> hits = new ArrayList<>();
            for (Row row : rows) {
                if (row.matches(inputValues)) {
                    if (!unique) {
                        return row.out();
                    }
                    hits.add(row);
                }
            }
            if (hits.size() > 1) {
                throw new TqlException(MULTI_HIT, "Decision '" + name + "' (hitPolicy: unique)"
                        + " matched " + hits.size() + " rows for inputs " + inputValues
                        + " — the rows' write-time integrity checks should have prevented this");
            }
            if (hits.size() == 1) {
                return hits.get(0).out();
            }
            if (defaultOut != null) {
                return defaultOut;
            }
            throw new TqlException(MISS, "Decision '" + name + "' matched no row for inputs "
                    + inputValues + " and declares no default — a decision never resolves to"
                    + " silent nulls");
        }
    }

    /**
     * Compiles one decision's contract and rows, raising {@code TQL-DECISION-4702..4704} on a
     * malformed declaration and {@code TQL-DECISION-4714} when two rows of a unique decision
     * can match the same inputs. The manifest loader compiles every declared decision at load
     * so these fail the build, not the first request.
     */
    public static Table table(String name, Map<String, String> declaredInputs,
            List<String> outputNames, String hitPolicy, String onMiss,
            List<RowSpec> declaredRows) {
        if (declaredInputs == null || declaredInputs.isEmpty()) {
            throw new TqlException(CONTRACT, "Decision '" + name + "' declares no inputs");
        }
        if (outputNames == null || outputNames.isEmpty()) {
            throw new TqlException(CONTRACT, "Decision '" + name + "' declares no outputs");
        }
        boolean unique = switch (hitPolicy == null || hitPolicy.isBlank() ? "first" : hitPolicy) {
            case "first" -> false;
            case "unique" -> true;
            default -> throw new TqlException(CONTRACT, "Decision '" + name
                    + "': unknown hitPolicy '" + hitPolicy + "' — first or unique");
        };
        if (onMiss != null && !onMiss.isBlank() && !"error".equals(onMiss)
                && !"default".equals(onMiss)) {
            throw new TqlException(CONTRACT, "Decision '" + name + "': unknown onMiss '"
                    + onMiss + "' — error or default");
        }
        Map<String, MatchKind> inputs = new LinkedHashMap<>();
        declaredInputs.forEach(
                (input, match) -> inputs.put(input, MatchKind.parse(match, name, input)));
        if (declaredRows == null || declaredRows.isEmpty()) {
            throw new TqlException(CONTRACT, "Decision '" + name + "' declares no rows");
        }
        List<Row> rows = new ArrayList<>();
        Map<String, Object> defaultOut = null;
        for (int i = 0; i < declaredRows.size(); i++) {
            RowSpec spec = declaredRows.get(i);
            checkOutputs(name, i, spec.out(), outputNames);
            if (spec.when().isEmpty()) {
                if (defaultOut != null) {
                    throw new TqlException(ROW, "Decision '" + name + "' declares two default"
                            + " rows (rows without when:) — at most one answers a miss");
                }
                if (i != declaredRows.size() - 1) {
                    throw new TqlException(ROW, "Decision '" + name + "' row " + (i + 1)
                            + " has no when: but is not last — a default row shadows every row"
                            + " after it");
                }
                defaultOut = spec.out();
                continue;
            }
            Map<String, Condition> cells = new LinkedHashMap<>();
            spec.when().forEach((input, literal) -> {
                MatchKind kind = inputs.get(input);
                if (kind == null) {
                    throw new TqlException(ROW, "Decision '" + name + "' row cell '" + input
                            + "' names no declared input " + inputs.keySet());
                }
                cells.put(input, condition(name, input, kind, literal));
            });
            rows.add(new Row(Map.copyOf(cells), spec.out()));
        }
        if ("default".equals(onMiss) && defaultOut == null) {
            throw new TqlException(MISS_POLICY, "Decision '" + name + "' declares onMiss:"
                    + " default but no default row — add a trailing row without when:");
        }
        if (unique) {
            for (int i = 0; i < rows.size(); i++) {
                for (int j = i + 1; j < rows.size(); j++) {
                    if (rows.get(i).intersects(rows.get(j))) {
                        throw new TqlException(OVERLAP, "Decision '" + name + "' (hitPolicy:"
                                + " unique) rows " + (i + 1) + " and " + (j + 1)
                                + " can match the same inputs — tighten the cells or use"
                                + " hitPolicy: first");
                    }
                }
            }
        }
        return new Table(name, Map.copyOf(inputs), List.copyOf(outputNames), unique, defaultOut,
                List.copyOf(rows));
    }

    private static void checkOutputs(String name, int row, Map<String, Object> out,
            List<String> outputNames) {
        if (!out.keySet().equals(new LinkedHashSet<>(outputNames))) {
            throw new TqlException(ROW, "Decision '" + name + "' row " + (row + 1)
                    + " must set exactly the outputs " + outputNames + ", not " + out.keySet());
        }
    }

    /** Compiles one cell literal into its condition, per the input's declared match kind. */
    private static Condition condition(String decision, String input, MatchKind kind,
            Object literal) {
        return switch (kind) {
            case EQ -> new Condition.Eq(scalar(decision, input, literal));
            case BOOL -> {
                if (!(literal instanceof Boolean flag)) {
                    throw new TqlException(ROW, "Decision '" + decision + "' cell '" + input
                            + "': a bool cell is true or false, not '" + literal + "'");
                }
                yield new Condition.Eq(flag);
            }
            case IN -> {
                if (!(literal instanceof List<?> values) || values.isEmpty()) {
                    throw new TqlException(ROW, "Decision '" + decision + "' cell '" + input
                            + "': an in cell is a non-empty list, not '" + literal + "'");
                }
                Set<Object> set = new LinkedHashSet<>();
                values.forEach(value -> set.add(scalar(decision, input, value)));
                yield new Condition.InSet(Set.copyOf(set));
            }
            case BETWEEN -> range(decision, input, literal);
            // Subtree membership needs the org closure, which lives in the database: a
            // YAML-backed decision cannot answer it in memory, so the loader restricts the
            // kind to table sources and this arm is unreachable from a valid declaration.
            case ORG_SUBTREE -> throw new TqlException(ROW, "Decision '" + decision + "' cell '"
                    + input + "': a subtree input needs a table source"
                    + " (docs/decision-tables.md)");
        };
    }

    private static Object scalar(String decision, String input, Object literal) {
        if (literal == null || literal instanceof Map || literal instanceof List) {
            throw new TqlException(ROW, "Decision '" + decision + "' cell '" + input
                    + "': expected a scalar, not '" + literal + "'");
        }
        return literal;
    }

    /**
     * Parses a range cell: a plain number (an exact point), {@code a..b} (inclusive both), or
     * one comparator {@code >= n}, {@code > n}, {@code <= n}, {@code < n}. Exclusive ends
     * compile to the nearest representable inclusive bound by scale, keeping the condition a
     * closed interval the overlap check can reason about.
     */
    private static Condition range(String decision, String input, Object literal) {
        if (literal instanceof Number number) {
            BigDecimal point = new BigDecimal(number.toString());
            return new Condition.Range(point, point);
        }
        String text = literal instanceof String s ? s.trim() : null;
        if (text == null || text.isEmpty()) {
            throw new TqlException(ROW, badRange(decision, input, literal));
        }
        try {
            if (text.contains("..")) {
                String[] ends = text.split("\\.\\.", -1);
                if (ends.length != 2 || ends[0].isBlank() || ends[1].isBlank()) {
                    throw new TqlException(ROW, badRange(decision, input, literal));
                }
                BigDecimal min = new BigDecimal(ends[0].trim());
                BigDecimal max = new BigDecimal(ends[1].trim());
                if (min.compareTo(max) > 0) {
                    throw new TqlException(ROW, "Decision '" + decision + "' cell '" + input
                            + "': range '" + text + "' is inverted");
                }
                return new Condition.Range(min, max);
            }
            if (text.startsWith(">=")) {
                return new Condition.Range(new BigDecimal(text.substring(2).trim()), null);
            }
            if (text.startsWith("<=")) {
                return new Condition.Range(null, new BigDecimal(text.substring(2).trim()));
            }
            if (text.startsWith(">")) {
                return new Condition.Range(
                        exclusive(new BigDecimal(text.substring(1).trim()), true), null);
            }
            if (text.startsWith("<")) {
                return new Condition.Range(null,
                        exclusive(new BigDecimal(text.substring(1).trim()), false));
            }
            BigDecimal point = new BigDecimal(text);
            return new Condition.Range(point, point);
        } catch (NumberFormatException ex) {
            throw new TqlException(ROW, badRange(decision, input, literal));
        }
    }

    private static String badRange(String decision, String input, Object literal) {
        return "Decision '" + decision + "' cell '" + input + "': a between cell is a number,"
                + " 'a..b', or one comparator ('>= n', '> n', '<= n', '< n'), not '" + literal
                + "'";
    }

    /** The neighbouring inclusive bound of an exclusive end, one unit in the bound's own scale. */
    private static BigDecimal exclusive(BigDecimal bound, boolean lower) {
        BigDecimal ulp = BigDecimal.ONE.movePointLeft(bound.scale());
        return lower ? bound.add(ulp) : bound.subtract(ulp);
    }

    private static boolean sameValue(Object cell, Object candidate) {
        if (candidate == null) {
            return false;
        }
        BigDecimal left = toNumber(cell);
        BigDecimal right = toNumber(candidate);
        if (left != null && right != null) {
            return left.compareTo(right) == 0;
        }
        if (cell instanceof Boolean || candidate instanceof Boolean) {
            return cell.equals(candidate);
        }
        return String.valueOf(cell).equals(String.valueOf(candidate));
    }

    private static BigDecimal toNumber(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return null;
    }

    /**
     * One compiled table-backed decision (docs/decision-tables.md "Two sources, one
     * contract"): the generated SELECT — each mapped column contributing a
     * {@code (col IS NULL OR col ⟨op⟩ ?)} arm, ordinary loggable SQL — plus its bind plan.
     *
     * @param binds bind slots in placeholder order: an input name, {@link #EFFECTIVE_AT}, or
     *              {@link #LIMIT}
     */
    public record TableSource(String name, String sql, List<String> binds,
            List<String> outputs, boolean unique, Map<String, Object> defaultOut) {

        /** The bind slot carrying the dated-row reference instant. */
        public static final String EFFECTIVE_AT = "@effectiveAt";
        /** The bind slot carrying the row-limit of a {@code hitPolicy: first} lookup. */
        public static final String LIMIT = "@limit";

        /** Runs the generated lookup on the operation's connection, in its transaction. */
        public Map<String, Object> evaluate(java.sql.Connection connection,
                Map<String, Object> inputValues, Object effectiveAt, int timeoutSeconds) {
            java.util.List<Map<String, Object>> hits = new ArrayList<>();
            try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
                if (timeoutSeconds > 0) {
                    statement.setQueryTimeout(timeoutSeconds);
                }
                int index = 1;
                for (String bind : binds) {
                    statement.setObject(index++, switch (bind) {
                        case EFFECTIVE_AT -> effectiveAt;
                        case LIMIT -> 1;
                        default -> inputValues.get(bind);
                    });
                }
                try (java.sql.ResultSet results = statement.executeQuery()) {
                    while (results.next() && hits.size() < 2) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (String output : outputs) {
                            row.put(output, results.getObject(output));
                        }
                        hits.add(row);
                    }
                }
            } catch (java.sql.SQLException ex) {
                throw TqlException.builder(LOOKUP_FAILED)
                        .message("Decision '" + name + "': the generated lookup failed: "
                                + ex.getMessage())
                        .cause(ex)
                        .build();
            }
            if (unique && hits.size() > 1) {
                throw new TqlException(MULTI_HIT, "Decision '" + name + "' (hitPolicy: unique)"
                        + " matched more than one row for inputs " + inputValues + " — the"
                        + " maintenance routes' write-time integrity checks should have"
                        + " prevented this");
            }
            if (!hits.isEmpty()) {
                return hits.get(0);
            }
            if (defaultOut != null) {
                return defaultOut;
            }
            throw new TqlException(MISS, "Decision '" + name + "' matched no row for inputs "
                    + inputValues + " and declares no default — a decision never resolves to"
                    + " silent nulls");
        }
    }

    /**
     * One compiled {@code decide:} entry: the decision — exactly one of the in-memory
     * {@code table} (YAML rows) or the {@code source} lookup (a table source) — plus this
     * operation's wiring, each input resolved by a whitelist expression against the request
     * context, and the dated-lookup instant ({@code effectiveAt:}, default {@code audit.now}).
     */
    public record Use(String alias, Table table, TableSource source, Map<String, Expr> wiring,
            Expr effectiveAt) {
    }

    /** Compiles one YAML-rows reference's wiring; root-checked at manifest load. */
    public static Use use(String alias, Table table, Map<String, String> wiring) {
        return new Use(alias, table, null, compileWiring(wiring), null);
    }

    /** Compiles one table-source reference's wiring and {@code effectiveAt:}. */
    public static Use use(String alias, TableSource source, Map<String, String> wiring,
            String effectiveAt) {
        return new Use(alias, null, source, compileWiring(wiring), ExpressionParser
                .parse(effectiveAt == null || effectiveAt.isBlank() ? "audit.now" : effectiveAt));
    }

    private static Map<String, Expr> compileWiring(Map<String, String> wiring) {
        Map<String, Expr> compiled = new LinkedHashMap<>();
        wiring.forEach((input, expression) -> compiled.put(input,
                ExpressionParser.parse(expression)));
        return Map.copyOf(compiled);
    }

    private final List<Use> uses;

    public DecisionTables(List<Use> uses) {
        this.uses = List.copyOf(uses);
    }

    public boolean isEmpty() {
        return uses.isEmpty();
    }

    /** Evaluates a {@code decide:} block that declares no table-backed decision. */
    public Map<String, Map<String, Object>> evaluate(Map<String, Object> context) {
        return evaluate(context, null, 0);
    }

    /**
     * Evaluates every declared decision against the request context, in authored order, and
     * returns the {@code decision.*} namespace: outputs by decision alias. Table-backed
     * decisions run their generated SELECT on the given connection — the operation's own
     * transaction, so a rate row committed by an earlier request is visible and the lookup
     * rides the command's isolation.
     */
    public Map<String, Map<String, Object>> evaluate(Map<String, Object> context,
            java.sql.Connection connection, int timeoutSeconds) {
        Map<String, Map<String, Object>> decisions = new LinkedHashMap<>();
        EvaluationContext evaluation = new EvaluationContext(context);
        for (Use use : uses) {
            Map<String, Object> inputs = new LinkedHashMap<>();
            use.wiring().forEach((input, expr) -> inputs.put(input, expr.eval(evaluation)));
            if (use.table() != null) {
                decisions.put(use.alias(), use.table().evaluate(inputs));
                continue;
            }
            if (connection == null) {
                throw new TqlException(LOOKUP_FAILED, "Decision '" + use.source().name()
                        + "' is table-backed and needs the operation's connection");
            }
            decisions.put(use.alias(), use.source().evaluate(connection, inputs,
                    use.effectiveAt().eval(evaluation), timeoutSeconds));
        }
        return decisions;
    }
}
