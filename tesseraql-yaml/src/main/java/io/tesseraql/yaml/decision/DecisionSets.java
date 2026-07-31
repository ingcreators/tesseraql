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
    /** TQL-DECISION-4702 (the core code, raised here for source mappings): malformed contract. */
    private static final TqlErrorCode CONTRACT_SHAPE = new TqlErrorCode(TqlDomain.DECISION,
            4702);
    /** TQL-DECISION-4704 (the core code, raised here for source defaults): miss-policy shape. */
    private static final TqlErrorCode MISS_SHAPE = new TqlErrorCode(TqlDomain.DECISION, 4704);

    /**
     * The context roots a wiring expression may read: what {@code RequestBinder} has bound
     * before the transaction opens. {@code document}, {@code steps}, and {@code audit} appear
     * later in the pipeline, and {@code decision} itself is the output namespace — reading any
     * of them would silently resolve null and match only wildcards, so the load rejects the
     * roots the runtime cannot honour.
     */
    private static final Set<String> WIRING_ROOTS = Set.of("params", "query", "body", "path",
            "principal", "tenant", "flags", "request", "preference");

    /**
     * {@code effectiveAt:} additionally reads {@code audit} — the command's single clock
     * reading is seeded before decisions evaluate, and {@code audit.now} is the default
     * reference instant of a dated lookup.
     */
    private static final Set<String> EFFECTIVE_ROOTS;
    static {
        Set<String> roots = new LinkedHashSet<>(WIRING_ROOTS);
        roots.add("audit");
        EFFECTIVE_ROOTS = Set.copyOf(roots);
    }

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
        decisions.forEach((name, decision) -> {
            if (decision.source() != null) {
                // Vendor only shapes the trailing row-limit clause; validating against any
                // one dialect validates the mapping.
                compileSource(name, decision, "postgres");
            } else {
                compile(name, decision);
            }
        });
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
                decision.onMiss(), decision.rows(), decision.source(), decision.defaultOut());
    }

    /**
     * Compiles a {@code decide:} block into its runtime {@link DecisionTables}
     * (docs/decision-tables.md). References arrive resolved by the manifest loader — the shared
     * decision stamped underneath — and each table compiles through the same code path the
     * loader already ran, so a failure here means the caller was built from an unresolved
     * definition. The one compile the route processor and the transition executor
     * (docs/transition-engine.md) both use.
     */
    public static DecisionTables compileUses(
            Map<String, io.tesseraql.yaml.model.DecisionUse> decide, String dialect) {
        List<DecisionTables.Use> uses = new java.util.ArrayList<>();
        (decide == null ? Map.<String, io.tesseraql.yaml.model.DecisionUse>of() : decide)
                .forEach((alias, use) -> {
                    if (use.decision() == null) {
                        throw new IllegalStateException("decide entry '" + alias
                                + "' is unresolved — the manifest loader resolves use:"
                                + " references before compilation");
                    }
                    uses.add(use.decision().source() != null
                            ? DecisionTables.use(alias,
                                    compileSource(use.use(), use.decision(), dialect),
                                    use.params(), use.effectiveAt())
                            : DecisionTables.use(alias, compile(use.use(), use.decision()),
                                    use.params()));
                });
        return new DecisionTables(uses);
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
        if (decision.source() != null && !decision.rows().isEmpty()) {
            throw new TqlException(CONTRACT_SHAPE, "Decision '" + name + "' declares both"
                    + " rows: and source: — one decision, one home for its rows");
        }
        if (decision.defaultOut() != null) {
            throw new TqlException(CONTRACT_SHAPE, "Decision '" + name + "' declares default:"
                    + " but is YAML-backed — its default is a trailing row without when:");
        }
        decision.inputs().forEach((input, spec) -> {
            if ("orgSubtree".equals(spec.match())) {
                throw new TqlException(CONTRACT_SHAPE, "Decision '" + name + "' input '"
                        + input + "' matches orgSubtree but the decision is YAML-backed —"
                        + " subtree membership lives in the org closure, so the kind needs a"
                        + " table source");
            }
        });
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

    /**
     * Compiles one table-backed decision into its generated lookup
     * (docs/decision-tables.md "Evaluation"): each mapped column contributes a
     * {@code (col IS NULL OR col ⟨op⟩ ?)} arm — NULL cell = wildcard, the YAML semantics in
     * SQL — an {@code in} input an EXISTS against its child table, an {@code orgSubtree}
     * input an EXISTS through the managed org closure, dated rows an effective-window test,
     * {@code ORDER BY} the priority column with a portable single-row fetch for
     * {@code hitPolicy: first}. The result is ordinary SQL, loggable and runnable in a SQL
     * tool.
     */
    public static DecisionTables.TableSource compileSource(String name,
            DecisionsDocument.Decision decision, String vendor) {
        DecisionsDocument.Source source = decision.source();
        if (decision.inputs().isEmpty() || decision.outputs().isEmpty()) {
            throw new TqlException(CONTRACT_SHAPE,
                    "Decision '" + name + "' declares no inputs or no outputs");
        }
        boolean unique = switch (decision.hitPolicy() == null || decision.hitPolicy().isBlank()
                ? "first"
                : decision.hitPolicy()) {
            case "first" -> false;
            case "unique" -> true;
            default -> throw new TqlException(CONTRACT_SHAPE, "Decision '" + name
                    + "': unknown hitPolicy '" + decision.hitPolicy() + "' — first or unique");
        };
        if (!unique && (source.priority() == null || source.priority().isBlank())) {
            throw new TqlException(CONTRACT_SHAPE, "Decision '" + name + "' (hitPolicy: first)"
                    + " needs a priority: column — first-hit needs a resolution order");
        }
        if (!source.effective().isEmpty() && source.effective().size() != 2) {
            throw new TqlException(CONTRACT_SHAPE, "Decision '" + name + "' effective: is a"
                    + " [from, to] column pair, not " + source.effective());
        }
        if (!source.outputs().keySet().equals(decision.outputs().keySet())) {
            throw new TqlException(CONTRACT_SHAPE, "Decision '" + name + "' source.outputs"
                    + " must map exactly the outputs " + decision.outputs().keySet() + ", not "
                    + source.outputs().keySet());
        }
        Set<String> mapped = new LinkedHashSet<>(source.match().keySet());
        mapped.retainAll(source.set().keySet());
        if (!mapped.isEmpty()) {
            throw new TqlException(CONTRACT_SHAPE, "Decision '" + name + "' maps " + mapped
                    + " under both match: and set:");
        }
        Set<String> covered = new LinkedHashSet<>(source.match().keySet());
        covered.addAll(source.set().keySet());
        if (!covered.equals(decision.inputs().keySet())) {
            throw new TqlException(CONTRACT_SHAPE, "Decision '" + name + "' source must map"
                    + " exactly the inputs " + decision.inputs().keySet() + ", not " + covered);
        }
        if (decision.defaultOut() != null) {
            if (!decision.defaultOut().keySet().equals(decision.outputs().keySet())) {
                throw new TqlException(CONTRACT_SHAPE, "Decision '" + name + "' default: must"
                        + " set exactly the outputs " + decision.outputs().keySet() + ", not "
                        + decision.defaultOut().keySet());
            }
            decision.defaultOut().forEach((output, value) -> checkValue(name,
                    "default '" + output + "'", decision.outputs().get(output).type(),
                    decision.outputs().get(output).allowed(), value));
        }
        if ("default".equals(decision.onMiss()) && decision.defaultOut() == null) {
            throw new TqlException(MISS_SHAPE, "Decision '" + name + "' declares onMiss:"
                    + " default but no default: outputs");
        }
        StringBuilder sql = new StringBuilder("select ");
        List<String> outputs = new java.util.ArrayList<>();
        source.outputs().forEach((output, column) -> {
            sql.append(outputs.isEmpty() ? "" : ", ").append("r.")
                    .append(identifier(name, column)).append(" as ")
                    .append(identifier(name, output));
            outputs.add(output);
        });
        sql.append(" from ").append(identifier(name, source.table())).append(" r where 1 = 1");
        List<String> binds = new java.util.ArrayList<>();
        String id = identifier(name, source.effectiveId());
        decision.inputs().forEach((input, spec) -> {
            DecisionTables.MatchKind kind = DecisionTables.MatchKind.parse(spec.match(), name,
                    input);
            DecisionsDocument.ColumnMatch match = source.match().get(input);
            switch (kind) {
                case EQ, BOOL -> {
                    requireShape(name, input, match != null && present(match.eq())
                            && match.between().isEmpty() && !present(match.subtree()),
                            "one eq: column");
                    sql.append(" and (r.").append(identifier(name, match.eq()))
                            .append(" is null or r.").append(identifier(name, match.eq()))
                            .append(" = ?)");
                    binds.add(input);
                }
                case BETWEEN -> {
                    requireShape(name, input, match != null && match.between().size() == 2,
                            "a between: [min, max] column pair");
                    String min = identifier(name, match.between().get(0));
                    String max = identifier(name, match.between().get(1));
                    sql.append(" and (r.").append(min).append(" is null or r.").append(min)
                            .append(" <= ?) and (r.").append(max).append(" is null or r.")
                            .append(max).append(" >= ?)");
                    binds.add(input);
                    binds.add(input);
                }
                case ORG_SUBTREE -> {
                    requireShape(name, input, match != null && present(match.subtree())
                            && !present(match.eq()) && match.between().isEmpty(),
                            "a subtree: unit-id column");
                    String column = identifier(name, match.subtree());
                    sql.append(" and (r.").append(column)
                            .append(" is null or exists (select 1 from tql_org_closure oc")
                            .append(" where oc.ancestor_id = r.").append(column)
                            .append(" and oc.descendant_id = ?))");
                    binds.add(input);
                }
                case IN -> {
                    DecisionsDocument.SetMatch set = source.set().get(input);
                    requireShape(name, input, set != null && present(set.table())
                            && present(set.key()) && present(set.value()),
                            "a set: {table, key, value} child table");
                    String child = identifier(name, set.table());
                    String key = identifier(name, set.key());
                    String value = identifier(name, set.value());
                    sql.append(" and (not exists (select 1 from ").append(child)
                            .append(" s where s.").append(key).append(" = r.").append(id)
                            .append(") or exists (select 1 from ").append(child)
                            .append(" s where s.").append(key).append(" = r.").append(id)
                            .append(" and s.").append(value).append(" = ?))");
                    binds.add(input);
                }
            }
        });
        if (!source.effective().isEmpty()) {
            String from = identifier(name, source.effective().get(0));
            String to = identifier(name, source.effective().get(1));
            sql.append(" and (r.").append(from).append(" is null or r.").append(from)
                    .append(" <= ?) and (r.").append(to).append(" is null or r.").append(to)
                    .append(" >= ?)");
            binds.add(DecisionTables.TableSource.EFFECTIVE_AT);
            binds.add(DecisionTables.TableSource.EFFECTIVE_AT);
        }
        if (!unique) {
            sql.append(" order by r.").append(identifier(name, source.priority())).append(' ')
                    .append(io.tesseraql.core.dialect.Pagination.fetchClause(vendor));
            binds.add(DecisionTables.TableSource.LIMIT);
        }
        return new DecisionTables.TableSource(name, sql.toString(), List.copyOf(binds),
                List.copyOf(outputs), unique, decision.defaultOut());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static void requireShape(String name, String input, boolean shaped, String shape) {
        if (!shaped) {
            throw new TqlException(CONTRACT_SHAPE, "Decision '" + name + "' input '" + input
                    + "' needs " + shape + " in its source mapping");
        }
    }

    /**
     * Identifiers land verbatim in the generated statement, so anything beyond a plain
     * (optionally schema-qualified) SQL name is rejected — a mapping is a name, never a
     * fragment.
     */
    private static String identifier(String name, String candidate) {
        if (candidate == null || !candidate.matches("[A-Za-z_][A-Za-z0-9_]*"
                + "(\\.[A-Za-z_][A-Za-z0-9_]*)?")) {
            throw new TqlException(CONTRACT_SHAPE, "Decision '" + name + "': '" + candidate
                    + "' is not a plain SQL identifier");
        }
        return candidate;
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
     * {@link #resolve} for a workflow transition's {@code decide:}: the wiring may
     * additionally read {@code document.*}, because a transition's decisions evaluate after
     * the document binds (the amount lives on the row, not in the transition's request body)
     * and before the guard consumes the outputs.
     */
    public DecisionUse resolveForWorkflow(String alias, DecisionUse declared, String source) {
        return resolve(alias, declared, source, true);
    }

    /**
     * Resolves one route-declared {@code decide:} entry: the reference must name a declared
     * decision, wire exactly its inputs, and read only the namespaces that exist when
     * decisions evaluate (before the document loads and before any step runs).
     */
    public DecisionUse resolve(String alias, DecisionUse declared, String source) {
        return resolve(alias, declared, source, false);
    }

    private DecisionUse resolve(String alias, DecisionUse declared, String source,
            boolean documentBound) {
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
        Set<String> wiringRoots = documentBound ? withDocument(WIRING_ROOTS) : WIRING_ROOTS;
        declared.params().forEach((input, expression) -> checkRoots(alias, input, expression,
                source, wiringRoots));
        if (declared.effectiveAt() != null && !declared.effectiveAt().isBlank()) {
            if (shared.source() == null) {
                throw new TqlException(CONTRACT, source + ": decide entry '" + alias
                        + "' declares effectiveAt: but decision '" + declared.use()
                        + "' is YAML-backed — dated rows are a table-source concern");
            }
            if (shared.source().effective().isEmpty()) {
                throw new TqlException(CONTRACT, source + ": decide entry '" + alias
                        + "' declares effectiveAt: but decision '" + declared.use()
                        + "' declares no effective: columns");
            }
            checkRoots(alias, "effectiveAt", declared.effectiveAt(), source,
                    documentBound ? withDocument(EFFECTIVE_ROOTS) : EFFECTIVE_ROOTS);
        }
        return declared.resolvedWith(shared);
    }

    private static Set<String> withDocument(Set<String> roots) {
        Set<String> extended = new LinkedHashSet<>(roots);
        extended.add("document");
        return extended;
    }

    private static void checkRoots(String alias, String input, String expression,
            String source, Set<String> allowed) {
        Expr parsed = ExpressionParser.parse(expression);
        Set<String> roots = new LinkedHashSet<>();
        collectRoots(parsed, roots);
        roots.removeAll(allowed);
        if (!roots.isEmpty()) {
            throw new TqlException(WIRING_ROOT, source + ": decide entry '" + alias
                    + "' wires input '" + input + "' from " + roots + " — a wiring expression"
                    + " reads the request context only (" + allowed.stream().sorted()
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
