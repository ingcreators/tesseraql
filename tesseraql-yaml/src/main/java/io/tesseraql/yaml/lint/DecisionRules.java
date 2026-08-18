package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.core.expr.Expr;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.WorkflowFile;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decision tables — their reachability, their sources, and the expressions
 * that consume their outputs (docs/decision-tables.md).
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class DecisionRules implements LintRule {

    private static final String UNDECLARED_DECISION_REFERENCE = "TQL-DECISION-4711";

    private static final String UNREACHABLE_DECISION_ROW = "TQL-DECISION-4715";

    private static final String UNREFERENCED_DECISION = "TQL-DECISION-4716";

    private static final String RENAMED_DECISION_SOURCE_KEY = "TQL-DECISION-4718";

    private static final String IMPOSSIBLE_DECISION_COMPARISON = "TQL-DECISION-4713";

    private static final String UNHANDLED_DECISION_OUTCOME = "TQL-DECISION-4712";

    private static final String SUBTREE_WITHOUT_MANAGED_ORG = "TQL-DECISION-4717";

    private static final String DECISION_MAPPING_UNKNOWN_TO_SCHEMA = "TQL-DECISION-4710";

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        lintDecisions(context.appHome(), manifest, findings);
    }

    /**
     * Lints decision tables (docs/decision-tables.md). Malformed contracts, bad cells, and
     * overlapping unique rows already failed the load (TQL-DECISION-4700..4707, 4714); what
     * remains is what only a whole-app view can see: a {@code decision.*} bind naming no
     * {@code decide:} entry of its route (which could otherwise only fail at runtime, the
     * TQL-SEC-4136 line), a first-hit row shadowed entirely by an earlier row, and a decision
     * nothing references.
     */
    void lintDecisions(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        // A decisions document parses into ignoreUnknown records, so a `hitPolcy:` fell back to
        // the default hit policy with nothing to say it had (docs/lint-restructure.md decision 3).
        for (Path document : LintSupport.documents(appHome, "decisions")) {
            UnknownKeyRules.lintUnknownKeys(context, appHome, document,
                    io.tesseraql.yaml.model.DecisionsDocument.class, Set.of(), findings);
        }
        Set<String> referenced = new HashSet<>();
        for (Map.Entry<Path, RouteDefinition> document : LintSupport.authoringDocuments(manifest)) {
            RouteDefinition def = document.getValue();
            String source = appHome.relativize(document.getKey()).toString().replace('\\', '/');
            def.decide().values().forEach(use -> referenced.add(use.use()));
            for (String bind : decisionBinds(document.getKey(), def)) {
                String alias = bind.split("\\.")[1];
                if (!def.decide().containsKey(alias)) {
                    findings.add(new LintFinding(UNDECLARED_DECISION_REFERENCE, ERROR, source,
                            "Route '" + def.id() + "' binds '" + bind + "' but declares no"
                                    + " decide: entry '" + alias + "' — the bind can only fail"
                                    + " at runtime"));
                }
            }
        }
        // Workflow transitions consume decisions too (docs/decision-tables.md "decide:"),
        // so a decision only they reference is not unreferenced.
        for (WorkflowFile workflow : manifest.workflows()) {
            for (io.tesseraql.yaml.model.TransitionSpec transition : workflow.definition()
                    .transitions()) {
                transition.decide().values().forEach(use -> referenced.add(use.use()));
            }
            for (io.tesseraql.yaml.model.DispatchSpec dispatch : workflow.definition()
                    .dispatch()) {
                dispatch.decide().values().forEach(use -> referenced.add(use.use()));
            }
        }
        io.tesseraql.yaml.decision.DecisionSets sets = io.tesseraql.yaml.decision.DecisionSets
                .load(appHome, new io.tesseraql.yaml.SimpleYamlParser());
        if (sets.isEmpty()) {
            return;
        }
        sets.decisions().forEach((name, decision) -> {
            if (decision.source() != null) {
                // Table rows are runtime data; their integrity checks live on the maintenance
                // routes and in lintDecisionSources below.
                return;
            }
            io.tesseraql.core.decision.DecisionTables.Table table = io.tesseraql.yaml.decision.DecisionSets
                    .compile(name, decision);
            if (table.unique()) {
                // Unique rows may not even overlap (TQL-DECISION-4714), which subsumes shadowing.
                return;
            }
            List<io.tesseraql.core.decision.DecisionTables.Row> rows = table.rows();
            for (int later = 1; later < rows.size(); later++) {
                for (int earlier = 0; earlier < later; earlier++) {
                    if (rows.get(later).containedIn(rows.get(earlier))) {
                        findings.add(new LintFinding(UNREACHABLE_DECISION_ROW, WARNING, "decisions",
                                "Decision '" + name + "' row " + (later + 1) + " is unreachable"
                                        + " — row " + (earlier + 1) + " already matches"
                                        + " everything it matches"));
                        break;
                    }
                }
            }
        });
        sets.decisions().keySet().stream()
                .filter(name -> !referenced.contains(name))
                .forEach(name -> findings.add(new LintFinding(UNREFERENCED_DECISION, WARNING,
                        "decisions", "Decision '" + name + "' is declared but never referenced")));
        lintDecisionSources(appHome, manifest, sets, findings);
        lintDecisionConsumption(appHome, manifest, findings);
        lintDecisionRemovedKeys(appHome, findings);
    }

    /**
     * Flags a decision {@code source.id:} — renamed to {@code keyColumn:} before v1 (TQL-DECISION-
     * 4718). This rename is more dangerous than a plain drop: {@code effectiveKeyColumn()} defaults
     * to {@code "id"}, so the dropped key is masked and the decision silently joins its {@code set:}
     * child tables on a column named {@code id} rather than the one the author redirected to.
     */
    @SuppressWarnings("unchecked")
    private void lintDecisionRemovedKeys(Path appHome, List<LintFinding> findings) {
        Path dir = appHome.resolve("decisions");
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".yml")).sorted()
                    .forEach(file -> {
                        Map<String, Object> tree = context.tree(file);
                        if (tree == null
                                || !(tree.get("decisions") instanceof Map<?, ?> decisions)) {
                            return;
                        }
                        String source = LintSupport.relative(appHome, file);
                        for (Map.Entry<?, ?> decision : decisions.entrySet()) {
                            if (decision.getValue() instanceof Map<?, ?> body
                                    && body.get("source") instanceof Map<?, ?> src
                                    && ((Map<String, Object>) src).containsKey("id")) {
                                findings.add(new LintFinding(RENAMED_DECISION_SOURCE_KEY, ERROR,
                                        source,
                                        "Decision '" + decision.getKey()
                                                + "' source.id: was renamed to "
                                                + "keyColumn: before v1 — the old key is dropped and the "
                                                + "join silently falls back to a column named 'id'"));
                            }
                        }
                    });
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    /**
     * The consumption side of decisions (docs/decision-tables.md "Acting on the result").
     * Because outputs can be enum-typed, the compiler knows the full value space: a guard or
     * step {@code when:} comparing an output against a value the decision cannot produce is
     * {@code TQL-DECISION-4713}; a from-state whose transitions branch on an enum output but
     * leave declared values unhandled is {@code TQL-DECISION-4712} — the unhandled {@code
     * else} caught at build. A {@code decision.*} reference naming no {@code decide:} entry
     * reuses {@code 4711}: it could otherwise only fail at runtime.
     */
    private void lintDecisionConsumption(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        for (Map.Entry<Path, RouteDefinition> document : LintSupport.authoringDocuments(manifest)) {
            RouteDefinition def = document.getValue();
            String source = appHome.relativize(document.getKey()).toString().replace('\\', '/');
            def.steps().forEach((name, step) -> {
                if (step.when() != null && !step.when().isBlank()) {
                    checkDecisionExpression(source, "step '" + name + "' when:", step.when(),
                            def.decide(), findings, context.functions());
                }
            });
        }
        for (WorkflowFile workflow : manifest.workflows()) {
            String source = LintSupport.relative(appHome, workflow.source());
            Map<String, List<io.tesseraql.yaml.model.TransitionSpec>> byFrom = new LinkedHashMap<>();
            for (io.tesseraql.yaml.model.TransitionSpec transition : workflow.definition()
                    .transitions()) {
                if (transition.guard() != null && transition.guard().expression() != null
                        && !transition.guard().expression().isBlank()) {
                    checkDecisionExpression(source, "transition '" + transition.id()
                            + "' guard", transition.guard().expression(),
                            effectiveDecide(workflow.definition(), transition), findings,
                            context.functions());
                }
                byFrom.computeIfAbsent(transition.from(), unused -> new ArrayList<>())
                        .add(transition);
            }
            byFrom.forEach((from, transitions) -> lintBranchCoverage(source,
                    workflow.definition(), from, transitions, findings));
        }
    }

    /** One {@code decision.<alias>.<output> == literal} (or !=) comparison in an expression. */
    private record DecisionComparison(String alias, String output, Object literal,
            boolean equality) {
    }

    /**
     * A transition's effective {@code decide:}: its own entries plus what any dispatch
     * naming it contributes (docs/transition-engine.md track B) — a member invoked
     * through the dispatch reads the dispatch-level results as {@code decision.*}, so
     * the consumption lints must see them too. The transition's own entries win, though
     * a collision is itself a lint error.
     */
    private static Map<String, io.tesseraql.yaml.model.DecisionUse> effectiveDecide(
            io.tesseraql.yaml.model.WorkflowDefinition def,
            io.tesseraql.yaml.model.TransitionSpec transition) {
        Map<String, io.tesseraql.yaml.model.DecisionUse> merged = new LinkedHashMap<>();
        for (io.tesseraql.yaml.model.DispatchSpec dispatch : def.dispatch()) {
            if (dispatch.oneOf().contains(transition.id())) {
                merged.putAll(dispatch.decide());
            }
        }
        merged.putAll(transition.decide());
        return merged;
    }

    private static void checkDecisionExpression(String source, String where, String expression,
            Map<String, io.tesseraql.yaml.model.DecisionUse> decide,
            List<LintFinding> findings, io.tesseraql.core.expr.ExpressionFunctions functions) {
        Expr parsed;
        try {
            parsed = io.tesseraql.core.expr.ExpressionParser.parse(expression, functions);
        } catch (RuntimeException unparseable) {
            // A malformed expression is its own lint's concern.
            return;
        }
        List<List<String>> paths = new ArrayList<>();
        LintSupport.collectGuardPaths(parsed, paths);
        for (List<String> path : paths) {
            if (path.size() >= 2 && "decision".equals(path.get(0))
                    && !decide.containsKey(path.get(1))) {
                findings.add(new LintFinding(UNDECLARED_DECISION_REFERENCE, ERROR, source,
                        where + " references 'decision." + path.get(1) + "' but declares no"
                                + " decide: entry '" + path.get(1)
                                + "' — the reference can only resolve null at runtime"));
            }
        }
        for (DecisionComparison comparison : decisionComparisons(parsed)) {
            List<Object> allowed = allowedValues(decide, comparison.alias(),
                    comparison.output());
            if (allowed.isEmpty()) {
                continue;
            }
            boolean known = allowed.stream()
                    .anyMatch(value -> String.valueOf(value)
                            .equals(String.valueOf(comparison.literal())));
            if (!known) {
                findings.add(new LintFinding(IMPOSSIBLE_DECISION_COMPARISON, ERROR, source,
                        where + " compares decision." + comparison.alias() + "."
                                + comparison.output() + " to '" + comparison.literal()
                                + "', which the decision cannot produce — its enum is "
                                + allowed));
            }
        }
    }

    /**
     * The unhandled-else check: when every transition out of a state branches on the same
     * enum-typed output with plain equality, the compiler can prove which declared values
     * have no receiving transition.
     */
    private static void lintBranchCoverage(String source,
            io.tesseraql.yaml.model.WorkflowDefinition def, String from,
            List<io.tesseraql.yaml.model.TransitionSpec> transitions,
            List<LintFinding> findings) {
        if (transitions.size() < 2) {
            return;
        }
        String alias = null;
        String output = null;
        List<Object> covered = new ArrayList<>();
        List<Object> allowed = List.of();
        for (io.tesseraql.yaml.model.TransitionSpec transition : transitions) {
            // Only expression guards are provable here; a SQL guard file is opaque to the
            // enum-coverage analysis, so its presence conservatively ends it.
            if (transition.guard() == null || transition.guard().expression() == null
                    || transition.guard().expression().isBlank()) {
                return;
            }
            Expr parsed;
            try {
                parsed = io.tesseraql.core.expr.ExpressionParser
                        .parse(transition.guard().expression());
            } catch (RuntimeException unparseable) {
                return;
            }
            List<DecisionComparison> comparisons = decisionComparisons(parsed);
            // Only the provable shape counts: one equality comparison, nothing else in the
            // guard, every transition on the same output.
            if (comparisons.size() != 1 || !comparisons.get(0).equality()
                    || !(parsed instanceof Expr.Comparison)) {
                return;
            }
            DecisionComparison comparison = comparisons.get(0);
            if (alias == null) {
                alias = comparison.alias();
                output = comparison.output();
                allowed = allowedValues(effectiveDecide(def, transition), alias, output);
            } else if (!alias.equals(comparison.alias())
                    || !output.equals(comparison.output())) {
                return;
            }
            covered.add(comparison.literal());
        }
        if (allowed.isEmpty()) {
            return;
        }
        List<Object> unhandled = new ArrayList<>();
        for (Object value : allowed) {
            if (covered.stream()
                    .noneMatch(hit -> String.valueOf(hit).equals(String.valueOf(value)))) {
                unhandled.add(value);
            }
        }
        if (!unhandled.isEmpty()) {
            findings.add(new LintFinding(UNHANDLED_DECISION_OUTCOME, WARNING, source,
                    "State '" + from + "' branches on decision." + alias + "." + output
                            + " but no transition handles " + unhandled
                            + " — a value the decision can produce has no receiver"));
        }
    }

    private static List<Object> allowedValues(
            Map<String, io.tesseraql.yaml.model.DecisionUse> decide, String alias,
            String output) {
        io.tesseraql.yaml.model.DecisionUse use = decide.get(alias);
        if (use == null || use.decision() == null) {
            return List.of();
        }
        io.tesseraql.yaml.model.DecisionsDocument.Output spec = use.decision().outputs()
                .get(output);
        return spec == null ? List.of() : spec.allowed();
    }

    /** Every {@code decision.<a>.<o> ==/!= literal} comparison, either operand order. */
    private static List<DecisionComparison> decisionComparisons(Expr expr) {
        List<DecisionComparison> out = new ArrayList<>();
        collectDecisionComparisons(expr, out);
        return out;
    }

    private static void collectDecisionComparisons(Expr expr, List<DecisionComparison> out) {
        switch (expr) {
            case Expr.Comparison comparison -> {
                if (comparison.operator() == Expr.Comparison.Operator.EQ
                        || comparison.operator() == Expr.Comparison.Operator.NE) {
                    addComparison(comparison.left(), comparison.right(),
                            comparison.operator() == Expr.Comparison.Operator.EQ, out);
                    addComparison(comparison.right(), comparison.left(),
                            comparison.operator() == Expr.Comparison.Operator.EQ, out);
                }
            }
            case Expr.Logical logical -> {
                collectDecisionComparisons(logical.left(), out);
                collectDecisionComparisons(logical.right(), out);
            }
            case Expr.Not not -> collectDecisionComparisons(not.operand(), out);
            default -> {
            }
        }
    }

    private static void addComparison(Expr side, Expr other, boolean equality,
            List<DecisionComparison> out) {
        if (side instanceof Expr.Path path && other instanceof Expr.Literal literal
                && path.segments().size() == 3 && "decision".equals(path.segments().get(0))) {
            out.add(new DecisionComparison(path.segments().get(1), path.segments().get(2),
                    literal.value(), equality));
        }
    }

    /**
     * Table-backed decision sources (docs/decision-tables.md "Integrity when the rows are
     * data"): an {@code subtree} input matches through the managed org closure, so it needs
     * {@code tesseraql.orgunit.mode: managed} (TQL-DECISION-4717); and when the schema
     * introspection sidecar is present, every mapped table and column is checked against the
     * real DDL (TQL-DECISION-4710) — the rows are runtime data, but the shape of their table
     * is checkable at build.
     */
    private void lintDecisionSources(Path appHome, AppManifest manifest,
            io.tesseraql.yaml.decision.DecisionSets sets, List<LintFinding> findings) {
        boolean managedOrgUnits = io.tesseraql.yaml.org.OrgUnitSettings
                .from(manifest.config()).managed();
        Map<String, Set<String>> ddl = sidecarColumns(appHome);
        sets.decisions().forEach((name, decision) -> {
            if (decision.source() == null) {
                return;
            }
            boolean subtree = decision.inputs().values().stream()
                    .anyMatch(input -> "subtree".equals(input.match()));
            if (subtree && !managedOrgUnits) {
                findings.add(new LintFinding(SUBTREE_WITHOUT_MANAGED_ORG, ERROR, "decisions",
                        "Decision '" + name + "' matches subtree, which resolves through"
                                + " the managed org closure — set tesseraql.orgunit.mode:"
                                + " managed or drop the subtree input"));
            }
            if (ddl != null) {
                checkSourceDdl(name, decision.source(), ddl, findings);
            }
        });
    }

    private static void checkSourceDdl(String name,
            io.tesseraql.yaml.model.DecisionsDocument.Source source,
            Map<String, Set<String>> ddl, List<LintFinding> findings) {
        java.util.function.BiConsumer<String, List<String>> check = (table, columns) -> {
            Set<String> present = ddl.get(table.toLowerCase(java.util.Locale.ROOT));
            if (present == null) {
                findings.add(new LintFinding(DECISION_MAPPING_UNKNOWN_TO_SCHEMA, ERROR, "decisions",
                        "Decision '" + name + "' maps table '" + table + "', which the schema"
                                + " sidecar does not know — regenerate .tesseraql/docs/"
                                + "schema.json or fix the mapping"));
                return;
            }
            columns.stream()
                    .filter(column -> column != null && !column.isBlank())
                    .filter(column -> !present.contains(column.toLowerCase(java.util.Locale.ROOT)))
                    .forEach(column -> findings.add(new LintFinding(
                            DECISION_MAPPING_UNKNOWN_TO_SCHEMA,
                            ERROR, "decisions", "Decision '" + name + "' maps column '"
                                    + column + "' of '" + table + "', which the schema sidecar"
                                    + " does not know")));
        };
        List<String> columns = new ArrayList<>();
        columns.add(source.effectiveKeyColumn());
        columns.add(source.priority());
        columns.addAll(source.effective());
        source.match().values().forEach(match -> {
            columns.add(match.eq());
            columns.addAll(match.between());
            columns.add(match.subtree());
        });
        columns.addAll(source.outputs().values());
        check.accept(source.table(), columns);
        source.set().values().forEach(set -> check.accept(set.table(),
                List.of(set.key(), set.value())));
    }

    /**
     * Lower-cased {@code table -> columns} across every datasource of the introspection
     * sidecar, or null when the sidecar is absent or unreadable — a run artifact a fresh
     * checkout legitimately lacks (the ReleaseDiff degradation contract).
     */
    private static Map<String, Set<String>> sidecarColumns(Path appHome) {
        Path sidecar = appHome.resolve(".tesseraql/docs/schema.json");
        if (!Files.isRegularFile(sidecar)) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper
                    .readTree(Files.readString(sidecar));
            Map<String, Set<String>> tables = new LinkedHashMap<>();
            for (var entry : root.path("datasources").properties()) {
                io.tesseraql.yaml.scaffold.CatalogSchema schema = mapper.convertValue(
                        entry.getValue(), io.tesseraql.yaml.scaffold.CatalogSchema.class);
                if (schema == null || schema.tables() == null) {
                    continue;
                }
                for (io.tesseraql.yaml.scaffold.CatalogSchema.Table table : schema.tables()) {
                    Set<String> columns = tables.computeIfAbsent(
                            table.name().toLowerCase(java.util.Locale.ROOT),
                            unused -> new HashSet<>());
                    table.columns().forEach(column -> columns
                            .add(column.name().toLowerCase(java.util.Locale.ROOT)));
                }
            }
            return tables;
        } catch (java.io.IOException | RuntimeException ex) {
            return null;
        }
    }

    /** The distinct {@code decision.*} bind expressions across a document's parseable SQL files. */
    private Set<String> decisionBinds(Path source, RouteDefinition def) {
        return LintSupport.ambientBinds(context, source, def, expression -> expression
                .startsWith(io.tesseraql.core.sql.AmbientBinds.DECISION + ".")
                && expression.split("\\.").length >= 2);
    }
}
