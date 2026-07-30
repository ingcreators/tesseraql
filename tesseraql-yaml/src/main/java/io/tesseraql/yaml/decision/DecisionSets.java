package io.tesseraql.yaml.decision;

import io.tesseraql.core.decision.DecisionTables;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.Expr;
import io.tesseraql.core.expr.ExpressionParser;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.model.DecisionUse;
import io.tesseraql.yaml.model.DecisionsDocument;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The app's shared decision tables (docs/decision-tables.md): named, value-producing decisions
 * declared once under {@code decisions/}, referenced from any command's {@code decide:} block
 * via {@code use:}. The manifest loader resolves references at load time — the
 * validation-rule-sets line — so the compiler builds the runtime tables from the route alone.
 *
 * <p>Every declared decision is compiled here through
 * {@link DecisionTables#table(String, Map, java.util.List, String, String, java.util.List)},
 * so a malformed contract, a bad cell literal, an inconsistent miss policy, or two overlapping
 * rows of a unique decision ({@code TQL-DECISION-4702..4704, 4714}) fail the build, not the
 * first request that consults the table.
 */
public final class DecisionSets {

    /** TQL-DECISION-4701: a decision name is declared twice across decisions documents. */
    private static final TqlErrorCode DUPLICATE = new TqlErrorCode(TqlDomain.DECISION, 4701);
    /** TQL-DECISION-4705: a decide: entry references an unknown decision. */
    private static final TqlErrorCode UNKNOWN = new TqlErrorCode(TqlDomain.DECISION, 4705);
    /** TQL-DECISION-4706: a decide: entry's params do not wire the inputs exactly. */
    private static final TqlErrorCode CONTRACT = new TqlErrorCode(TqlDomain.DECISION, 4706);
    /** TQL-DECISION-4707: a wiring expression reads a namespace absent at decision time. */
    private static final TqlErrorCode WIRING_ROOT = new TqlErrorCode(TqlDomain.DECISION, 4707);
    /** TQL-DECISION-4708: a row value violates its declared type or enum value space. */
    private static final TqlErrorCode VALUE = new TqlErrorCode(TqlDomain.DECISION, 4708);

    /**
     * The context roots a wiring expression may read: what {@code RequestBinder} has bound
     * before the transaction opens. {@code document}, {@code steps}, and {@code audit} appear
     * later in the pipeline, and {@code decision} itself is the output namespace — reading any
     * of them would silently resolve null and match only wildcards, so the load rejects the
     * roots the runtime cannot honour.
     */
    private static final Set<String> WIRING_ROOTS = Set.of("params", "query", "body", "path",
            "principal", "tenant", "flags", "request", "preference");

    private final Map<String, DecisionsDocument.Decision> decisions;

    private DecisionSets(Map<String, DecisionsDocument.Decision> decisions) {
        this.decisions = java.util.Collections.unmodifiableMap(decisions);
    }

    /**
     * Loads every {@code decisions/*.yml} under the app home; duplicate names fail the load.
     * A {@code domain:} on an input or output resolves against the app's field domains here —
     * the declared type and value space merge into the stored decision, so every downstream
     * consumer (the compiler, the row checks, later the exhaustiveness lints) sees effective
     * values, the field-domains line.
     */
    public static DecisionSets load(Path appHome, SimpleYamlParser parser) {
        Path dir = appHome.resolve("decisions");
        Map<String, DecisionsDocument.Decision> decisions = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            return new DecisionSets(decisions);
        }
        io.tesseraql.yaml.domain.FieldDomains domains = io.tesseraql.yaml.domain.FieldDomains
                .load(appHome);
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(file -> file.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .forEach(file -> parser.parseDecisions(file).decisions()
                            .forEach((name, decision) -> {
                                DecisionsDocument.Decision merged = mergeDomains(name, decision,
                                        domains, file);
                                if (decisions.putIfAbsent(name, merged) != null) {
                                    throw new TqlException(DUPLICATE, "Decision '" + name
                                            + "' is declared twice (second: " + file + ")");
                                }
                            }));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        decisions.forEach(DecisionSets::compile);
        return new DecisionSets(decisions);
    }

    /** The decision with each {@code domain:} reference's type and value space merged in. */
    private static DecisionsDocument.Decision mergeDomains(String name,
            DecisionsDocument.Decision decision, io.tesseraql.yaml.domain.FieldDomains domains,
            Path file) {
        Map<String, DecisionsDocument.Input> inputs = new LinkedHashMap<>();
        decision.inputs().forEach((input, spec) -> {
            if (spec.domain() == null) {
                inputs.put(input, spec);
                return;
            }
            io.tesseraql.yaml.model.InputField field = domains.require(spec.domain(),
                    file + " (decision '" + name + "' input '" + input + "')");
            inputs.put(input, new DecisionsDocument.Input(
                    spec.type() != null ? spec.type() : field.type(), spec.domain(),
                    spec.match()));
        });
        Map<String, DecisionsDocument.Output> outputs = new LinkedHashMap<>();
        decision.outputs().forEach((output, spec) -> {
            if (spec.domain() == null) {
                outputs.put(output, spec);
                return;
            }
            io.tesseraql.yaml.model.InputField field = domains.require(spec.domain(),
                    file + " (decision '" + name + "' output '" + output + "')");
            outputs.put(output, new DecisionsDocument.Output(
                    spec.type() != null ? spec.type() : field.type(), spec.domain(),
                    spec.allowed().isEmpty() && field.enumValues() != null
                            ? List.copyOf(field.enumValues())
                            : spec.allowed()));
        });
        return new DecisionsDocument.Decision(inputs, outputs, decision.hitPolicy(),
                decision.onMiss(), decision.rows());
    }

    /**
     * Compiles one declared decision into its runtime table — the load-time check and the
     * compiler's build are the same code path, so they cannot disagree. Beyond the structural
     * compile, the declared types and value spaces are enforced against every row: an {@code
     * eq}/{@code in} cell literal must fit its input's type, and an output value must fit its
     * output's type and declared {@code enum} — the typo in a routing row dies at build, not
     * in the branch nobody tested.
     */
    public static DecisionTables.Table compile(String name,
            DecisionsDocument.Decision decision) {
        Map<String, String> inputs = new LinkedHashMap<>();
        decision.inputs().forEach((input, spec) -> inputs.put(input, spec.match()));
        DecisionTables.Table table = DecisionTables.table(name, inputs,
                java.util.List.copyOf(decision.outputs().keySet()), decision.hitPolicy(),
                decision.onMiss(),
                decision.rows().stream()
                        .map(row -> new DecisionTables.RowSpec(row.when(), row.out()))
                        .toList());
        decision.rows().forEach(row -> checkRowValues(name, decision, row));
        return table;
    }

    private static void checkRowValues(String name, DecisionsDocument.Decision decision,
            DecisionsDocument.Row row) {
        row.when().forEach((input, literal) -> {
            DecisionsDocument.Input spec = decision.inputs().get(input);
            if (spec == null || spec.type() == null) {
                return;
            }
            java.util.List<?> values = literal instanceof java.util.List<?> list
                    ? list
                    : List.of(literal);
            boolean membership = "in".equals(spec.match())
                    || spec.match() == null || spec.match().isBlank() || "eq".equals(spec.match());
            if (membership) {
                values.forEach(value -> checkValue(name, "cell '" + input + "'", spec.type(),
                        List.of(), value));
            }
        });
        row.out().forEach((output, value) -> {
            DecisionsDocument.Output spec = decision.outputs().get(output);
            if (spec == null) {
                return;
            }
            checkValue(name, "output '" + output + "'", spec.type(), spec.allowed(), value);
        });
    }

    private static void checkValue(String name, String where, String type, List<Object> allowed,
            Object value) {
        boolean typeMismatch = switch (type == null ? "" : type) {
            case "integer", "number" -> !(value instanceof Number);
            case "string" -> !(value instanceof String);
            case "boolean" -> !(value instanceof Boolean);
            default -> false;
        };
        if (typeMismatch) {
            throw new TqlException(VALUE, "Decision '" + name + "' " + where + ": '" + value
                    + "' is not a " + type);
        }
        if (!allowed.isEmpty() && allowed.stream().noneMatch(option -> sameValue(option, value))) {
            throw new TqlException(VALUE, "Decision '" + name + "' " + where + ": '" + value
                    + "' is not one of the declared enum " + allowed);
        }
    }

    private static boolean sameValue(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return new java.math.BigDecimal(a.toString())
                    .compareTo(new java.math.BigDecimal(b.toString())) == 0;
        }
        return java.util.Objects.equals(left, right);
    }

    public boolean isEmpty() {
        return decisions.isEmpty();
    }

    /** The declared decisions by name (for lint and the docs portal). */
    public Map<String, DecisionsDocument.Decision> decisions() {
        return decisions;
    }

    /**
     * Resolves one route-declared {@code decide:} entry: the reference must name a declared
     * decision, wire exactly its inputs, and read only the namespaces that exist when
     * decisions evaluate (before the document loads and before any step runs).
     */
    public DecisionUse resolve(String alias, DecisionUse declared, String source) {
        if (declared.use() == null || declared.use().isBlank()) {
            throw new TqlException(UNKNOWN, source + ": decide entry '" + alias
                    + "' declares no use: — a decide: entry is always a reference to a"
                    + " decision under decisions/");
        }
        DecisionsDocument.Decision shared = decisions.get(declared.use());
        if (shared == null) {
            throw new TqlException(UNKNOWN, source + ": decide entry '" + alias
                    + "' references unknown decision '" + declared.use()
                    + "' — declare it under decisions/ or fix the reference");
        }
        Set<String> contract = new LinkedHashSet<>(shared.inputs().keySet());
        if (!declared.params().keySet().equals(contract)) {
            throw new TqlException(CONTRACT, source + ": decide entry '" + alias
                    + "' must wire exactly the inputs " + contract + " of decision '"
                    + declared.use() + "', not " + declared.params().keySet());
        }
        declared.params().forEach(
                (input, expression) -> checkWiringRoots(alias, input, expression, source));
        return declared.resolvedWith(shared);
    }

    private static void checkWiringRoots(String alias, String input, String expression,
            String source) {
        Expr parsed = ExpressionParser.parse(expression);
        Set<String> roots = new LinkedHashSet<>();
        collectRoots(parsed, roots);
        roots.removeAll(WIRING_ROOTS);
        if (!roots.isEmpty()) {
            throw new TqlException(WIRING_ROOT, source + ": decide entry '" + alias
                    + "' wires input '" + input + "' from " + roots + " — a wiring expression"
                    + " reads the request context only (" + WIRING_ROOTS.stream().sorted()
                            .collect(java.util.stream.Collectors.joining(", "))
                    + "); document/steps values do not exist when decisions evaluate");
        }
    }

    /** Every root a wiring expression reads, literals excluded, all branches included. */
    private static void collectRoots(Expr expr, Set<String> roots) {
        switch (expr) {
            case Expr.Path path -> {
                if (!path.segments().isEmpty()) {
                    roots.add(path.segments().get(0));
                }
            }
            case Expr.Not not -> collectRoots(not.operand(), roots);
            case Expr.Negate negate -> collectRoots(negate.operand(), roots);
            case Expr.Logical logical -> {
                collectRoots(logical.left(), roots);
                collectRoots(logical.right(), roots);
            }
            case Expr.Arithmetic arithmetic -> {
                collectRoots(arithmetic.left(), roots);
                collectRoots(arithmetic.right(), roots);
            }
            case Expr.Comparison comparison -> {
                collectRoots(comparison.left(), roots);
                collectRoots(comparison.right(), roots);
            }
            case Expr.Call call -> call.args().forEach(arg -> collectRoots(arg, roots));
            case Expr.Literal literal -> {
            }
        }
    }
}
