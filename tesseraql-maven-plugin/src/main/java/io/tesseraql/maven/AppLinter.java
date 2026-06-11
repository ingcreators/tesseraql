package io.tesseraql.maven;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Statically lints an app home, independent of Maven, so it is unit-testable (design ch. 18, 20).
 *
 * <p>The first rule set checks recipes are known, referenced SQL files exist, and route security
 * policies are defined (deny-by-default safety). More rules (large-data, tenant predicate, field
 * authorization) are added alongside their features.
 */
public final class AppLinter {

    private static final Set<String> KNOWN_ROUTE_RECIPES = Set.of("query-json", "command-json",
            "query-html", "page", "query-export", "file-import", "file-export");

    /** Loads and lints the app home, returning all findings. */
    public List<LintFinding> lint(Path appHome) {
        AppManifest manifest = new ManifestLoader().load(appHome);
        List<LintFinding> findings = new ArrayList<>();
        for (RouteFile route : manifest.routes()) {
            lintRoute(appHome, manifest.config(), route, findings);
        }
        for (io.tesseraql.yaml.manifest.JobFile job : manifest.jobs()) {
            lintJob(appHome, manifest.config(), job, findings);
        }
        return findings;
    }

    private void lintRoute(Path appHome, AppConfig config, RouteFile route,
            List<LintFinding> findings) {
        RouteDefinition definition = route.definition();
        String source = appHome.relativize(route.source()).toString().replace('\\', '/');

        if (!KNOWN_ROUTE_RECIPES.contains(definition.recipe())) {
            findings.add(new LintFinding("TQL-YAML-1002", "error", source,
                    "Unknown route recipe '" + definition.recipe() + "'"));
        }
        if (definition.sql() != null && !definition.sql().isContract()
                && definition.sql().file() != null) {
            Path sqlFile = route.source().getParent().resolve(definition.sql().file());
            if (!Files.isRegularFile(sqlFile)) {
                findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                        "Referenced SQL file is missing: " + definition.sql().file()));
            }
        }
        definition.steps().forEach((name, step) -> {
            if (step.file() != null
                    && !Files.isRegularFile(route.source().getParent().resolve(step.file()))) {
                findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                        "Step '" + name + "' references a missing SQL file: " + step.file()));
            }
        });
        lintOptimisticLocking(route, definition, source, findings);
        lintValidation(route, definition, source, findings);
        lintNotify(config, definition, source, findings);
        if (definition.security() != null && definition.security().policy() != null
                && config.navigate(
                        "tesseraql.security.policies." + definition.security().policy()) == null) {
            findings.add(new LintFinding("TQL-SEC-4030", "warning", source,
                    "Route references undefined policy '" + definition.security().policy()
                            + "' (deny by default)"));
        }
        lintTenantPredicate(config, route, definition, source, findings);
    }

    /**
     * In shared-schema tenancy, every tenant-owned query must constrain rows by the tenant or it
     * leaks data across tenants (design ch. 30.4). Warns when an enabled shared-schema app has a
     * SQL route that neither binds {@code tenant.*} nor mentions a tenant column.
     */
    private void lintTenantPredicate(AppConfig config, RouteFile route, RouteDefinition definition,
            String source, List<LintFinding> findings) {
        boolean enabled = config.getString("tenancy.enabled").map(Boolean::parseBoolean)
                .orElse(false);
        String mode = config.getString("tenancy.mode").orElse("shared-schema");
        if (!enabled || !"shared-schema".equals(mode)) {
            return;
        }
        if (definition.sql() == null || definition.sql().isContract()
                || definition.sql().file() == null) {
            return;
        }
        boolean boundToTenant = definition.sql().params().values().stream()
                .anyMatch(expr -> expr != null && expr.startsWith("tenant."));
        if (boundToTenant) {
            return;
        }
        Path sqlFile = route.source().getParent().resolve(definition.sql().file());
        if (Files.isRegularFile(sqlFile) && readQuietly(sqlFile).toLowerCase().contains("tenant")) {
            return;
        }
        findings.add(new LintFinding("TQL-TENANT-3001", "warning", source,
                "Shared-schema route '" + definition.id()
                        + "' has no tenant predicate; bind tenant.id or filter by a tenant column"));
    }

    /**
     * Nudges version-column predicates on command UPDATEs (roadmap Phase 18): a row-count
     * expectation without a version predicate only detects "row vanished", not concurrent
     * edits; a version predicate without an expectation silently affects zero rows.
     */
    private void lintOptimisticLocking(RouteFile route, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        if (!"command-json".equals(definition.recipe())) {
            return;
        }
        java.util.Map<String, io.tesseraql.yaml.model.SqlBinding> bindings = new java.util.LinkedHashMap<>(
                definition.steps());
        if (definition.sql() != null) {
            bindings.put("sql", definition.sql());
        }
        bindings.forEach((name, binding) -> {
            if (binding.file() == null) {
                return;
            }
            Path file = route.source().getParent().resolve(binding.file());
            if (!Files.isRegularFile(file)) {
                return;
            }
            String sql = readQuietly(file).toLowerCase();
            boolean isUpdate = sql.stripLeading().startsWith("update");
            boolean versionPredicate = sql.contains("version");
            if (isUpdate && binding.expect() != null && !versionPredicate) {
                findings.add(new LintFinding("TQL-SQL-2104", "warning", source,
                        "Step '" + name + "': UPDATE declares expect.rows but has no"
                                + " version-column predicate; a concurrent edit is only detected"
                                + " when the row vanishes - add `and version = ...`"));
            }
            if (isUpdate && binding.expect() == null && versionPredicate) {
                findings.add(new LintFinding("TQL-SQL-2105", "warning", source,
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
    private void lintValidation(RouteFile route, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        if (definition.validate().isEmpty()) {
            return;
        }
        if (!"command-json".equals(definition.recipe())) {
            findings.add(new LintFinding("TQL-YAML-1003", "error", source,
                    "validate: is only supported on command-json routes, not '"
                            + definition.recipe() + "'"));
        }
        definition.validate().forEach((id, rule) -> {
            if (rule.isExpression() == rule.isSql()) {
                findings.add(new LintFinding("TQL-FIELD-2003", "error", source,
                        "Validation rule '" + id
                                + "' must declare exactly one of rule: or file:"));
                return;
            }
            if (rule.field() == null || rule.field().isBlank()) {
                findings.add(new LintFinding("TQL-FIELD-2003", "error", source,
                        "Validation rule '" + id + "' needs a field: to report violations"
                                + " against"));
            }
            lintRuleExpression(id, rule.when(), source, findings);
            if (rule.isExpression()) {
                lintRuleExpression(id, rule.rule(), source, findings);
                return;
            }
            Path file = route.source().getParent().resolve(rule.file());
            if (!Files.isRegularFile(file)) {
                findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                        "Validation rule '" + id + "' references a missing SQL file: "
                                + rule.file()));
            } else if (!io.tesseraql.core.validation.ValidationRules
                    .isSelect(readQuietly(file))) {
                findings.add(new LintFinding("TQL-FIELD-2003", "error", source,
                        "Validation rule '" + id + "': validation SQL must be a SELECT"
                                + " returning violations - it must not write"));
            }
        });
    }

    /**
     * Statically checks the {@code notify:} block of a command route (roadmap Phase 20):
     * notifications only apply to command routes, each declares a {@code channel:} that the
     * config knows, and its {@code when:} guard parses.
     */
    private void lintNotify(AppConfig config, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        if (definition.notifications().isEmpty()) {
            return;
        }
        if (!"command-json".equals(definition.recipe())) {
            findings.add(new LintFinding("TQL-YAML-1004", "error", source,
                    "notify: is only supported on command-json routes, not '"
                            + definition.recipe() + "'"));
        }
        definition.notifications()
                .forEach((id, spec) -> lintNotifySpec(config, id, spec, source, findings));
    }

    /**
     * Statically checks a batch job's pipeline steps (roadmap Phase 20): a step declares
     * exactly one of {@code sql:} or {@code notify:}, and notify steps lint like a route's.
     */
    private void lintJob(Path appHome, AppConfig config, io.tesseraql.yaml.manifest.JobFile job,
            List<LintFinding> findings) {
        String source = appHome.relativize(job.source()).toString().replace('\\', '/');
        for (io.tesseraql.yaml.model.PipelineStep step : job.definition().pipeline()) {
            if ((step.sql() == null) == (step.notification() == null)) {
                findings.add(new LintFinding("TQL-FIELD-2004", "error", source,
                        "Step '" + step.id() + "' must declare exactly one of sql: or notify:"));
                continue;
            }
            if (step.notification() != null) {
                lintNotifySpec(config, step.id(), step.notification(), source, findings);
            }
        }
    }

    private void lintNotifySpec(AppConfig config, String id,
            io.tesseraql.yaml.model.NotifySpec spec, String source, List<LintFinding> findings) {
        if (spec.channel() == null || spec.channel().isBlank()) {
            findings.add(new LintFinding("TQL-FIELD-2004", "error", source,
                    "Notification '" + id + "' needs a channel:"));
        } else if (config
                .navigate("tesseraql.notifications.channels." + spec.channel()) == null) {
            // A warning, not an error: another environment's config may declare the channel.
            findings.add(new LintFinding("TQL-YAML-1102", "warning", source,
                    "Notification '" + id + "' references undeclared channel '"
                            + spec.channel() + "'"));
        }
        if (spec.when() != null && !spec.when().isBlank()) {
            try {
                io.tesseraql.core.expr.ExpressionParser.parse(spec.when());
            } catch (RuntimeException ex) {
                findings.add(new LintFinding("TQL-SQL-2101", "error", source,
                        "Notification '" + id + "' has a malformed when: expression: "
                                + ex.getMessage()));
            }
        }
    }

    private void lintRuleExpression(String ruleId, String expression, String source,
            List<LintFinding> findings) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        try {
            io.tesseraql.core.expr.ExpressionParser.parse(expression);
        } catch (RuntimeException ex) {
            findings.add(new LintFinding("TQL-SQL-2101", "error", source,
                    "Validation rule '" + ruleId + "' has a malformed expression: "
                            + ex.getMessage()));
        }
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException ex) {
            return "";
        }
    }
}
