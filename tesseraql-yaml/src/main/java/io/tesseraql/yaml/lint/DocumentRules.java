package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules every route-shaped document runs, whatever surface carries it —
 * a route, a queue consumer, an MCP tool or resource: datasource selection, declared-input
 * policy, tenant predicate, optimistic locking, validation, embedded variables, emitted
 * cache invalidation.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class DocumentRules {

    private static final String INVALID_INVALIDATES = "TQL-FIELD-4620";

    private static final String EMBEDDED_VARIABLE_INTERPOLATES_INPUT = "TQL-SQL-2109";

    private static final String SHARED_SCHEMA_WITHOUT_TENANT_PREDICATE = "TQL-TENANT-3001";

    private static final String UPDATE_WITHOUT_VERSION_PREDICATE = "TQL-SQL-2104";

    private static final String VERSION_PREDICATE_WITHOUT_EXPECT = "TQL-SQL-2105";

    private static final String INVALID_VALIDATION_RULE = "TQL-FIELD-2003";

    private static final String DATASOURCE_ROUTE_UNSUPPORTED_KEY = "TQL-YAML-1036";

    private DocumentRules() {
    }

    /** Recipes whose SQL pipeline is a read, where a route-level {@code datasource:} applies
     * (roadmap Phase 53). */
    static final Set<String> READ_DATASOURCE_RECIPES = Set.of("query-json", "query-html",
            "page", "query-export");

    /** Recipes whose whole single-connection transaction may move to a named connector (the
     * projection pattern, docs/multi-datasource.md) — as long as the route stays plain SQL. */
    static final Set<String> TRANSACTIONAL_DATASOURCE_RECIPES = Set.of("command-json",
            "webhook", "queue-consume");

    /**
     * {@code invalidates:} lints (docs/lookups.md, decision 13).
     *
     * <p>The declaration names source tables, and dropping a catalog is what a maintenance
     * screen's write is for. Two ways it silently does nothing: on a recipe that never commits
     * (there is no write to invalidate after), and naming a table no catalog reads — a typo in
     * a verbatim identifier looks exactly like a correct declaration, and the symptom is a
     * screen showing yesterday's names with nothing to explain it.
     *
     * <p>The unread-table case is a warning, not an error: a table may feed a catalog in
     * another environment's configuration, and the cost of an unnecessary invalidation is a
     * handful of small queries.
     */
    static void lintInvalidates(LintContext context, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        if (definition.invalidates().isEmpty()) {
            return;
        }
        if (!"command-json".equals(definition.recipe())) {
            findings.add(new LintFinding(INVALID_INVALIDATES, ERROR, source,
                    "invalidates: is only supported on command-json routes, not '"
                            + definition.recipe() + "' — there is no commit to invalidate"
                            + " catalogs after"));
            return;
        }
        Set<String> catalogTables = context.catalogTables();
        for (String table : definition.invalidates()) {
            if (table == null || table.isBlank()) {
                findings.add(new LintFinding(INVALID_INVALIDATES, ERROR, source,
                        "invalidates: carries an empty table name"));
            } else if (!catalogTables.contains(table)) {
                findings.add(new LintFinding(INVALID_INVALIDATES, WARNING, source,
                        "invalidates: names table '" + table + "', which no catalog reads —"
                                + " the declaration drops nothing"
                                + (catalogTables.isEmpty()
                                        ? " (the app declares no catalogs)"
                                        : " (catalogs read " + String.join(", ", catalogTables)
                                                + ")")));
            }
        }
    }

    /** A {@code {placeholder}} reference inside an embedded-variable template. */
    static final Pattern EMBEDDED_PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    /**
     * An embedded variable ({@code /*# … {x} … *}{@code /}) interpolates its placeholder values into
     * the SQL text, not a {@code ?} bind, so a request-controlled value there is an injection vector
     * unless allowlisted. This requires every placeholder that resolves to a request input to be
     * {@code enum}-constrained (the runtime guard against meta-characters is only defense in depth).
     */
    static void lintEmbeddedVariables(LintContext context, Path documentSource,
            RouteDefinition definition,
            String source, List<LintFinding> findings) {
        Binding sql = definition.main();
        if (sql == null || sql.isContract() || sql.file() == null) {
            return;
        }
        Path sqlFile = documentSource.getParent().resolve(sql.file());
        if (!Files.isRegularFile(sqlFile)) {
            return; // missing-file is reported separately
        }
        List<SqlNode> nodes = context.sqlNodes(sqlFile);
        if (nodes == null) {
            return; // SQL syntax / IO errors surface through other checks
        }
        Set<String> placeholders = new LinkedHashSet<>();
        SqlNode.walk(nodes, node -> {
            if (node instanceof SqlNode.Embedded embedded) {
                Matcher matcher = EMBEDDED_PLACEHOLDER.matcher(embedded.template());
                while (matcher.find()) {
                    placeholders.add(matcher.group(1).trim());
                }
            }
        });
        Map<String, String> params = sql.params() == null ? Map.of() : sql.params();
        Map<String, InputField> inputs = definition.input() == null ? Map.of() : definition.input();
        for (String placeholder : placeholders) {
            int dot = placeholder.indexOf('.');
            String root = dot < 0 ? placeholder : placeholder.substring(0, dot);
            String input = requestInput(params.get(root));
            if (input == null) {
                continue; // not a request input (constant / principal / loop var) — trusted
            }
            InputField field = inputs.get(input);
            if (field == null || field.enumValues() == null || field.enumValues().isEmpty()) {
                findings.add(new LintFinding(EMBEDDED_VARIABLE_INTERPOLATES_INPUT, ERROR, source,
                        "Embedded variable '{" + placeholder + "}' interpolates request input '"
                                + input + "' into SQL; constrain it with an 'enum' allowlist to "
                                + "prevent injection"));
            }
        }
    }

    /** The input name a {@code sql.params} source binds from a request, or {@code null} otherwise. */
    static String requestInput(String paramSource) {
        if (paramSource == null) {
            return null;
        }
        for (String prefix : List.of("query.", "params.", "body.")) {
            if (paramSource.startsWith(prefix)) {
                return paramSource.substring(prefix.length());
            }
        }
        return null;
    }

    /**
     * Policy ids are dotted names ({@code users.read}): literal keys of the policies map, not
     * nested config paths, so a {@code navigate} walk on the full path never finds them.
     */
    static boolean policyDefined(AppConfig config, String policy) {
        return config.navigate("tesseraql.security.policies") instanceof java.util.Map<?, ?> map
                && map.containsKey(policy);
    }

    /**
     * In shared-schema tenancy, every tenant-owned query must constrain rows by the tenant or it
     * leaks data across tenants (design ch. 30.4). Warns when an enabled shared-schema app has a
     * SQL route that neither binds {@code tenant.*} nor mentions a tenant column.
     */
    static void lintTenantPredicate(LintContext context, AppConfig config, Path documentSource,
            RouteDefinition definition, String source, List<LintFinding> findings) {
        // getBoolean, not parseBoolean: `tenancy.enabled: yes` used to read as false here and
        // silently switch the whole tenant lint off (docs/silent-tolerance.md K-e).
        boolean enabled = config.getBoolean("tenancy.enabled", false);
        String mode = config.getString("tenancy.mode").orElse("shared-schema");
        if (!enabled || !"shared-schema".equals(mode)) {
            return;
        }
        if (definition.main() == null || definition.main().isContract()
                || definition.main().file() == null) {
            return;
        }
        boolean boundToTenant = definition.main().params().values().stream()
                .anyMatch(expr -> expr != null && expr.startsWith("tenant."));
        if (boundToTenant) {
            return;
        }
        Path sqlFile = documentSource.getParent().resolve(definition.main().file());
        String sql = Files.isRegularFile(sqlFile) ? context.content(sqlFile) : null;
        if (sql != null && sql.toLowerCase().contains("tenant")) {
            return;
        }
        findings.add(new LintFinding(SHARED_SCHEMA_WITHOUT_TENANT_PREDICATE, WARNING, source,
                "Shared-schema route '" + definition.id()
                        + "' has no tenant predicate; bind tenant.id or filter by a tenant column"));
    }

    /** The write recipes an optimistic-locking nudge applies to, whatever surface mounts them. */
    static final java.util.Set<String> WRITE_RECIPES = java.util.Set.of("command-json",
            "queue-consume");

    /**
     * Nudges version-column predicates on command UPDATEs (roadmap Phase 18): a row-count
     * expectation without a version predicate only detects "row vanished", not concurrent
     * edits; a version predicate without an expectation silently affects zero rows.
     *
     * <p>Takes the document's path rather than a {@link RouteFile}, so an MCP tool and a queue
     * consumer — which write with the same bindings and had the check skipped entirely — are
     * held to it too (docs/silent-tolerance.md K-e).
     */
    static void lintOptimisticLocking(LintContext context, Path documentSource,
            RouteDefinition definition,
            String source, List<LintFinding> findings) {
        if (!WRITE_RECIPES.contains(definition.recipe())) {
            return;
        }
        java.util.Map<String, io.tesseraql.yaml.model.Binding> bindings = new java.util.LinkedHashMap<>(
                definition.steps());
        if (definition.main() != null) {
            bindings.put(RouteDefinition.MAIN, definition.main());
        }
        bindings.forEach((name, binding) -> {
            if (binding.file() == null) {
                return;
            }
            Path file = documentSource.getParent().resolve(binding.file());
            if (!Files.isRegularFile(file)) {
                return;
            }
            String text = context.content(file);
            if (text == null) {
                return;
            }
            String sql = text.toLowerCase();
            boolean isUpdate = sql.stripLeading().startsWith("update");
            boolean versionPredicate = sql.contains("version");
            if (isUpdate && binding.expect() != null && !versionPredicate) {
                findings.add(new LintFinding(UPDATE_WITHOUT_VERSION_PREDICATE, WARNING, source,
                        "Step '" + name + "': UPDATE declares expect.rows but has no"
                                + " version-column predicate; a concurrent edit is only detected"
                                + " when the row vanishes - add `and version = ...`"));
            }
            if (isUpdate && binding.expect() == null && versionPredicate) {
                findings.add(new LintFinding(VERSION_PREDICATE_WITHOUT_EXPECT, WARNING, source,
                        "Step '" + name + "': UPDATE has a version predicate but no expect.rows;"
                                + " a stale edit silently affects zero rows - declare"
                                + " expect: { rows: 1 }"));
            }
        });
    }

    /**
     * Statically checks the {@code validate:} block (roadmap Phase 19), reporting at lint time
     * what would otherwise fail at route build time: validation only applies to command routes,
     * a rule declares exactly one of {@code rule:}/{@code file:} plus a {@code field:}, its
     * expressions parse, its SQL file exists, and that SQL is a SELECT (it runs inside the
     * command's transaction and must not write).
     */
    static void lintValidation(LintContext context, Path file, RouteDefinition definition,
            String source,
            List<LintFinding> findings) {
        if (definition.validate().isEmpty()) {
            return;
        }
        definition.validate().forEach((id, rule) -> {
            if (rule.isExpression() == rule.isSql()) {
                findings.add(new LintFinding(INVALID_VALIDATION_RULE, ERROR, source,
                        "Validation rule '" + id
                                + "' must declare exactly one of rule: or file:"));
                return;
            }
            if (rule.field() == null || rule.field().isBlank()) {
                findings.add(new LintFinding(INVALID_VALIDATION_RULE, ERROR, source,
                        "Validation rule '" + id + "' needs a field: to report violations"
                                + " against"));
            }
            lintRuleExpression(id, rule.when(), source, findings, context.functions());
            if (rule.isExpression()) {
                lintRuleExpression(id, rule.rule(), source, findings, context.functions());
                return;
            }
            Path sqlFile = file.getParent().resolve(rule.file());
            if (!Files.isRegularFile(sqlFile)) {
                findings.add(new LintFinding(LintCodes.MISSING_SQL_FILE, ERROR, source,
                        "Validation rule '" + id + "' references a missing SQL file: "
                                + rule.file()));
                return;
            }
            String sql = context.content(sqlFile);
            if (sql != null && !io.tesseraql.core.validation.ValidationRules.isSelect(sql)) {
                findings.add(new LintFinding(INVALID_VALIDATION_RULE, ERROR, source,
                        "Validation rule '" + id + "': validation SQL must be a SELECT"
                                + " returning violations - it must not write"));
            }
        });
    }

    /**
     * Lints the {@code datasource:} surface (roadmap Phase 53). A named connector must be
     * declared under {@code tesseraql.datasources} ({@code TQL-YAML-1035}); a read route picks a
     * connector freely, and a transactional route moves its whole single-connection transaction
     * there — but only as plain SQL: {@code notify:}/{@code publish:}/{@code outbox:} and
     * sequence allocation ride the main connector, so declaring one on a non-main route is
     * {@code TQL-YAML-1036} (project through main instead). A binding inside a transactional
     * pipeline can never pick its own connector — the pipeline is one transaction on one
     * connection ({@code TQL-YAML-1037}). Named queries run outside a command's transaction,
     * so their per-binding override is legal on every recipe.
     */
    static void lintDatasource(LintContext context, AppConfig config, Path sourceFile,
            RouteDefinition definition,
            String source, List<LintFinding> findings) {
        boolean read = READ_DATASOURCE_RECIPES.contains(definition.recipe());
        if (declaredDatasource(definition.datasource())
                && !"main".equals(definition.datasource())) {
            if (read) {
                lintDatasourceName(context, config, sourceFile, definition.datasource(), source,
                        findings);
            } else if (TRANSACTIONAL_DATASOURCE_RECIPES.contains(definition.recipe())) {
                if (mainAnchored(definition)) {
                    findings.add(new LintFinding(DATASOURCE_ROUTE_UNSUPPORTED_KEY, ERROR, source,
                            "a 'datasource: " + definition.datasource() + "' route cannot declare"
                                    + " notify:/publish:/outbox: or sequence allocation - they"
                                    + " ride the main connector; project through main instead",
                            context.lineOf(sourceFile, "datasource:"), null));
                } else {
                    lintDatasourceName(context, config, sourceFile, definition.datasource(), source,
                            findings);
                }
            } else {
                findings.add(new LintFinding(DATASOURCE_ROUTE_UNSUPPORTED_KEY, ERROR, source,
                        "datasource: is not supported on the '" + definition.recipe()
                                + "' recipe - its pipeline runs on main",
                        context.lineOf(sourceFile, "datasource:"), null));
            }
        }
        Binding sql = definition.main();
        if (sql != null && declaredDatasource(sql.datasource())) {
            if (read) {
                lintDatasourceName(context, config, sourceFile, sql.datasource(), source, findings);
            } else {
                findings.add(new LintFinding(LintCodes.DATASOURCE_SPLITS_TRANSACTION, ERROR, source,
                        "sql.datasource on the '" + definition.recipe() + "' recipe would split"
                                + " the command transaction - a transactional pipeline runs on"
                                + " one connection"));
            }
        }
        definition.steps().forEach((name, step) -> {
            if (declaredDatasource(step.datasource())) {
                findings.add(new LintFinding(LintCodes.DATASOURCE_SPLITS_TRANSACTION, ERROR, source,
                        "Step '" + name + "' declares datasource: - a transactional pipeline is"
                                + " one transaction on one connection and cannot pick a connector"
                                + " per step"));
            }
        });
        definition.sources().forEach((name, query) -> {
            if (declaredDatasource(query.datasource())) {
                lintDatasourceName(context, config, sourceFile, query.datasource(), source,
                        findings);
            }
        });
        if (definition.fileImport() != null && definition.rowStep() != null
                && declaredDatasource(definition.rowStep().datasource())) {
            findings.add(new LintFinding(LintCodes.DATASOURCE_SPLITS_TRANSACTION, ERROR, source,
                    "an import's per-row step cannot declare datasource: - the import pipeline"
                            + " runs on main"));
        }
        if (definition.fileExport() != null && definition.main() != null
                && declaredDatasource(definition.main().datasource())) {
            findings.add(new LintFinding(LintCodes.DATASOURCE_SPLITS_TRANSACTION, ERROR, source,
                    "an exporting route's main source cannot declare datasource: - the export"
                            + " pipeline runs on main"));
        }
    }

    /** Whether a {@code datasource:} value is actually declared (non-null, non-blank). */
    static boolean declaredDatasource(String datasource) {
        return datasource != null && !datasource.isBlank();
    }

    /** Whether the route declares a feature whose tables live on the main connector. */
    static boolean mainAnchored(RouteDefinition definition) {
        return !definition.notifications().isEmpty() || definition.publish() != null
                || definition.outbox() != null
                || (definition.main() != null && definition.main().isSequence())
                || definition.steps().values().stream().anyMatch(Binding::isSequence);
    }

    /** {@code TQL-YAML-1035}: a non-main connector must exist under {@code tesseraql.datasources}
     * ({@code main} is always legal — an embedded database can supply it outside config). */
    static void lintDatasourceName(LintContext context, AppConfig config, Path sourceFile,
            String name, String source,
            List<LintFinding> findings) {
        if ("main".equals(name) || config.navigate("tesseraql.datasources." + name) != null) {
            return;
        }
        findings.add(new LintFinding(LintCodes.UNDECLARED_DATASOURCE, ERROR, source,
                "datasource '" + name + "' is not declared under tesseraql.datasources",
                context.lineOf(sourceFile, "datasource:"), null));
    }

    static void lintRuleExpression(String ruleId, String expression, String source,
            List<LintFinding> findings, io.tesseraql.core.expr.ExpressionFunctions functions) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        try {
            io.tesseraql.core.expr.ExpressionParser.parse(expression, functions);
        } catch (RuntimeException ex) {
            findings.add(new LintFinding(LintCodes.MALFORMED_EXPRESSION, ERROR, source,
                    "Validation rule '" + ruleId + "' has a malformed expression: "
                            + ex.getMessage()));
        }
    }
}
