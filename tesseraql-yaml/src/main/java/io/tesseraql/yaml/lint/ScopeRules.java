package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.manifest.ScopeFile;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.JobDefinition;
import io.tesseraql.yaml.model.MatchArm;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.ScopeDefinition;
import io.tesseraql.yaml.model.WhenCondition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scopes: the scope documents, the directives SQL carries, and the write-side
 * inference that finds a scope-governed table a write forgot.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class ScopeRules implements LintRule {

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        lintScopes(context.appHome(), manifest, findings);
    }

    private static final Pattern SCOPE_DIRECTIVE = Pattern
            .compile("/\\*%\\s*scope\\s+([^*]+?)\\s*\\*/");

    private static final Pattern SQL_IDENTIFIER = Pattern
            .compile(io.tesseraql.core.sql.SqlIdentifiers.IDENTIFIER);

    /**
     * Lints organizational data scoping (roadmap Phase 29): every {@code scope/} definition is
     * well-formed, and every {@code /*%scope%/} directive in a query names a declared scope with a
     * valid {@code on <alias>}.
     */
    void lintScopes(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        Set<String> declared = new HashSet<>();
        for (ScopeFile scope : manifest.scopes()) {
            lintScopeDefinition(appHome, scope, findings);
            UnknownKeyRules.lintUnknownKeys(context, appHome, scope.source(), ScopeDefinition.class,
                    Set.of(),
                    findings);
            if (scope.definition().id() != null) {
                declared.add(scope.definition().id());
            }
        }
        for (RouteFile route : manifest.routes()) {
            lintScopeDirectives(appHome, route, declared, findings);
        }
        for (RouteFile consumer : manifest.consumers()) {
            lintScopeDirectives(appHome, consumer, declared, findings);
        }
        for (io.tesseraql.yaml.manifest.ToolFile tool : manifest.tools()) {
            lintScopeDirectives(appHome, tool.source(), tool.definition(), declared, findings);
        }
        lintUnreachableScopeDirectives(appHome, manifest, findings);
        lintWriteScope(appHome, manifest, findings);
    }

    /**
     * Reports a {@code /*%scope … *&#47;} directive sitting in SQL no scope resolver reaches
     * ({@code TQL-SCOPE-3014}). Scoping is wired into the request path — route SQL, named
     * queries, command steps, and validation rules — because that is where a principal exists to
     * scope against. Batch jobs run on a schedule and file transfers stream rows outside a
     * request, so a directive there can only fail at execution time with {@code TQL-SQL-2106}.
     * Saying so at lint time is the difference between a build error and a 3am job failure.
     */
    private void lintUnreachableScopeDirectives(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        for (JobFile job : manifest.jobs()) {
            Path dir = job.source().getParent();
            String source = LintSupport.relative(appHome, job.source());
            for (String file : jobSqlFiles(job)) {
                Path sqlFile = dir.resolve(file);
                String sql = Files.isRegularFile(sqlFile) ? context.content(sqlFile) : null;
                if (sql != null && SCOPE_DIRECTIVE.matcher(sql).find()) {
                    findings.add(new LintFinding("TQL-SCOPE-3014", "error", source,
                            "batch job '" + job.definition().id() + "' uses a /*%scope … */"
                                    + " directive in " + file + ", but a job runs with no"
                                    + " principal to scope against — it would fail at execution"
                                    + " time (TQL-SQL-2106); filter with a job parameter instead"));
                }
            }
        }
    }

    /** Every SQL file a job references: its pipeline steps and the import row SQL. */
    private static List<String> jobSqlFiles(JobFile job) {
        List<String> files = new ArrayList<>();
        JobDefinition definition = job.definition();
        if (definition.pipeline() != null) {
            definition.pipeline().forEach(step -> {
                if (step.sql() != null && step.sql().file() != null) {
                    files.add(step.sql().file());
                }
                if (step.chunk() != null) {
                    if (step.chunk().reader() != null && step.chunk().reader().file() != null) {
                        files.add(step.chunk().reader().file());
                    }
                    if (step.chunk().writer() != null && step.chunk().writer().file() != null) {
                        files.add(step.chunk().writer().file());
                    }
                }
            });
        }
        return files;
    }

    // The trailing alias guard is a lookahead, not \b: \b only bounds ASCII word characters,
    // so a Japanese alias would never "end" and the table would silently escape the scope set.
    private static final Pattern SCOPED_TABLE_ALIASED = Pattern.compile(
            "(?is)\\b(?:from|join|into|update)\\s+([\\p{L}_][\\p{L}\\p{N}_.]*)"
                    + "\\s+(?:as\\s+)?ALIAS(?![\\p{L}\\p{N}_])");

    private static final Pattern WRITE_TARGET = Pattern.compile(
            "(?is)^\\s*(?:update|delete\\s+from)\\s+([\\p{L}_][\\p{L}\\p{N}_.]*)");

    /**
     * A defense-in-depth guard (docs/data-scoping.md, docs/security-hardening.md): if the app scopes
     * a table's reads with {@code /*%scope … *}{@code /} but an {@code UPDATE}/{@code DELETE} on the
     * same table carries no scope predicate, the write can reach rows outside the authorized set.
     * The set of scope-governed tables is inferred from where scope directives are actually used
     * (there is no manifest-level table→scope map), so this warns only on a genuine read/write
     * inconsistency within one app — never on a table the app does not scope at all.
     */
    private void lintWriteScope(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        if (manifest.scopes().isEmpty()) {
            return;
        }
        Set<String> scopedTables = new HashSet<>();
        for (RouteFile route : allScopeRoutes(manifest)) {
            for (Path sqlFile : routeSqlFiles(route)) {
                String sql = Files.isRegularFile(sqlFile) ? context.content(sqlFile) : null;
                if (sql != null) {
                    collectScopedTables(sql, scopedTables);
                }
            }
        }
        if (scopedTables.isEmpty()) {
            return;
        }
        for (RouteFile route : allScopeRoutes(manifest)) {
            String source = LintSupport.relative(appHome, route.source());
            String id = route.definition().id();
            for (Map.Entry<String, Binding> entry : writeBindings(route.definition())) {
                Path sqlFile = route.source().getParent().resolve(entry.getValue().file());
                if (!Files.isRegularFile(sqlFile)) {
                    continue;
                }
                String sql = context.content(sqlFile);
                if (sql == null) {
                    continue;
                }
                Matcher target = WRITE_TARGET.matcher(sql);
                if (!target.find()) {
                    continue; // not an UPDATE/DELETE (an INSERT adds rows, nothing to over-reach)
                }
                String table = lastSegment(target.group(1));
                if (scopedTables.contains(table) && !SCOPE_DIRECTIVE.matcher(sql).find()) {
                    findings.add(new LintFinding("TQL-SEC-4100", "warning", source,
                            "route '" + id + "' writes scope-governed table '" + table
                                    + "' with no /*%scope … */ predicate; confirm the write cannot"
                                    + " reach rows outside the caller's scope"));
                }
            }
        }
    }

    /** The routes + consumers a scope directive or scoped write can live on. */
    private static List<RouteFile> allScopeRoutes(AppManifest manifest) {
        List<RouteFile> routes = new ArrayList<>(manifest.routes());
        routes.addAll(manifest.consumers());
        return routes;
    }

    /** Adds every table a {@code /*%scope … on alias *}{@code /} (or aliasless) directive governs. */
    private static void collectScopedTables(String sql, Set<String> out) {
        Matcher directive = SCOPE_DIRECTIVE.matcher(sql);
        while (directive.find()) {
            String content = stripAsBoolean(directive.group(1).trim());
            int on = content.indexOf(" on ");
            if (on >= 0) {
                String alias = content.substring(on + " on ".length()).trim();
                if (SQL_IDENTIFIER.matcher(alias).matches()) {
                    Matcher aliased = Pattern.compile(SCOPED_TABLE_ALIASED.pattern()
                            .replace("ALIAS", Pattern.quote(alias))).matcher(sql);
                    if (aliased.find()) {
                        out.add(lastSegment(aliased.group(1)));
                    }
                }
            } else {
                // Aliasless scope: the statement's single write/from target is the scoped table.
                Matcher write = WRITE_TARGET.matcher(sql);
                if (write.find()) {
                    out.add(lastSegment(write.group(1)));
                } else {
                    Matcher from = Pattern.compile("(?is)\\bfrom\\s+([\\p{L}_][\\p{L}\\p{N}_.]*)")
                            .matcher(sql);
                    if (from.find()) {
                        out.add(lastSegment(from.group(1)));
                    }
                }
            }
        }
    }

    /** The {@code (name, binding)} pairs of a route whose SQL runs in write ({@code update}) mode. */
    private static List<Map.Entry<String, Binding>> writeBindings(RouteDefinition definition) {
        Map<String, Binding> bindings = new LinkedHashMap<>();
        if (definition.main() != null) {
            bindings.put(RouteDefinition.MAIN, definition.main());
        }
        bindings.putAll(definition.steps());
        List<Map.Entry<String, Binding>> writes = new ArrayList<>();
        for (Map.Entry<String, Binding> entry : bindings.entrySet()) {
            Binding binding = entry.getValue();
            if (binding != null && !binding.isContract() && binding.file() != null
                    && "update".equals(binding.effectiveMode())) {
                writes.add(entry);
            }
        }
        return writes;
    }

    /** The last dotted segment of a possibly schema-qualified table name, lowercased. */
    private static String lastSegment(String table) {
        int dot = table.lastIndexOf('.');
        return (dot < 0 ? table : table.substring(dot + 1)).toLowerCase(java.util.Locale.ROOT);
    }

    /** Checks a scope definition: each arm declares exactly one effect, a valid when, a real file. */
    private void lintScopeDefinition(Path appHome, ScopeFile scope, List<LintFinding> findings) {
        String source = LintSupport.relative(appHome, scope.source());
        ScopeDefinition definition = scope.definition();
        if (!"scope".equals(definition.kind())) {
            findings.add(new LintFinding("TQL-SCOPE-3012", "error", source,
                    "scope '" + definition.id() + "' must declare kind: scope"));
        }
        if (definition.match().isEmpty()) {
            findings.add(new LintFinding("TQL-SCOPE-3012", "error", source,
                    "scope '" + definition.id() + "' declares no match arms"));
        }
        Path scopeDir = scope.source().getParent();
        int index = 0;
        for (MatchArm arm : definition.match()) {
            String where = "scope '" + definition.id() + "' arm " + index;
            boolean hasApply = arm.apply() != null && !arm.apply().isBlank();
            boolean hasFile = arm.file() != null && !arm.file().isBlank();
            if (hasApply == hasFile) {
                findings.add(new LintFinding("TQL-SCOPE-3012", "error", source,
                        where + " must declare exactly one of apply (all|none) or file"));
            }
            if (hasApply && !arm.isAll() && !arm.isNone()) {
                findings.add(new LintFinding("TQL-SCOPE-3012", "error", source,
                        where + " apply must be 'all' or 'none', not '" + arm.apply() + "'"));
            }
            if (hasFile && !Files.isRegularFile(scopeDir.resolve(arm.file()))) {
                findings.add(new LintFinding("TQL-SCOPE-3012", "error", source,
                        where + " references missing fragment '" + arm.file() + "'"));
            }
            lintWhen(arm.when(), where, source, findings);
            index++;
        }
    }

    private void lintWhen(WhenCondition when, String where, String source,
            List<LintFinding> findings) {
        if (when == null) {
            return;
        }
        int set = (when.role() != null ? 1 : 0) + (when.permission() != null ? 1 : 0)
                + (when.claim() != null ? 1 : 0);
        if (set == 0) {
            findings.add(new LintFinding("TQL-SCOPE-3012", "error", source,
                    where + " when declares no role/permission/claim (an empty block or a "
                            + "misspelled key would match every principal); remove when for an "
                            + "unconditional arm, or name a predicate"));
        }
        if (set > 1) {
            findings.add(new LintFinding("TQL-SCOPE-3012", "error", source,
                    where + " when must set only one of role/permission/claim"));
        }
        if (when.claim() != null && when.value() == null) {
            findings.add(new LintFinding("TQL-SCOPE-3012", "error", source,
                    where + " when claim needs an 'equals' value"));
        }
    }

    private void lintScopeDirectives(Path appHome, RouteFile route, Set<String> declared,
            List<LintFinding> findings) {
        lintScopeDirectives(appHome, route.source(), route.definition(), declared, findings);
    }

    /** Checks each {@code /*%scope%/} directive in a document's SQL names a declared scope. */
    private void lintScopeDirectives(Path appHome, Path file, RouteDefinition definition,
            Set<String> declared, List<LintFinding> findings) {
        String source = LintSupport.relative(appHome, file);
        String id = definition.id();
        for (Path sqlFile : routeSqlFiles(file, definition)) {
            if (!Files.isRegularFile(sqlFile)) {
                continue;
            }
            String sql = context.content(sqlFile);
            if (sql == null) {
                continue;
            }
            Matcher matcher = SCOPE_DIRECTIVE.matcher(sql);
            while (matcher.find()) {
                String content = stripAsBoolean(matcher.group(1).trim());
                String name = content;
                String alias = null;
                int on = content.indexOf(" on ");
                if (on >= 0) {
                    name = content.substring(0, on).trim();
                    alias = content.substring(on + " on ".length()).trim();
                }
                if (!declared.contains(name)) {
                    findings.add(new LintFinding("TQL-SCOPE-3011", "error", source,
                            "route '" + id + "' references scope '" + name
                                    + "' not declared under scope/"));
                }
                if (alias != null && !SQL_IDENTIFIER.matcher(alias).matches()) {
                    findings.add(new LintFinding("TQL-SCOPE-3013", "error", source,
                            "route '" + id + "' scope 'on' alias '" + alias
                                    + "' is not a SQL identifier"));
                }
            }
        }
    }

    private static List<Path> routeSqlFiles(RouteFile route) {
        return routeSqlFiles(route.source(), route.definition());
    }

    /** The non-contract SQL files a document references ({@code sql}, {@code steps}, {@code queries}). */
    private static List<Path> routeSqlFiles(Path source, RouteDefinition definition) {
        Path dir = source.getParent();
        Map<String, Binding> bindings = new LinkedHashMap<>();
        if (definition.main() != null) {
            bindings.put(RouteDefinition.MAIN, definition.main());
        }
        bindings.putAll(definition.steps());
        bindings.putAll(definition.sources());
        List<Path> files = new ArrayList<>();
        for (Binding binding : bindings.values()) {
            if (binding != null && !binding.isContract() && binding.file() != null) {
                files.add(dir.resolve(binding.file()));
            }
        }
        return files;
    }

    /** Drops the {@code as boolean} suffix so the scope name/alias parse the same as a predicate. */
    static String stripAsBoolean(String content) {
        return content.endsWith(" as boolean")
                ? content.substring(0, content.length() - " as boolean".length()).trim()
                : content;
    }
}
