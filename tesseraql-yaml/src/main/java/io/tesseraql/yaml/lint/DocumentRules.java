package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.LockSpec;
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

    // A declared lock whose own statement never assigns the column: a lock that never fires.
    private static final String LOCK_NEVER_ADVANCED = "TQL-SQL-2116";

    // A lock directive outside the statement's WHERE (docs/edit-conflict.md decision 1).
    private static final String LOCK_DIRECTIVE_OUTSIDE_WHERE = "TQL-SQL-2117";

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
            // The <name>Sql sibling of a type: sort input is framework-built from the
            // declared columns: allowlist (docs/list-surface.md decision 7) — as constrained
            // as an enum, just computed instead of enumerated.
            if (input.endsWith("Sql")) {
                InputField sortInput = inputs.get(input.substring(0,
                        input.length() - "Sql".length()));
                if (sortInput != null && "sort".equals(sortInput.type())) {
                    continue;
                }
            }
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
     *
     * <p>An id under the framework's {@code tql.} mark is defined by construction: it is the
     * synthesized atom check, permitting whoever holds that granted code, with no declaration
     * behind it (docs/stack-shells.md structural decision 1). Reading it as undefined warned
     * every application that referenced a framework surface's atom on a route of its own.
     */
    static boolean policyDefined(AppConfig config, String policy) {
        if (policy != null && policy.startsWith("tql.")) {
            return true;
        }
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
     * The lock directive as the parser accepts it. The 2-way parser trims a directive's content,
     * so the keyword may be spaced away from its delimiters and a plain indexOf would miss it.
     * Used only to locate the offset — whether a statement carries one at all is answered by the
     * parsed nodes, which no string literal can fool.
     */
    private static final Pattern LOCK_DIRECTIVE = Pattern.compile("/\\*%\\s*lock\\s*\\*/");

    /** The column the pairing heuristic looks for when the route declares no lock. */
    private static final String GUESSED_LOCK_COLUMN = "version";

    /** {@code where} at a word boundary: the cheapest honest answer to "which clause". */
    private static final Pattern WHERE_KEYWORD = Pattern.compile("\\bwhere\\b");

    /**
     * The optimistic-locking warnings, in two families the statement itself chooses between
     * (docs/edit-conflict.md decision 10).
     *
     * <p>On the step that carries the route's lock directive, the pairing question is already
     * answered: {@code lock:} implies its expectation, declaring {@code expect:} beside the
     * directive is refused, and a conditional directive is refused — so {@code TQL-SQL-2104} and
     * {@code TQL-SQL-2105} would only restate a build refusal. What the compiler cannot answer is
     * anything about the SQL <em>text</em>, because it holds a flat node list with no clause
     * positions. Two questions therefore live here and nowhere else: does the SET list advance
     * the column, and is the directive in the WHERE.
     *
     * <p>Every other step keeps the Phase 18 heuristic — a row-count expectation without a
     * version predicate only detects "row vanished", not concurrent edits; a version predicate
     * without an expectation silently affects zero rows — and on a route that declares a lock it
     * runs against the declared column rather than the guess. Suppressing per carrier step
     * rather than per route is deliberate: a multi-step command has one locked step and its
     * other writes are as unguarded as any.
     *
     * <p>Takes the document's path rather than a {@link RouteFile}, so an MCP tool and a queue
     * consumer — which write with the same bindings and had the check skipped entirely — are
     * held to it too (docs/silent-tolerance.md K-e).
     */
    static void lintOptimisticLocking(LintContext context, Path documentSource,
            RouteDefinition definition, boolean httpRoute,
            String source, List<LintFinding> findings) {
        if (!WRITE_RECIPES.contains(definition.recipe())) {
            return;
        }
        // Un-normalized by design: a block that omits column: reaches here with a null one, and
        // there is then no declared column to name in a finding.
        LockSpec lock = definition.lock();
        String declared = lock == null || lock.column() == null || lock.column().isBlank()
                ? null
                : lock.column().toLowerCase(java.util.Locale.ROOT);
        String column = declared == null ? GUESSED_LOCK_COLUMN : declared;
        // Only an HTTP command route mounts the step that reads the lock off a request, so
        // suggesting lock: to an MCP tool or a queue consumer would send its author into
        // TQL-ROUTE-3119. Nor is it advice on a route that already has its one lock.
        String alsoLock = httpRoute && lock == null
                ? ", or declare lock: " + column + " (docs/edit-conflict.md)"
                : "";
        Pattern columnWord = Pattern.compile("\\b" + Pattern.quote(column) + "\\b");
        Pattern columnAssigned = Pattern.compile("\\b" + Pattern.quote(column) + "\\s*=");
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
            // The statement past its leading comments, for the same reason the verb test needs
            // them gone: every generated file opens with a header, and a clause keyword there is
            // not a clause. Offsets below are all within this text, never the raw file's.
            String sql = io.tesseraql.core.validation.ValidationRules.statementBody(text)
                    .toLowerCase(java.util.Locale.ROOT);
            boolean isUpdate = sql.startsWith("update");
            List<SqlNode> nodes = context.sqlNodes(file);
            if (nodes != null && carriesLock(nodes)) {
                lintDeclaredLock(name, declared, columnAssigned, sql, isUpdate, source, findings);
                return;
            }
            boolean versionPredicate = columnWord.matcher(sql).find();
            if (isUpdate && binding.expect() != null && !versionPredicate) {
                findings.add(new LintFinding(UPDATE_WITHOUT_VERSION_PREDICATE, WARNING, source,
                        "Step '" + name + "': UPDATE declares expect.rowCount but has no " + column
                                + "-column predicate; a concurrent edit is only detected"
                                + " when the row vanishes - add `and " + column + " = ...`"
                                + alsoLock));
            }
            if (isUpdate && binding.expect() == null && versionPredicate) {
                findings.add(new LintFinding(VERSION_PREDICATE_WITHOUT_EXPECT, WARNING, source,
                        "Step '" + name + "': UPDATE has a " + column + " predicate but no"
                                + " expect.rowCount; a stale edit silently affects zero rows -"
                                + " declare expect: { rowCount: 1 }" + alsoLock));
            }
        });
    }

    /** The last {@code where} keyword starting before {@code limit}, or -1 if there is none. */
    private static int lastWhereBefore(String sql, int limit) {
        Matcher where = WHERE_KEYWORD.matcher(sql);
        int last = -1;
        while (where.find() && where.start() < limit) {
            last = where.start();
        }
        return last;
    }

    /** Whether any of this statement's nodes is the lock directive, nested ones included. */
    private static boolean carriesLock(List<SqlNode> nodes) {
        boolean[] found = {false};
        SqlNode.walk(nodes, node -> {
            if (node instanceof SqlNode.Lock) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /**
     * The two warnings only this layer can raise, on the step that carries the directive.
     *
     * <p>Both are warnings rather than refusals because both have a legitimate shape they cannot
     * tell apart from the defect: a column the database advances itself (a trigger, a vendor
     * rowversion) is a lock nothing in the statement assigns, and the clause test is a text scan
     * over a statement the framework deliberately does not parse twice.
     */
    private static void lintDeclaredLock(String name, String column,
            Pattern columnAssigned, String sql, boolean isUpdate, String source,
            List<LintFinding> findings) {
        // Only an UPDATE and a DELETE have a WHERE clause this scan can reason about. A MERGE
        // is a legal carrier — validateLock constrains the step's mode, never the verb — and its
        // lock belongs in an `on (…)` or a `when matched and …`, so judging its clause position
        // from a WHERE keyword would nag a correct statement forever.
        if (!isUpdate && !sql.startsWith("delete")) {
            return;
        }
        Matcher directive = LOCK_DIRECTIVE.matcher(sql);
        int lockAt = directive.find() ? directive.start() : -1;
        // The LAST where before the directive, not the first: a subquery in the SET list has a
        // where of its own, and taking that one would cut the SET list short and then report a
        // correct statement as never advancing its column.
        int whereAt = lastWhereBefore(sql, lockAt < 0 ? sql.length() : lockAt);
        if (lockAt >= 0 && whereAt < 0) {
            findings.add(new LintFinding(LOCK_DIRECTIVE_OUTSIDE_WHERE, WARNING, source,
                    "Step '" + name + "': the lock directive is not in the statement's WHERE -"
                            + " it renders a predicate, so a lock anywhere else is a syntax error"
                            + " the database raises the first time the statement runs"));
        }
        if (!isUpdate || column == null || lockAt < 0) {
            // A DELETE locks without advancing anything, which is correct: the row is gone. A
            // lock: block that names no column has nothing to look for. And a directive the node
            // walk saw but this text scan cannot locate is one we decline to judge.
            return;
        }
        // Only what precedes the statement's own WHERE can be a SET-list assignment, and the
        // lock predicate cannot contribute a false match: the directive carries no column name.
        String setList = sql.substring(0, whereAt < 0 ? lockAt : whereAt);
        if (!columnAssigned.matcher(setList).find()) {
            findings.add(new LintFinding(LOCK_NEVER_ADVANCED, WARNING, source,
                    "Step '" + name + "': lock: names '" + column + "' but this UPDATE's SET list"
                            + " never assigns it, so the lock compares a value that never moves"
                            + " and every stale save is accepted - advance it (" + column + " = "
                            + column + " + 1), unless the database advances it for you"));
        }
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
