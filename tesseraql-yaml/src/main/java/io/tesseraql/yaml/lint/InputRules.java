package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The declared-input vocabulary of a route ({@code input:}, {@code page:},
 * {@code statusWhen:}).
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class InputRules implements LintRule {

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        Path appHome = context.appHome();
        for (RouteFile route : manifest.routes()) {
            lintInputs(appHome, route, findings);
        }
    }

    /**
     * Validates the declared-input vocabulary (roadmap Phase 40): a {@code head.yml}/
     * {@code options.yml} route is rejected here with a clear code instead of failing deep in
     * the route compiler ({@code TQL-YAML-1011}); a {@code pattern:} must compile
     * ({@code TQL-YAML-1012}); a string field's {@code format:} must be a known semantic
     * validator ({@code TQL-YAML-1013}); and a {@code requiredWhen:} must parse in the core
     * expression language ({@code TQL-YAML-1014}).
     */
    void lintInputs(Path appHome, RouteFile route, List<LintFinding> findings) {
        String source = appHome.relativize(route.source()).toString().replace('\\', '/');
        String method = route.httpMethod();
        if ("HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
            findings.add(new LintFinding("TQL-YAML-1011", "error", source,
                    "HEAD/OPTIONS route files are not servable — remove " + source));
        }
        if (route.definition().input() == null) {
            return;
        }
        io.tesseraql.yaml.model.PageSpec page = route.definition().pagination();
        if (page != null) {
            String recipe = route.definition().recipe();
            if (!"query-json".equals(recipe) && !"query-html".equals(recipe)) {
                findings.add(new LintFinding("TQL-YAML-1015", "error", source,
                        "page: is a query-json/query-html key (recipe is " + recipe + ")"));
            }
            if (io.tesseraql.yaml.model.PageSpec.KEYSET.equals(page.effectiveStrategy())
                    && (page.by() == null || page.by().isBlank())) {
                findings.add(new LintFinding("TQL-YAML-1016", "error", source,
                        "page: strategy keyset requires by: (the cursor column)"));
            }
            if (!io.tesseraql.yaml.model.PageSpec.OFFSET.equals(page.effectiveStrategy())
                    && !io.tesseraql.yaml.model.PageSpec.KEYSET.equals(page.effectiveStrategy())) {
                findings.add(new LintFinding("TQL-YAML-1016", "error", source,
                        "page: unknown strategy " + page.strategy() + " (offset or keyset)",
                        context.lineOf(route.source(), "page:"), null));
            }
            if (page.effectiveSize() < 1
                    || (page.maxSize() != null && page.maxSize() < page.effectiveSize())) {
                findings.add(new LintFinding("TQL-YAML-1017", "error", source,
                        "page: size must be >= 1 and maxSize >= size",
                        context.lineOf(route.source(), "page:"), null));
            }
            if (route.definition().main() != null && route.definition().main().file() != null) {
                Path sqlFile = route.source().getParent()
                        .resolve(route.definition().main().file()).normalize();
                String sql = java.nio.file.Files.isRegularFile(sqlFile)
                        ? context.content(sqlFile)
                        : null;
                if (sql != null && sql.toLowerCase(java.util.Locale.ROOT)
                        .matches("(?s).*\\b(limit|fetch)\\b.*")) {
                    findings.add(new LintFinding("TQL-YAML-1018", "warning", source,
                            "page: appends the pagination clause — the authored SQL should"
                                    + " not carry its own LIMIT/FETCH",
                            context.lineOf(route.source(), "page:"), null));
                }
            }
        }
        var response = route.definition().response();
        for (io.tesseraql.yaml.model.ResponseSpec.StatusWhen arm : statusArms(response)) {
            try {
                io.tesseraql.core.expr.ExpressionParser.parse(arm.when());
            } catch (RuntimeException ex) {
                findings.add(new LintFinding("TQL-YAML-1020", "error", source,
                        "statusWhen: condition does not parse: " + ex.getMessage(),
                        context.lineOf(route.source(), "statusWhen:"), null));
            }
            if (arm.status() < 100 || arm.status() > 599) {
                findings.add(new LintFinding("TQL-YAML-1020", "error", source,
                        "statusWhen: status " + arm.status() + " is not an HTTP status"));
            }
        }
        route.definition().input().forEach((name, field) -> {
            if (field.pattern() != null) {
                try {
                    java.util.regex.Pattern.compile(field.pattern());
                } catch (java.util.regex.PatternSyntaxException ex) {
                    findings.add(new LintFinding("TQL-YAML-1012", "error", source,
                            "input " + name + ": pattern does not compile: " + ex.getMessage(),
                            context.lineOf(route.source(), name + ":"), null));
                }
            }
            if ((field.type() == null || "string".equals(field.type())) && field.format() != null
                    && !io.tesseraql.yaml.model.InputField.STRING_FORMATS
                            .contains(field.format())) {
                findings.add(new LintFinding("TQL-YAML-1013", "error", source,
                        "input " + name + ": unknown string format " + field.format()
                                + " (known: "
                                + io.tesseraql.yaml.model.InputField.STRING_FORMATS + ")"));
            }
            if (field.requiredWhen() != null && !field.requiredWhen().isBlank()) {
                try {
                    io.tesseraql.core.expr.ExpressionParser.parse(field.requiredWhen());
                } catch (RuntimeException ex) {
                    findings.add(new LintFinding("TQL-YAML-1014", "error", source,
                            "input " + name + ": requiredWhen does not parse: "
                                    + ex.getMessage()));
                }
            }
        });
    }

    /** Both renderers' statusWhen arms (json + html), empty when absent. */
    private static java.util.List<io.tesseraql.yaml.model.ResponseSpec.StatusWhen> statusArms(
            io.tesseraql.yaml.model.ResponseSpec response) {
        java.util.List<io.tesseraql.yaml.model.ResponseSpec.StatusWhen> arms = new ArrayList<>();
        if (response != null && response.json() != null) {
            arms.addAll(response.json().statusWhen());
        }
        if (response != null && response.html() != null) {
            arms.addAll(response.html().statusWhen());
        }
        return arms;
    }
}
