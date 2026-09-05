package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

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

    private static final String UNSERVABLE_ROUTE_FILE = "TQL-YAML-1011";

    private static final String PAGE_ON_UNSUPPORTED_RECIPE = "TQL-YAML-1015";

    private static final String INVALID_PAGE_STRATEGY = "TQL-YAML-1016";

    private static final String INVALID_PAGE_SIZE = "TQL-YAML-1017";

    private static final String PAGED_SQL_DECLARES_LIMIT = "TQL-YAML-1018";

    private static final String INVALID_STATUS_WHEN = "TQL-YAML-1020";

    private static final String INVALID_INPUT_PATTERN = "TQL-YAML-1012";

    private static final String UNKNOWN_INPUT_FORMAT = "TQL-YAML-1013";

    private static final String INVALID_REQUIRED_WHEN = "TQL-YAML-1014";

    private static final String INVALID_ELEMENT_CONTRACT = "TQL-YAML-1027";

    private static final String INVALID_SORT_INPUT = "TQL-YAML-1028";

    private static final String INVALID_LOOKUP = "TQL-YAML-1059";

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    /** The manifest under lint — the lookup rule resolves `source:` against its routes. */
    private AppManifest manifest;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        this.manifest = manifest;
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
            findings.add(new LintFinding(UNSERVABLE_ROUTE_FILE, ERROR, source,
                    "HEAD/OPTIONS route files are not servable — remove " + source));
        }
        if (route.definition().input() == null) {
            return;
        }
        io.tesseraql.yaml.model.PageSpec page = route.definition().pagination();
        if (page != null) {
            String recipe = route.definition().recipe();
            if (!"query-json".equals(recipe) && !"query-html".equals(recipe)) {
                findings.add(new LintFinding(PAGE_ON_UNSUPPORTED_RECIPE, ERROR, source,
                        "page: is a query-json/query-html key (recipe is " + recipe + ")"));
            }
            if (io.tesseraql.yaml.model.PageSpec.KEYSET.equals(page.effectiveStrategy())
                    && page.effectiveBy().isEmpty()) {
                findings.add(new LintFinding(INVALID_PAGE_STRATEGY, ERROR, source,
                        "page: strategy keyset requires by: (the cursor column, or an ordered"
                                + " list for a composite cursor)"));
            }
            // Keyset is refused on a contract binding rather than published, because the
            // cursor it would advertise cannot be honoured: the `after` predicate lives in the
            // author's own statement, and a bundled contract has none. The page binder mints
            // offset 0 for every keyset request, so a `next` link would hand out an endless
            // chain of identical pages. Offset pagination - the half that works - is unaffected.
            if (io.tesseraql.yaml.model.PageSpec.KEYSET.equals(page.effectiveStrategy())
                    && route.definition().main() != null
                    && route.definition().main().isContract()) {
                findings.add(new LintFinding(INVALID_PAGE_STRATEGY, ERROR, source,
                        "page: strategy keyset is not available on a contract: binding - the"
                                + " after predicate lives in the statement, and a contract's is"
                                + " the framework's. Use strategy: offset",
                        context.lineOf(route.source(), "page:"), null));
            }
            if (page.effectiveBy().stream().anyMatch(column -> column == null
                    || column.isBlank())) {
                findings.add(new LintFinding(INVALID_PAGE_STRATEGY, ERROR, source,
                        "page: by: entries must be column names"));
            }
            if (!io.tesseraql.yaml.model.PageSpec.OFFSET.equals(page.effectiveStrategy())
                    && !io.tesseraql.yaml.model.PageSpec.KEYSET.equals(page.effectiveStrategy())
                    && !io.tesseraql.yaml.model.PageSpec.SNAPSHOT
                            .equals(page.effectiveStrategy())) {
                findings.add(new LintFinding(INVALID_PAGE_STRATEGY, ERROR, source,
                        "page: unknown strategy " + page.strategy()
                                + " (offset, keyset or snapshot)",
                        context.lineOf(route.source(), "page:"), null));
            }
            if (page.cap() != null && !io.tesseraql.yaml.model.PageSpec.SNAPSHOT
                    .equals(page.effectiveStrategy())) {
                findings.add(new LintFinding(INVALID_PAGE_STRATEGY, ERROR, source,
                        "page: cap: is a strategy: snapshot key"));
            }
            if (page.effectiveSize() < 1
                    || (page.maxSize() != null && page.maxSize() < page.effectiveSize())) {
                findings.add(new LintFinding(INVALID_PAGE_SIZE, ERROR, source,
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
                    findings.add(new LintFinding(PAGED_SQL_DECLARES_LIMIT, WARNING, source,
                            "page: appends the pagination clause — the authored SQL should"
                                    + " not carry its own LIMIT/FETCH",
                            context.lineOf(route.source(), "page:"), null));
                }
            }
        }
        var response = route.definition().response();
        for (io.tesseraql.yaml.model.ResponseSpec.StatusWhen arm : statusArms(response)) {
            try {
                io.tesseraql.core.expr.ExpressionParser.parse(arm.when(), context.functions());
            } catch (RuntimeException ex) {
                findings.add(new LintFinding(INVALID_STATUS_WHEN, ERROR, source,
                        "statusWhen: condition does not parse: " + ex.getMessage(),
                        context.lineOf(route.source(), "statusWhen:"), null));
            }
            if (arm.status() < 100 || arm.status() > 599) {
                findings.add(new LintFinding(INVALID_STATUS_WHEN, ERROR, source,
                        "statusWhen: status " + arm.status() + " is not an HTTP status"));
            }
        }
        route.definition().input().forEach((name, field) -> {
            lintField(source, route, name, field, findings);
            lintElementContract(source, route, name, field, findings);
        });
    }

    /**
     * The constraint vocabulary of one declared field, wherever it sits: a route's own
     * {@code input:} entry, or a field of an object array's element ({@code items.fields:}),
     * whose declarations are the same {@code inputField} and earn the same checks.
     */
    private void lintField(String source, RouteFile route, String name,
            io.tesseraql.yaml.model.InputField field, List<LintFinding> findings) {
        if (field.pattern() != null) {
            try {
                java.util.regex.Pattern.compile(field.pattern());
            } catch (java.util.regex.PatternSyntaxException ex) {
                findings.add(new LintFinding(INVALID_INPUT_PATTERN, ERROR, source,
                        "input " + name + ": pattern does not compile: " + ex.getMessage(),
                        context.lineOf(route.source(), name + ":"), null));
            }
        }
        if ((field.type() == null || "string".equals(field.type())) && field.format() != null
                && !io.tesseraql.yaml.model.InputField.STRING_FORMATS
                        .contains(field.format())) {
            findings.add(new LintFinding(UNKNOWN_INPUT_FORMAT, ERROR, source,
                    "input " + name + ": unknown string format " + field.format()
                            + " (known: "
                            + io.tesseraql.yaml.model.InputField.STRING_FORMATS + ")"));
        }
        boolean sortTyped = "sort".equals(field.type());
        boolean hasColumns = field.columns() != null && !field.columns().isEmpty();
        if (sortTyped != hasColumns) {
            findings.add(new LintFinding(INVALID_SORT_INPUT, ERROR, source, sortTyped
                    ? "input " + name + ": type sort requires columns: (the sortable-column"
                            + " allowlist its keys validate against)"
                    : "input " + name + ": columns: is a type: sort key"));
        }
        if (field.requiredWhen() != null && !field.requiredWhen().isBlank()) {
            try {
                io.tesseraql.core.expr.ExpressionParser.parse(field.requiredWhen(),
                        context.functions());
            } catch (RuntimeException ex) {
                findings.add(new LintFinding(INVALID_REQUIRED_WHEN, ERROR, source,
                        "input " + name + ": requiredWhen does not parse: "
                                + ex.getMessage()));
            }
        }
        lintLookup(source, route, name, field, findings);
    }

    /**
     * The {@code lookup:} declaration (docs/reference-lookup.md decision 1,
     * {@code TQL-YAML-1059}): all three keys, a {@code source:} that names a GET route — the
     * compiler fails the build on a dangling one, and this is the same check where an author
     * reads findings — and that route carrying the SQL query the resolve leg re-renders.
     */
    private void lintLookup(String source, RouteFile route, String name,
            io.tesseraql.yaml.model.InputField field, List<LintFinding> findings) {
        io.tesseraql.yaml.model.InputField.LookupSpec lookup = field.lookup();
        if (lookup == null) {
            return;
        }
        Integer line = context.lineOf(route.source(), name + ":");
        if (name.contains(".items.fields.")) {
            findings.add(new LintFinding(INVALID_LOOKUP, ERROR, source,
                    "input " + name + ": lookup: is not supported on line-item element fields",
                    line, null));
            return;
        }
        if (isBlank(lookup.source()) || isBlank(lookup.code()) || isBlank(lookup.label())) {
            findings.add(new LintFinding(INVALID_LOOKUP, ERROR, source,
                    "input " + name + ": lookup: declares source: (the GET query route),"
                            + " code: and label: (its row columns) — all three",
                    line, null));
            return;
        }
        RouteFile referenced = null;
        for (RouteFile candidate : manifest.routes()) {
            if ("GET".equalsIgnoreCase(candidate.httpMethod())
                    && candidate.urlPath().equals(lookup.source())) {
                referenced = candidate;
                break;
            }
        }
        if (referenced == null) {
            findings.add(new LintFinding(INVALID_LOOKUP, ERROR, source,
                    "input " + name + ": lookup source " + lookup.source()
                            + " matches no GET route",
                    line, null));
            return;
        }
        io.tesseraql.yaml.model.Binding main = referenced.definition().main();
        if (main == null || main.file() == null || main.isHttp()) {
            findings.add(new LintFinding(INVALID_LOOKUP, ERROR, source,
                    "input " + name + ": lookup source " + lookup.source()
                            + " declares no main SQL query for the resolve leg to re-render",
                    line, null));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * The shape of an object array's element contract ({@code TQL-YAML-1027}). A line is flat
     * and belongs to an array: {@code fields:} is one level deep, it is not a second spelling of
     * the scalar {@code type:}, and it says nothing about who may write a row — the checks the
     * declaration cannot enforce silently are refused instead (docs/declarative-validation.md,
     * "Line items").
     */
    private void lintElementContract(String source, RouteFile route, String name,
            io.tesseraql.yaml.model.InputField field, List<LintFinding> findings) {
        io.tesseraql.yaml.model.InputField.InputItems items = field.items();
        if (items == null || !items.hasFields()) {
            return;
        }
        Integer line = context.lineOf(route.source(), name + ":");
        if (!"array".equals(field.type())) {
            findings.add(new LintFinding(INVALID_ELEMENT_CONTRACT, ERROR, source,
                    "input " + name + ": items.fields: declares the elements of an array —"
                            + " set type: array",
                    line, null));
        }
        if (items.type() != null) {
            findings.add(new LintFinding(INVALID_ELEMENT_CONTRACT, ERROR, source,
                    "input " + name + ": items: declares both type: (scalar elements) and"
                            + " fields: (object elements) — keep one",
                    line, null));
        }
        items.fields().forEach((elementName, element) -> {
            String at = name + ".items.fields." + elementName;
            if ("array".equals(element.type()) || element.items() != null) {
                findings.add(new LintFinding(INVALID_ELEMENT_CONTRACT, ERROR, source,
                        "input " + at + ": a line is flat — an array inside items.fields: is"
                                + " not declarable",
                        line, null));
            }
            if (element.policy() != null) {
                findings.add(new LintFinding(INVALID_ELEMENT_CONTRACT, ERROR, source,
                        "input " + at + ": policy: authorizes a form field, and a line is not"
                                + " one — gate the whole array instead",
                        line, null));
            }
            lintField(source, route, at, element, findings);
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
