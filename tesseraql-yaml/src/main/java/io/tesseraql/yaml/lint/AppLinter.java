package io.tesseraql.yaml.lint;

import io.tesseraql.core.expr.Expr;
import io.tesseraql.core.expr.ExpressionParser;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.manifest.ScopeFile;
import io.tesseraql.yaml.manifest.WorkflowFile;
import io.tesseraql.yaml.model.DeadlineSpec;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.MatchArm;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.ScopeDefinition;
import io.tesseraql.yaml.model.SqlBinding;
import io.tesseraql.yaml.model.StateSpec;
import io.tesseraql.yaml.model.TransitionSpec;
import io.tesseraql.yaml.model.WhenCondition;
import io.tesseraql.yaml.model.WorkflowDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Statically lints an app home, independent of Maven, so it is unit-testable (design ch. 18, 20).
 *
 * <p>The first rule set checks recipes are known, referenced SQL files exist, and route security
 * policies are defined (deny-by-default safety). More rules (large-data, tenant predicate, field
 * authorization) are added alongside their features.
 */
public final class AppLinter {

    private static final Set<String> KNOWN_ROUTE_RECIPES = Set.of("query-json", "command-json",
            "query-html", "page", "query-export", "file-import", "file-export", "webhook");

    /** The servable route recipes — exposed so the shipped JSON Schema is drift-tested. */
    public static Set<String> knownRouteRecipes() {
        return KNOWN_ROUTE_RECIPES;
    }

    private static final Set<String> KNOWN_AUTH_MODES = Set.of("bearer", "browser", "apiKey",
            "mtls", "public");

    /**
     * The route auth modes — exposed so the shipped JSON Schema's {@code security.auth} enum and
     * the Studio route form are drift-tested against one source (roadmap Phase 57; the hand-coded
     * form list had already lost {@code public}).
     */
    public static Set<String> knownAuthModes() {
        return KNOWN_AUTH_MODES;
    }

    private static final Set<String> KNOWN_INPUT_TYPES = Set.of("string", "integer", "number",
            "boolean", "date", "array");

    /** The declared-input types — exposed for the same drift tests as {@link #knownAuthModes()}. */
    public static Set<String> knownInputTypes() {
        return KNOWN_INPUT_TYPES;
    }
    /** Recipes whose SQL pipeline is a read, where a route-level {@code datasource:} applies
     * (roadmap Phase 53). */
    private static final Set<String> READ_DATASOURCE_RECIPES = Set.of("query-json", "query-html",
            "page", "query-export");
    /** Recipes whose whole single-connection transaction may move to a named connector (the
     * projection pattern, docs/multi-datasource.md) — as long as the route stays plain SQL. */
    private static final Set<String> TRANSACTIONAL_DATASOURCE_RECIPES = Set.of("command-json",
            "webhook", "queue-consume");
    /** Recipes an application-declared MCP tool may use (roadmap Phase 24 follow-on). */
    private static final Set<String> KNOWN_TOOL_RECIPES = Set.of("query-json", "command-json");
    /** Recipes an MCP Apps UI resource may use - both render HTML (roadmap Phase 24). */
    private static final Set<String> KNOWN_UI_RECIPES = Set.of("query-html", "page");
    /** Recipes a {@code publish:} block may ride - the transactional commands (roadmap Phase 27). */
    private static final Set<String> PUBLISH_RECIPES = Set.of("command-json", "webhook",
            "queue-consume");
    /** The MCP Apps uri scheme a UI resource is addressed by (SEP-1865). */
    private static final String UI_SCHEME = "ui://";

    /** Loads and lints the app home, returning all findings. */
    public List<LintFinding> lint(Path appHome) {
        // The manifest loader absolutizes every source path; a relative app home (the
        // documented `tesseraql lint --app .` form) must match, or relativizing the
        // sources for finding locations throws.
        appHome = appHome.toAbsolutePath().normalize();
        AppManifest manifest = new ManifestLoader().load(appHome);
        List<LintFinding> findings = new ArrayList<>();
        for (RouteFile route : manifest.routes()) {
            lintRoute(appHome, manifest.config(), route, findings);
        }
        for (io.tesseraql.yaml.manifest.JobFile job : manifest.jobs()) {
            lintJob(appHome, manifest.config(), job, findings);
        }
        for (io.tesseraql.yaml.manifest.ToolFile tool : manifest.tools()) {
            lintTool(appHome, manifest.config(), tool, findings);
        }
        for (io.tesseraql.yaml.manifest.ResourceFile resource : manifest.resources()) {
            lintResource(appHome, manifest.config(), resource, findings);
        }
        for (io.tesseraql.yaml.manifest.UiResourceFile ui : manifest.uiResources()) {
            lintUiResource(appHome, manifest.config(), ui, findings);
        }
        for (RouteFile consumer : manifest.consumers()) {
            lintConsumer(appHome, manifest.config(), consumer, findings);
        }
        lintDuplicateResourceUris(appHome, manifest, findings);
        lintToolUiLinks(appHome, manifest, findings);
        lintI18n(appHome, manifest, findings);
        lintSecurityConfig(appHome, manifest, findings);
        lintScopes(appHome, manifest, findings);
        lintPreferences(appHome, findings);
        lintOrgUnitConfig(manifest.config(), findings);
        lintWorkflows(appHome, manifest, findings);
        lintWorkflowConfig(manifest.config(), findings);
        lintAttachments(appHome, manifest, findings);
        lintObjectStorageEgress(appHome, manifest, findings);
        lintViews(appHome, manifest, findings);
        lintDuckDb(appHome, manifest, findings);
        for (RouteFile route : manifest.routes()) {
            lintInputs(appHome, route, findings);
        }
        return findings;
    }

    /**
     * Validates the declared-input vocabulary (roadmap Phase 40): a {@code head.yml}/
     * {@code options.yml} route is rejected here with a clear code instead of failing deep in
     * the route compiler ({@code TQL-YAML-1011}); a {@code pattern:} must compile
     * ({@code TQL-YAML-1012}); a string field's {@code format:} must be a known semantic
     * validator ({@code TQL-YAML-1013}); and a {@code requiredWhen:} must parse in the core
     * expression language ({@code TQL-YAML-1014}).
     */
    private void lintInputs(Path appHome, RouteFile route, List<LintFinding> findings) {
        String source = appHome.relativize(route.source()).toString().replace('\\', '/');
        String method = route.httpMethod();
        if ("HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
            findings.add(new LintFinding("TQL-YAML-1011", "error", source,
                    "HEAD/OPTIONS route files are not servable — remove " + source));
        }
        if (route.definition().input() == null) {
            return;
        }
        io.tesseraql.yaml.model.PageSpec page = route.definition().page();
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
                        lineOf(route.source(), "page:"), null));
            }
            if (page.effectiveSize() < 1
                    || (page.maxSize() != null && page.maxSize() < page.effectiveSize())) {
                findings.add(new LintFinding("TQL-YAML-1017", "error", source,
                        "page: size must be >= 1 and maxSize >= size",
                        lineOf(route.source(), "page:"), null));
            }
            if (route.definition().sql() != null && route.definition().sql().file() != null) {
                Path sqlFile = route.source().getParent()
                        .resolve(route.definition().sql().file()).normalize();
                try {
                    if (java.nio.file.Files.isRegularFile(sqlFile) && java.nio.file.Files
                            .readString(sqlFile).toLowerCase(java.util.Locale.ROOT)
                            .matches("(?s).*\\b(limit|fetch)\\b.*")) {
                        findings.add(new LintFinding("TQL-YAML-1018", "warning", source,
                                "page: appends the pagination clause — the authored SQL should"
                                        + " not carry its own LIMIT/FETCH",
                                lineOf(route.source(), "page:"), null));
                    }
                } catch (java.io.IOException ignored) {
                    // unreadable SQL surfaces through other lint rules
                }
            }
        }
        var response = route.definition().response();
        var json = response == null ? null : response.json();
        if (json != null) {
            for (io.tesseraql.yaml.model.ResponseSpec.NestSpec nestSpec : json.nest()) {
                boolean bodyHasKey = json.body() instanceof java.util.Map<?, ?> bodyMap
                        && bodyMap.containsKey(nestSpec.into());
                var queries = route.definition().queries();
                boolean childDeclared = queries != null
                        && queries.containsKey(nestSpec.children());
                if (!bodyHasKey || !childDeclared || nestSpec.on().size() != 1
                        || nestSpec.as() == null || nestSpec.as().isBlank()) {
                    findings.add(new LintFinding("TQL-YAML-1019", "error", source,
                            "nest: needs into: (a body key), children: (a named query), as:,"
                                    + " and a single on: parentColumn: childColumn entry",
                            lineOf(route.source(), "nest:"), null));
                }
            }
        }
        for (io.tesseraql.yaml.model.ResponseSpec.StatusWhen arm : statusArms(response)) {
            try {
                io.tesseraql.core.expr.ExpressionParser.parse(arm.when());
            } catch (RuntimeException ex) {
                findings.add(new LintFinding("TQL-YAML-1020", "error", source,
                        "statusWhen: condition does not parse: " + ex.getMessage(),
                        lineOf(route.source(), "statusWhen:"), null));
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
                            lineOf(route.source(), name + ":"), null));
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

    /** A live-view topic name: lowercase dot/dash-separated segments (docs/realtime.md). */
    private static final java.util.regex.Pattern TOPIC_NAME = java.util.regex.Pattern
            .compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

    /**
     * Live-view emit lints (docs/realtime.md): emit: is a command-json key (TQL-YAML-1038, the
     * topics broadcast after that command's commit), and a topic name must match the slug shape
     * (TQL-YAML-1039) so it survives URL, SSE event-name, and selector contexts unquoted.
     */
    private void lintEmit(RouteFile route, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        if (definition.emit().isEmpty()) {
            return;
        }
        if (!"command-json".equals(definition.recipe())) {
            findings.add(new LintFinding("TQL-YAML-1038", "error", source,
                    "emit: is only supported on command-json routes, not '"
                            + definition.recipe() + "'"));
        }
        for (String topic : definition.emit()) {
            if (topic == null || !TOPIC_NAME.matcher(topic).matches()) {
                findings.add(new LintFinding("TQL-YAML-1039", "error", source,
                        "emit: topic '" + topic + "' is not a legal topic name"
                                + " (lowercase dot/dash-separated segments)"));
            }
        }
    }

    /**
     * refreshOn: lints (docs/realtime.md): live refresh replaces the region wholesale, so a
     * form — which would lose in-progress input — cannot declare it (TQL-VIEW-3311), and a
     * topic no command emits will never fire — almost always a typo (TQL-VIEW-3312, a
     * warning: another environment's routes may emit it).
     */
    private void lintRefreshOn(AppManifest manifest, String source,
            io.tesseraql.yaml.view.ViewSpec spec, List<LintFinding> findings) {
        String topic = spec.refreshOn();
        if (topic == null || topic.isBlank()) {
            return;
        }
        if (io.tesseraql.yaml.view.ViewSpec.FORM.equals(spec.view())) {
            findings.add(new LintFinding("TQL-VIEW-3311", "error", source,
                    "view " + spec.id() + ": refreshOn: is not a form-view key — a live"
                            + " replacement would discard in-progress input"));
            return;
        }
        if (!TOPIC_NAME.matcher(topic.trim()).matches()) {
            findings.add(new LintFinding("TQL-YAML-1039", "error", source,
                    "refreshOn: topic '" + topic + "' is not a legal topic name"
                            + " (lowercase dot/dash-separated segments)"));
            return;
        }
        boolean emitted = manifest.routes().stream()
                .anyMatch(route -> route.definition().emit().contains(topic.trim()));
        if (!emitted) {
            findings.add(new LintFinding("TQL-VIEW-3312", "warning", source,
                    "view " + spec.id() + ": refreshOn: topic '" + topic.trim()
                            + "' is emitted by no route — the view will never refresh"));
        }
    }

    /**
     * Validates declarative views (roadmap Phase 39, docs/declarative-views.md): the
     * {@code response.html.view} reference resolves and parses ({@code TQL-VIEW-3301/3302}), is
     * not combined with {@code template:} ({@code TQL-VIEW-3302}), a form's {@code action:} names
     * a POST route with an {@code input:} block ({@code TQL-VIEW-3303}) whose fields the view's
     * {@code fields:} entries actually declare ({@code TQL-VIEW-3304}) with known widgets
     * ({@code TQL-VIEW-3305}); and an app's {@code templates/tql/view/*.html} pattern override
     * carries the expected fragment signature ({@code TQL-VIEW-3307}, warning).
     */
    private void lintViews(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        for (RouteFile route : manifest.routes()) {
            var response = route.definition().response();
            var html = response == null ? null : response.html();
            if (html == null || html.view() == null) {
                continue;
            }
            String source = appHome.relativize(route.source()).toString().replace('\\', '/');
            if (html.template() != null) {
                findings.add(new LintFinding("TQL-VIEW-3302", "error", source,
                        "response.html declares both template: and view: — they are mutually"
                                + " exclusive"));
            }
            Path routeDir = route.source().getParent();
            Path colocated = routeDir.resolve(html.view()).normalize();
            Path file = java.nio.file.Files.isRegularFile(colocated)
                    ? colocated
                    : appHome.resolve("templates").resolve(html.view()).normalize();
            if (!java.nio.file.Files.isRegularFile(file)) {
                findings.add(new LintFinding("TQL-VIEW-3302", "error", source,
                        "view: " + html.view() + " does not resolve (colocated or templates/)"));
                continue;
            }
            io.tesseraql.yaml.view.ViewSpec spec;
            try {
                spec = io.tesseraql.yaml.view.ViewSpec.parse(file);
            } catch (io.tesseraql.core.error.TqlException ex) {
                findings.add(new LintFinding("TQL-VIEW-3301", "error", source, ex.getMessage()));
                continue;
            }
            if (io.tesseraql.yaml.view.ViewSpec.FORM.equals(spec.view())) {
                lintFormView(manifest, source, spec, findings);
            }
            lintRefreshOn(manifest, source, spec, findings);
            for (String slotName : spec.slots().keySet()) {
                java.util.Set<String> allowed = io.tesseraql.yaml.view.ViewSpec
                        .slotsFor(spec.view());
                if (!allowed.contains(slotName)) {
                    findings.add(new LintFinding("TQL-VIEW-3306", "error", source,
                            "view " + spec.id() + ": unknown slot " + slotName + " (a "
                                    + spec.view() + " view offers " + allowed + ")"));
                    continue;
                }
                String ref = spec.slots().get(slotName);
                int separator = ref.indexOf("::");
                String template = separator < 1 ? ref : ref.substring(0, separator).trim();
                Path slotColocated = routeDir.resolve(template).normalize();
                Path slotFile = java.nio.file.Files.isRegularFile(slotColocated)
                        ? slotColocated
                        : appHome.resolve("templates").resolve(template).normalize();
                if (separator < 1 || !java.nio.file.Files.isRegularFile(slotFile)) {
                    findings.add(new LintFinding("TQL-VIEW-3302", "error", source,
                            "view " + spec.id() + ": slot " + slotName + " reference " + ref
                                    + " does not resolve ('<template> :: <fragment>')"));
                }
            }
            for (io.tesseraql.yaml.view.ViewSpec.Child child : spec.children()) {
                var queries = route.definition().queries();
                if (!"sql".equals(child.source())
                        && (queries == null || !queries.containsKey(child.source()))) {
                    findings.add(new LintFinding("TQL-VIEW-3308", "error", source,
                            "view " + spec.id() + ": children source " + child.source()
                                    + " is not a named query of the route"));
                }
            }
            for (io.tesseraql.yaml.view.ViewSpec.Panel panel : spec.panels()) {
                String panelSource = panel.source() == null || panel.source().isBlank()
                        ? "sql"
                        : panel.source();
                var queries = route.definition().queries();
                if (!"sql".equals(panelSource)
                        && (queries == null || !queries.containsKey(panelSource))) {
                    findings.add(new LintFinding("TQL-VIEW-3308", "error", source,
                            "view " + spec.id() + ": panel source " + panelSource
                                    + " is not a named query of the route"));
                }
            }
            if (io.tesseraql.yaml.view.ViewSpec.LIST.equals(spec.view())) {
                var inputs = route.definition().input();
                if (spec.search() != null
                        && (inputs == null || !inputs.containsKey(spec.search()))) {
                    findings.add(new LintFinding("TQL-VIEW-3309", "error", source,
                            "view " + spec.id() + ": search: " + spec.search()
                                    + " is not a declared input of the route"));
                }
                boolean sortable = spec.columns().stream()
                        .anyMatch(io.tesseraql.yaml.view.ViewSpec.Column::isSortable);
                if (sortable && (inputs == null || !inputs.containsKey("sort")
                        || !inputs.containsKey("dir"))) {
                    findings.add(new LintFinding("TQL-VIEW-3310", "error", source,
                            "view " + spec.id() + ": sortable columns need the route to declare"
                                    + " sort and dir inputs its SQL applies"));
                }
            }
        }
        lintViewOverrides(appHome, findings);
    }

    /** A form view's action route exists, declares inputs, and covers every fields: entry. */
    private void lintFormView(AppManifest manifest, String source,
            io.tesseraql.yaml.view.ViewSpec spec, List<LintFinding> findings) {
        RouteFile action = null;
        for (RouteFile candidate : manifest.routes()) {
            if ("POST".equalsIgnoreCase(candidate.httpMethod())
                    && candidate.urlPath().equals(spec.action())) {
                action = candidate;
                break;
            }
        }
        if (action == null) {
            findings.add(new LintFinding("TQL-VIEW-3303", "error", source,
                    "view " + spec.id() + ": action " + spec.action()
                            + " matches no POST route"));
            return;
        }
        var inputs = action.definition().input();
        if (inputs == null || inputs.isEmpty()) {
            findings.add(new LintFinding("TQL-VIEW-3303", "error", source,
                    "view " + spec.id() + ": action route " + action.definition().id()
                            + " declares no input: block to derive fields from"));
            return;
        }
        for (io.tesseraql.yaml.view.ViewSpec.Field field : spec.fields()) {
            if (!inputs.containsKey(field.name())) {
                findings.add(new LintFinding("TQL-VIEW-3304", "error", source,
                        "view " + spec.id() + ": field " + field.name()
                                + " is not declared by the action route's input: block"));
            }
            if (field.widget() != null
                    && !io.tesseraql.yaml.view.ViewSpec.WIDGETS.contains(field.widget())) {
                findings.add(new LintFinding("TQL-VIEW-3305", "error", source,
                        "view " + spec.id() + ": unknown widget " + field.widget()
                                + " (known: " + io.tesseraql.yaml.view.ViewSpec.WIDGETS + ")"));
            }
        }
    }

    /**
     * An L2 pattern override must carry the pattern's fragment signature so it stays compatible
     * with fragment-level composition (docs/declarative-views.md; warning, not error — the whole
     * file still renders today).
     */
    private void lintViewOverrides(Path appHome, List<LintFinding> findings) {
        Path overrides = appHome.resolve("templates").resolve("tql").resolve("view");
        if (!java.nio.file.Files.isDirectory(overrides)) {
            return;
        }
        try (var files = java.nio.file.Files.list(overrides)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".html"))
                    .sorted().toList()) {
                String name = file.getFileName().toString();
                String expected = name.startsWith("field")
                        ? "th:fragment=\"field(f)\""
                        : "th:fragment=\"view(v)\"";
                String content = java.nio.file.Files.readString(file);
                if (!content.contains(expected)) {
                    findings.add(new LintFinding("TQL-VIEW-3307", "warning",
                            appHome.relativize(file).toString().replace('\\', '/'),
                            "view pattern override lacks the expected " + expected
                                    + " signature (docs/declarative-views.md)"));
                }
            }
        } catch (java.io.IOException ex) {
            findings.add(new LintFinding("TQL-VIEW-3307", "warning", "templates/tql/view",
                    "view pattern overrides could not be read: " + ex.getMessage()));
        }
    }

    /** Validates approval-workflow configuration (roadmap Phase 28): a known {@code mode}. */
    private void lintWorkflowConfig(AppConfig config, List<LintFinding> findings) {
        String mode = config.getString("tesseraql.workflow.mode").orElse(null);
        if (mode != null && !"managed".equalsIgnoreCase(mode) && !"app".equalsIgnoreCase(mode)) {
            findings.add(new LintFinding("TQL-WORKFLOW-3110", "error", "config",
                    "tesseraql.workflow.mode must be 'managed' or 'app', not '" + mode + "'"));
        }
    }

    /**
     * Validates {@code config/preferences.yml} (roadmap Phase 48 slice 5) by loading it the
     * way the runtime does: TQL-YAML-1030 parse/key/duplicate, 1031 unknown type, 1032 choice
     * without options, 1033 default outside the acceptable values.
     */
    private void lintPreferences(Path appHome, List<LintFinding> findings) {
        try {
            io.tesseraql.yaml.account.PreferencesSpec.load(appHome);
        } catch (io.tesseraql.core.error.TqlException ex) {
            findings.add(new LintFinding(ex.code().toString(), "error",
                    "config/preferences.yml", ex.getMessage()));
        }
    }

    /** Validates org-unit configuration (roadmap Phase 29 slice 2): a known {@code mode}. */
    private void lintOrgUnitConfig(AppConfig config, List<LintFinding> findings) {
        String mode = config.getString("tesseraql.orgunit.mode").orElse(null);
        if (mode != null && !"managed".equalsIgnoreCase(mode) && !"app".equalsIgnoreCase(mode)) {
            findings.add(new LintFinding("TQL-SCOPE-3020", "error", "config",
                    "tesseraql.orgunit.mode must be 'managed' or 'app', not '" + mode + "'"));
        }
    }

    /**
     * Lints authentication configuration (roadmap Phase 25): a bearer JWT picks a supported
     * algorithm and a single matching key source (no algorithm confusion), and an
     * {@code auth: apiKey} route requires API-key config whose clients each store a key hash. Reads
     * raw config nodes — never resolving secret placeholders — so the lint runs without a live
     * secret store.
     */
    private void lintSecurityConfig(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        AppConfig config = manifest.config();
        if (config.navigate("tesseraql.security.jwt") != null) {
            lintJwtConfig(config, findings);
        }
        lintApiKeyConfig(appHome, manifest, config, findings);
        lintMtlsConfig(appHome, manifest, config, findings);
        lintOidcConfig(config, findings);
        lintSecurityDefaults(appHome, manifest, config, findings);
        lintFieldDomains(appHome, manifest, findings);
        lintResponseHeaderDefaults(appHome, manifest, config, findings);
        lintAmbientPrincipal(appHome, manifest, findings);
    }

    /** The ambient {@code principal.*} bind fields (docs/two-way-sql.md "Ambient binds"). */
    private static final Pattern AMBIENT_PRINCIPAL = Pattern
            .compile("principal\\.(subject|loginId|tenantId|roles|permissions|groups)");

    /**
     * Lints the ambient {@code principal.*} binds (docs/ambient-params.md): a bind on a route
     * that never carries an authenticated principal — {@code auth: public}, no effective
     * security at all, or a signature-authenticated webhook — can only fail at runtime as an
     * unbound parameter, so it is an error here ({@code TQL-SEC-4136}). A {@code params:} entry
     * that merely renames an ambient field is flagged toward the ambient spelling
     * ({@code TQL-SEC-4137}) — a migration nudge, not a rule.
     */
    private void lintAmbientPrincipal(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        for (RouteFile route : manifest.routes()) {
            RouteDefinition def = route.definition();
            String source = appHome.relativize(route.source()).toString().replace('\\', '/');
            boolean noPrincipal = "webhook".equals(def.recipe())
                    || def.security() == null
                    || "public".equals(def.security().auth());
            if (noPrincipal) {
                for (String bind : principalBinds(route)) {
                    findings.add(new LintFinding("TQL-SEC-4136", "error", source,
                            "Route '" + def.id() + "' binds '" + bind + "' but never carries an"
                                    + " authenticated principal — the bind can only fail as an"
                                    + " unbound parameter at runtime"));
                }
            }
            sqlParamMaps(def).forEach((where, params) -> params.forEach((bindName, expr) -> {
                if (expr != null && AMBIENT_PRINCIPAL.matcher(expr).matches()) {
                    findings.add(new LintFinding("TQL-SEC-4137", "warning", source,
                            "Route '" + def.id() + "' " + where + " wires '" + bindName + ": "
                                    + expr + "' — the ambient bind /* " + expr + " */ makes the"
                                    + " wiring unnecessary"));
                }
            }));
        }
    }

    /**
     * Every {@code params:} map feeding a 2-way SQL <em>file</em>, labeled for the finding
     * message. Service invocations ({@code sql.service:}) are excluded: their params are the
     * service's arguments, not SQL binds, so the ambient namespace does not replace them — the
     * bundled Studio/account apps wire {@code principal.*} into services exactly this way, by
     * design.
     */
    private static Map<String, Map<String, String>> sqlParamMaps(RouteDefinition def) {
        Map<String, Map<String, String>> maps = new LinkedHashMap<>();
        if (def.sql() != null && def.sql().file() != null && def.sql().params() != null) {
            maps.put("sql.params", def.sql().params());
        }
        def.steps().forEach((name, step) -> {
            if (step.file() != null && step.params() != null) {
                maps.put("step '" + name + "'", step.params());
            }
        });
        def.queries().forEach((name, query) -> {
            if (query.file() != null && query.params() != null) {
                maps.put("query '" + name + "'", query.params());
            }
        });
        def.validate().forEach((name, rule) -> {
            if (rule.file() != null && rule.params() != null) {
                maps.put("validation rule '" + name + "'", rule.params());
            }
        });
        return maps;
    }

    /** The distinct {@code principal.*} bind expressions across a route's parseable SQL files. */
    private Set<String> principalBinds(RouteFile route) {
        Set<String> found = new LinkedHashSet<>();
        Path dir = route.source().getParent();
        List<String> files = new ArrayList<>();
        RouteDefinition def = route.definition();
        if (def.sql() != null && def.sql().file() != null) {
            files.add(def.sql().file());
        }
        def.steps().values().forEach(step -> {
            if (step.file() != null) {
                files.add(step.file());
            }
        });
        def.queries().values().forEach(query -> {
            if (query.file() != null) {
                files.add(query.file());
            }
        });
        def.validate().values().forEach(rule -> {
            if (rule.file() != null) {
                files.add(rule.file());
            }
        });
        for (String file : files) {
            Path sqlFile = dir.resolve(file).normalize();
            if (!Files.isRegularFile(sqlFile)) {
                continue;
            }
            try {
                collectPrincipalBinds(Sql2WayParser.parse(Files.readString(sqlFile)), found);
            } catch (Exception ignored) {
                // Unparseable SQL is its own lint's concern.
            }
        }
        return found;
    }

    private static void collectPrincipalBinds(List<SqlNode> nodes, Set<String> found) {
        for (SqlNode node : nodes) {
            switch (node) {
                case SqlNode.Bind bind -> {
                    if (AMBIENT_PRINCIPAL.matcher(bind.expressionSource().trim()).matches()) {
                        found.add(bind.expressionSource().trim());
                    }
                }
                case SqlNode.ListBind bind -> {
                    if (AMBIENT_PRINCIPAL.matcher(bind.expressionSource().trim()).matches()) {
                        found.add(bind.expressionSource().trim());
                    }
                }
                case SqlNode.If cond -> cond.branches()
                        .forEach(branch -> collectPrincipalBinds(branch.body(), found));
                case SqlNode.For loop -> collectPrincipalBinds(loop.body(), found);
                default -> {
                }
            }
        }
    }

    /**
     * Lints field domains (docs/field-domains.md): a route override that loosens a domain
     * constraint is exactly the drift domains exist to prevent, and a domain nothing references
     * is either dead or a missed reference. Duplicate names, unknown references, and operational
     * keys inside a domain already failed the manifest load (TQL-FIELD-4600..4603).
     */
    private void lintFieldDomains(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        io.tesseraql.yaml.domain.FieldDomains domains = io.tesseraql.yaml.domain.FieldDomains
                .load(appHome);
        if (domains.isEmpty()) {
            return;
        }
        Set<String> referenced = new HashSet<>();
        for (RouteFile route : manifest.routes()) {
            String source = appHome.relativize(route.source()).toString();
            route.definition().input().forEach((name, field) -> {
                if (field.domain() == null) {
                    return;
                }
                referenced.add(field.domain());
                InputField domain = domains.domains().get(field.domain());
                if (domain == null) {
                    return;
                }
                loosened(name, field, domain).forEach(what -> findings.add(new LintFinding(
                        "TQL-FIELD-4610", "warning", source,
                        "Field '" + name + "' loosens domain '" + field.domain() + "': " + what
                                + " — a loosened copy is the drift domains exist to prevent")));
            });
        }
        domains.domains().keySet().stream()
                .filter(name -> !referenced.contains(name))
                .forEach(name -> findings.add(new LintFinding("TQL-FIELD-4611", "warning",
                        "domains",
                        "Domain '" + name + "' is declared but never referenced")));
    }

    /** The ways the merged field is looser than its domain, as human-readable clauses. */
    private static List<String> loosened(String name, InputField merged, InputField domain) {
        List<String> ways = new ArrayList<>();
        if (domain.maxLength() != null && merged.maxLength() != null
                && merged.maxLength() > domain.maxLength()) {
            ways.add("maxLength " + merged.maxLength() + " > " + domain.maxLength());
        }
        if (domain.minLength() != null && merged.minLength() != null
                && merged.minLength() < domain.minLength()) {
            ways.add("minLength " + merged.minLength() + " < " + domain.minLength());
        }
        if (domain.min() != null && merged.min() != null
                && merged.min().compareTo(domain.min()) < 0) {
            ways.add("min " + merged.min() + " < " + domain.min());
        }
        if (domain.max() != null && merged.max() != null
                && merged.max().compareTo(domain.max()) > 0) {
            ways.add("max " + merged.max() + " > " + domain.max());
        }
        if (domain.enumValues() != null && merged.enumValues() != null
                && !domain.enumValues().containsAll(merged.enumValues())) {
            ways.add("enum adds values outside the domain's set");
        }
        return ways;
    }

    /**
     * Lints routes against the app-wide default response headers (docs/route-defaults.md): a
     * route restating a default identically is leftover copy-paste the default replaces, and a
     * route suppressing or wildcard-broadening one is weakening a security control — either
     * deliberate (own the override) or the drift the defaults exist to end. Only routes are
     * compared; with no declared defaults there is nothing to lint.
     */
    private void lintResponseHeaderDefaults(Path appHome, AppManifest manifest, AppConfig config,
            List<LintFinding> findings) {
        io.tesseraql.yaml.config.ResponseHeaderDefaults defaults;
        try {
            defaults = io.tesseraql.yaml.config.ResponseHeaderDefaults.from(config);
        } catch (io.tesseraql.core.error.TqlException ex) {
            // The manifest loader does not parse this key; surface the malformed map here.
            findings.add(new LintFinding("TQL-SEC-4135", "error", "config", ex.getMessage()));
            return;
        }
        if (defaults.isEmpty()) {
            return;
        }
        for (RouteFile route : manifest.routes()) {
            var response = route.definition().response();
            if (response == null || response.html() == null
                    || response.html().headers().isEmpty()) {
                continue;
            }
            String source = appHome.relativize(route.source()).toString();
            for (var entry : response.html().headers().entrySet()) {
                String name = entry.getKey();
                String declared = String.valueOf(entry.getValue());
                String fallback = defaults.headers().get(name);
                if (fallback == null) {
                    continue;
                }
                if (declared.equals(fallback)) {
                    findings.add(new LintFinding("TQL-SEC-4133", "warning", source,
                            "Route '" + route.definition().id() + "' restates the default"
                                    + " response header '" + name + "' — the app default"
                                    + " already sends it"));
                } else if (io.tesseraql.yaml.config.ResponseHeaderDefaults.UNSET
                        .equals(declared)) {
                    findings.add(new LintFinding("TQL-SEC-4134", "warning", source,
                            "Route '" + route.definition().id() + "' suppresses the default"
                                    + " response header '" + name + "' — confirm the page must"
                                    + " not send it"));
                } else if (declared.contains("*") && !fallback.contains("*")) {
                    findings.add(new LintFinding("TQL-SEC-4134", "warning", source,
                            "Route '" + route.definition().id() + "' overrides the default"
                                    + " response header '" + name + "' with a wildcard the"
                                    + " default does not carry — confirm the broadening"));
                }
            }
        }
    }

    /**
     * Lints the path-matched route security defaults (docs/route-defaults.md): the retired
     * kind-keyed {@code defaults.api}/{@code defaults.htmx} shape never had a consumer and is
     * flagged toward {@code defaults.routes}, and a route left {@code public} under a rule that
     * declares a policy is either deliberate (declare the route's own security) or the exact
     * mistake the default exists to catch.
     */
    private void lintSecurityDefaults(Path appHome, AppManifest manifest, AppConfig config,
            List<LintFinding> findings) {
        Object legacy = config.navigate("tesseraql.security.defaults");
        if (legacy instanceof Map<?, ?> map && (map.containsKey("api")
                || map.containsKey("htmx"))) {
            findings.add(new LintFinding("TQL-SEC-4130", "warning", "config",
                    "tesseraql.security.defaults.api/htmx is replaced by the path-matched"
                            + " security.defaults.routes rules and has no effect"));
        }
        // A malformed rule list already failed the manifest load (TQL-SEC-4132) before lint ran.
        io.tesseraql.yaml.config.SecurityDefaults defaults = io.tesseraql.yaml.config.SecurityDefaults
                .from(config);
        if (defaults.isEmpty()) {
            return;
        }
        for (RouteFile route : manifest.routes()) {
            var security = route.definition().security();
            if (security == null || !"public".equals(security.auth())) {
                continue;
            }
            defaults.matchedRule(route.urlPath()).ifPresent(rule -> {
                if (rule.policy() != null) {
                    findings.add(new LintFinding("TQL-SEC-4131", "warning",
                            appHome.relativize(route.source()).toString(),
                            "Route '" + route.definition().id() + "' is public, but the security"
                                    + " default rule '" + rule.match() + "' declares policy '"
                                    + rule.policy() + "' for its path — confirm the route is"
                                    + " deliberately open"));
                }
            });
        }
    }

    /**
     * Lints the OIDC relying-party config (roadmap Phase 25): when enabled, it must declare a
     * https (or loopback-http) discovery URI, a client id, and a redirect URI — caught statically
     * so a misconfigured login fails at lint, not at the first redirect. Reads raw config nodes.
     */
    private void lintOidcConfig(AppConfig config, List<LintFinding> findings) {
        if (!"true".equalsIgnoreCase(rawString(config, "tesseraql.oidc.enabled"))) {
            return;
        }
        String discoveryUri = rawString(config, "tesseraql.oidc.discoveryUri");
        if (discoveryUri == null) {
            findings.add(new LintFinding("TQL-SEC-4050", "error", "config",
                    "OIDC is enabled but tesseraql.oidc.discoveryUri is not configured"));
        } else if (!discoveryUri.contains("${") && !isHttpsOrLoopback(discoveryUri)) {
            findings.add(new LintFinding("TQL-SEC-4051", "error", "config",
                    "OIDC tesseraql.oidc.discoveryUri must be https"
                            + " (loopback http is allowed for development)"));
        }
        if (rawString(config, "tesseraql.oidc.clientId") == null) {
            findings.add(new LintFinding("TQL-SEC-4052", "error", "config",
                    "OIDC is enabled but tesseraql.oidc.clientId is not configured"));
        }
        if (rawString(config, "tesseraql.oidc.redirectUri") == null) {
            findings.add(new LintFinding("TQL-SEC-4053", "error", "config",
                    "OIDC is enabled but tesseraql.oidc.redirectUri is not configured"));
        }
    }

    private static String rawString(AppConfig config, String path) {
        Object value = config.navigate(path);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isHttpsOrLoopback(String uri) {
        if (uri.startsWith("https://")) {
            return true;
        }
        if (uri.startsWith("http://")) {
            String host = uri.substring("http://".length());
            return host.startsWith("localhost") || host.startsWith("127.0.0.1")
                    || host.startsWith("[::1]") || host.startsWith("::1");
        }
        return false;
    }

    private void lintJwtConfig(AppConfig config, List<LintFinding> findings) {
        Object rawAlgorithm = config.navigate("tesseraql.security.jwt.algorithm");
        String algorithm = rawAlgorithm == null
                ? "HS256"
                : String.valueOf(rawAlgorithm).toUpperCase(java.util.Locale.ROOT);
        boolean secret = config.navigate("tesseraql.security.jwt.secret") != null;
        boolean publicKey = config.navigate("tesseraql.security.jwt.publicKey") != null;
        boolean jwksUri = config.navigate("tesseraql.security.jwt.jwksUri") != null;
        boolean keyMaterial = publicKey || jwksUri;
        if (!algorithm.equals("HS256") && !algorithm.equals("RS256")) {
            findings.add(new LintFinding("TQL-SEC-4043", "error", "config",
                    "Unsupported JWT algorithm '" + algorithm + "'; use HS256 or RS256"));
            return;
        }
        if (algorithm.equals("HS256") && keyMaterial) {
            findings.add(new LintFinding("TQL-SEC-4042", "error", "config",
                    "JWT algorithm HS256 declares RS256 key material (publicKey/jwksUri); an"
                            + " algorithm-confusion risk - pick one algorithm"));
        }
        if (algorithm.equals("RS256")) {
            if (secret) {
                findings.add(new LintFinding("TQL-SEC-4042", "error", "config",
                        "JWT algorithm RS256 declares an HS256 secret; an algorithm-confusion risk"
                                + " - pick one algorithm"));
            }
            if (!keyMaterial) {
                findings.add(new LintFinding("TQL-SEC-4040", "error", "config",
                        "RS256 JWT config must declare a key source (jwksUri or publicKey)"));
            } else if (publicKey && jwksUri) {
                findings.add(new LintFinding("TQL-SEC-4041", "error", "config",
                        "RS256 JWT config declares conflicting key sources; set exactly one of"
                                + " jwksUri/publicKey"));
            }
        }
    }

    private void lintApiKeyConfig(Path appHome, AppManifest manifest, AppConfig config,
            List<LintFinding> findings) {
        boolean apiKeysConfigured = config.navigate("tesseraql.security.apiKeys") != null;
        if (!apiKeysConfigured) {
            for (RouteFile route : manifest.routes()) {
                io.tesseraql.yaml.model.SecuritySpec security = route.definition().security();
                if (security != null && "apiKey".equals(security.auth())) {
                    String source = appHome.relativize(route.source()).toString().replace('\\',
                            '/');
                    findings.add(new LintFinding("TQL-SEC-4044", "error", source,
                            "Route '" + route.definition().id() + "' declares auth: apiKey but no"
                                    + " tesseraql.security.apiKeys is configured (deny by default)"));
                }
            }
            return;
        }
        if (!(config.navigate(
                "tesseraql.security.apiKeys.clients") instanceof java.util.Map<?, ?> clients)) {
            return;
        }
        clients.forEach((id, spec) -> {
            java.util.Map<?, ?> client = spec instanceof java.util.Map<?, ?> map
                    ? map
                    : java.util.Map.of();
            if (config.navigate(
                    "tesseraql.security.apiKeys.clients." + id + ".secretHash") == null) {
                findings.add(new LintFinding("TQL-SEC-4045", "error", "config",
                        "API-key client '" + id + "' must declare a secretHash; raw keys are never"
                                + " stored"));
            }
            if (client.get("roles") == null && client.get("permissions") == null) {
                findings.add(new LintFinding("TQL-SEC-4046", "warning", "config",
                        "API-key client '" + id + "' grants no roles or permissions; service"
                                + " callers should be least-privilege"));
            }
        });
    }

    /**
     * Lints the mutual-TLS config (roadmap Phase 25): an {@code auth: mtls} route requires mTLS
     * config; the config must name the forwarded-certificate header and each client must declare
     * exactly one certificate matcher (subjectDn/san/sha256). A missing trustBundle is a warning —
     * without it the runtime does not independently validate the chain and fully trusts the
     * TLS-terminating edge. Reads raw config nodes — never resolving secret placeholders.
     */
    private void lintMtlsConfig(Path appHome, AppManifest manifest, AppConfig config,
            List<LintFinding> findings) {
        if (config.navigate("tesseraql.security.mtls") == null) {
            for (RouteFile route : manifest.routes()) {
                io.tesseraql.yaml.model.SecuritySpec security = route.definition().security();
                if (security != null && "mtls".equals(security.auth())) {
                    String source = appHome.relativize(route.source()).toString().replace('\\',
                            '/');
                    findings.add(new LintFinding("TQL-SEC-4060", "error", source,
                            "Route '" + route.definition().id() + "' declares auth: mtls but no"
                                    + " tesseraql.security.mtls is configured (deny by default)"));
                }
            }
            return;
        }
        if (config.navigate("tesseraql.security.mtls.forwardedHeader") == null) {
            findings.add(new LintFinding("TQL-SEC-4061", "error", "config",
                    "tesseraql.security.mtls declares no forwardedHeader; a forwarded client"
                            + " certificate has no header to be read from"));
        }
        if (config.navigate("tesseraql.security.mtls.trustBundle") == null) {
            findings.add(new LintFinding("TQL-SEC-4065", "warning", "config",
                    "tesseraql.security.mtls declares no trustBundle; the runtime does not"
                            + " independently validate the certificate chain and fully trusts the"
                            + " TLS-terminating edge"));
        }
        if (!(config.navigate(
                "tesseraql.security.mtls.clients") instanceof java.util.Map<?, ?> clients)) {
            return;
        }
        clients.forEach((id, spec) -> {
            java.util.Map<?, ?> client = spec instanceof java.util.Map<?, ?> map
                    ? map
                    : java.util.Map.of();
            int matchers = 0;
            if (client.get("subjectDn") != null) {
                matchers++;
            }
            if (client.get("san") != null) {
                matchers++;
            }
            if (client.get("sha256") != null) {
                matchers++;
            }
            if (matchers == 0) {
                findings.add(new LintFinding("TQL-SEC-4062", "error", "config",
                        "mTLS client '" + id + "' declares no certificate matcher; set exactly one"
                                + " of subjectDn/san/sha256"));
            } else if (matchers > 1) {
                findings.add(new LintFinding("TQL-SEC-4063", "error", "config",
                        "mTLS client '" + id + "' declares more than one certificate matcher; set"
                                + " exactly one of subjectDn/san/sha256"));
            }
            if (client.get("roles") == null && client.get("permissions") == null) {
                findings.add(new LintFinding("TQL-SEC-4064", "warning", "config",
                        "mTLS client '" + id + "' grants no roles or permissions; service callers"
                                + " should be least-privilege"));
            }
        });
    }

    /**
     * Lints an application-declared MCP Apps UI resource (roadmap Phase 24): it renders HTML (the
     * {@code query-html} or {@code page} recipe), declares a {@code ui://} uri the client reads and
     * tools link to, takes no {@code input:} (a UI resource is addressed only by its uri), its SQL
     * file exists, and its referenced policy is defined. A missing description is a warning: it is
     * the hint the model uses to decide whether to surface the UI.
     */
    private void lintUiResource(Path appHome, AppConfig config,
            io.tesseraql.yaml.manifest.UiResourceFile ui, List<LintFinding> findings) {
        RouteDefinition definition = ui.definition();
        String source = appHome.relativize(ui.source()).toString().replace('\\', '/');

        if (!KNOWN_UI_RECIPES.contains(definition.recipe())) {
            findings.add(new LintFinding("TQL-MCP-1008", "error", source,
                    "MCP UI resource '" + definition.id() + "' has recipe '" + definition.recipe()
                            + "'; a UI resource renders HTML - use query-html or page"));
        }
        if (ui.uri() == null || !ui.uri().startsWith(UI_SCHEME)) {
            findings.add(new LintFinding("TQL-MCP-1009", "error", source,
                    "MCP UI resource '" + definition.id() + "' must declare a ui:// uri: it is the"
                            + " address the client reads and a tool links to"));
        }
        if (!definition.input().isEmpty()) {
            findings.add(new LintFinding("TQL-MCP-1011", "error", source,
                    "MCP UI resource '" + definition.id()
                            + "' must not declare input: a UI resource"
                            + " is addressed only by its uri and takes no arguments"));
        }
        if (ui.description() == null || ui.description().isBlank()) {
            findings.add(new LintFinding("TQL-MCP-1010", "warning", source,
                    "MCP UI resource '" + definition.id() + "' has no description; it is the hint"
                            + " the model uses to decide whether to surface the UI"));
        }
        if (definition.sql() != null && !definition.sql().isContract()
                && definition.sql().file() != null
                && !Files.isRegularFile(ui.source().getParent().resolve(definition.sql().file()))) {
            findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                    "Referenced SQL file is missing: " + definition.sql().file()));
        }
        String policy = definition.security() == null ? null : definition.security().policy();
        if (policy != null && !policy.isBlank() && !policyDefined(config, policy)) {
            findings.add(new LintFinding("TQL-SEC-4030", "warning", source,
                    "MCP UI resource references undefined policy '" + policy
                            + "' (deny by default)"));
        }
        lintDatasource(config, ui.source(), definition, source, findings);
    }

    /**
     * A tool's {@code ui:} link must resolve to a UI resource the app declares; a dangling link
     * would advertise a {@code _meta.ui.resourceUri} no {@code resources/read} can serve. Fail fast
     * at lint time rather than at render.
     */
    private void lintToolUiLinks(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        Set<String> declared = new java.util.HashSet<>();
        for (io.tesseraql.yaml.manifest.UiResourceFile ui : manifest.uiResources()) {
            if (ui.uri() != null) {
                declared.add(ui.uri());
            }
        }
        for (io.tesseraql.yaml.manifest.ToolFile tool : manifest.tools()) {
            String link = tool.uiResource();
            if (link != null && !link.isBlank() && !declared.contains(link)) {
                String source = appHome.relativize(tool.source()).toString().replace('\\', '/');
                findings.add(new LintFinding("TQL-MCP-1012", "error", source,
                        "MCP tool '" + tool.definition().id() + "' links ui: '" + link
                                + "' but no kind: ui resource declares that uri"));
            }
        }
    }

    /**
     * Lints an application-declared MCP resource (roadmap Phase 24): it is read-only (the
     * {@code query-json} recipe, query-mode SQL), declares a {@code uri} the client reads, takes no
     * {@code input:} (a resource is addressed only by its uri), its SQL file exists, and its
     * referenced policy is defined. A missing description is a warning: it is the hint the model
     * uses to decide whether to attach the resource as context.
     */
    private void lintResource(Path appHome, AppConfig config,
            io.tesseraql.yaml.manifest.ResourceFile resource, List<LintFinding> findings) {
        RouteDefinition definition = resource.definition();
        String source = appHome.relativize(resource.source()).toString().replace('\\', '/');

        boolean write = !"query-json".equals(definition.recipe())
                || (definition.sql() != null && "update".equals(definition.sql().effectiveMode()));
        if (write) {
            findings.add(new LintFinding("TQL-MCP-1003", "error", source,
                    "MCP resource '" + definition.id() + "' must be read-only: use the query-json"
                            + " recipe with query-mode SQL"));
        }
        if (resource.uri() == null || resource.uri().isBlank()) {
            findings.add(new LintFinding("TQL-MCP-1004", "error", source,
                    "MCP resource '" + definition.id() + "' must declare a uri: it is the address"
                            + " the client reads the resource by"));
        }
        if (!definition.input().isEmpty()) {
            findings.add(new LintFinding("TQL-MCP-1006", "error", source,
                    "MCP resource '" + definition.id() + "' must not declare input: a resource is"
                            + " addressed only by its uri and takes no arguments"));
        }
        if (resource.description() == null || resource.description().isBlank()) {
            findings.add(new LintFinding("TQL-MCP-1005", "warning", source,
                    "MCP resource '" + definition.id() + "' has no description; it is the hint the"
                            + " model uses to decide whether to attach the resource"));
        }
        if (definition.sql() != null && !definition.sql().isContract()
                && definition.sql().file() != null
                && !Files.isRegularFile(
                        resource.source().getParent().resolve(definition.sql().file()))) {
            findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                    "Referenced SQL file is missing: " + definition.sql().file()));
        }
        String policy = definition.security() == null ? null : definition.security().policy();
        if (policy != null && !policy.isBlank() && !policyDefined(config, policy)) {
            findings.add(new LintFinding("TQL-SEC-4030", "warning", source,
                    "MCP resource references undefined policy '" + policy + "' (deny by default)"));
        }
        lintDatasource(config, resource.source(), definition, source, findings);
    }

    /**
     * Two resources sharing a {@code uri} would collide at startup (the MCP server keys every
     * resource by its uri and rejects a duplicate), so flag it at lint time instead - deny by
     * default, fail fast. UI resources ({@code ui://}) share that single namespace with plain
     * resources, so they are checked together.
     */
    private void lintDuplicateResourceUris(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        for (io.tesseraql.yaml.manifest.ResourceFile resource : manifest.resources()) {
            checkDuplicateUri(appHome, resource.uri(), resource.source(), seen, findings);
        }
        for (io.tesseraql.yaml.manifest.UiResourceFile ui : manifest.uiResources()) {
            checkDuplicateUri(appHome, ui.uri(), ui.source(), seen, findings);
        }
    }

    private void checkDuplicateUri(Path appHome, String uri, Path file,
            java.util.Map<String, String> seen, List<LintFinding> findings) {
        if (uri == null || uri.isBlank()) {
            return;
        }
        String source = appHome.relativize(file).toString().replace('\\', '/');
        String previous = seen.putIfAbsent(uri, source);
        if (previous != null) {
            findings.add(new LintFinding("TQL-MCP-1007", "error", source,
                    "MCP resource uri '" + uri + "' is already declared by " + previous));
        }
    }

    /**
     * Lints an application-declared MCP tool (roadmap Phase 24 follow-on): its recipe is a tool
     * recipe, its SQL files exist, its referenced policy is defined, and - deny by default - a write
     * tool declares an authorization policy, since an AI agent must not mutate data unauthorized. A
     * missing description is a warning: it is the hint the model uses to decide when to call.
     */
    private void lintTool(Path appHome, AppConfig config, io.tesseraql.yaml.manifest.ToolFile tool,
            List<LintFinding> findings) {
        RouteDefinition definition = tool.definition();
        String source = appHome.relativize(tool.source()).toString().replace('\\', '/');

        if (!KNOWN_TOOL_RECIPES.contains(definition.recipe())) {
            findings.add(new LintFinding("TQL-MCP-1001", "error", source,
                    "MCP tool '" + definition.id() + "' has recipe '" + definition.recipe()
                            + "'; only query-json and command-json are supported"));
        }
        if (tool.description() == null || tool.description().isBlank()) {
            findings.add(new LintFinding("TQL-MCP-1002", "warning", source,
                    "MCP tool '" + definition.id() + "' has no description; it is the hint the"
                            + " model uses to decide when to call the tool"));
        }
        if (definition.sql() != null && !definition.sql().isContract()
                && definition.sql().file() != null
                && !Files.isRegularFile(
                        tool.source().getParent().resolve(definition.sql().file()))) {
            findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                    "Referenced SQL file is missing: " + definition.sql().file()));
        }
        definition.steps().forEach((name, step) -> {
            if (step.file() != null
                    && !Files.isRegularFile(tool.source().getParent().resolve(step.file()))) {
                findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                        "Step '" + name + "' references a missing SQL file: " + step.file()));
            }
        });

        boolean write = "command-json".equals(definition.recipe())
                || (definition.sql() != null && "update".equals(definition.sql().effectiveMode()));
        String policy = definition.security() == null ? null : definition.security().policy();
        if (write && (policy == null || policy.isBlank())) {
            findings.add(new LintFinding("TQL-MCP-4030", "error", source,
                    "Write MCP tool '" + definition.id() + "' must declare a security.policy: an AI"
                            + " agent must not mutate data without authorization (deny by default)"));
        }
        if (policy != null && !policy.isBlank() && !policyDefined(config, policy)) {
            findings.add(new LintFinding("TQL-SEC-4030", "warning", source,
                    "MCP tool references undefined policy '" + policy + "' (deny by default)"));
        }
        lintDatasource(config, tool.source(), definition, source, findings);
    }

    /**
     * Statically checks the app's message catalogs (roadmap Phase 22) when a {@code messages/}
     * directory exists: catalog files parse and carry valid BCP-47 names (TQL-YAML-1007), every
     * locale declared in {@code tesseraql.i18n.locales} has catalog entries to read
     * (TQL-YAML-1103), translation gaps against the default locale surface per catalog
     * (TQL-YAML-1008), and every validation-rule / constraint-mapping message key resolves in
     * the default locale (TQL-FIELD-2005; {@code tql.*} keys resolve through the framework's
     * built-in catalog and are skipped).
     */
    private void lintI18n(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        if (!Files.isDirectory(appHome.resolve("messages"))) {
            return;
        }
        io.tesseraql.yaml.i18n.MessageCatalog catalog;
        try {
            catalog = io.tesseraql.yaml.i18n.MessageCatalog.load(appHome.resolve("messages"));
        } catch (io.tesseraql.core.error.TqlException ex) {
            findings.add(new LintFinding("TQL-YAML-1007", "error", "messages", ex.getMessage()));
            return;
        }
        AppConfig config = manifest.config();
        String defaultTag = java.util.Locale.forLanguageTag(
                config.getString("tesseraql.i18n.defaultLocale").orElse("en")).toLanguageTag();

        Object declared = config.navigate("tesseraql.i18n.locales");
        if (declared instanceof List<?> tags) {
            for (Object tag : tags) {
                String normalized = java.util.Locale
                        .forLanguageTag(String.valueOf(tag)).toLanguageTag();
                if (!normalized.equals(defaultTag)
                        && catalog.forLocale(normalized).isEmpty()) {
                    findings.add(new LintFinding("TQL-YAML-1103", "warning", "messages",
                            "Declared locale '" + tag + "' has no messages/" + normalized
                                    + ".yml catalog"));
                }
            }
        }

        java.util.Map<String, String> defaults = catalog.forLocale(defaultTag);
        for (String tag : catalog.tags()) {
            if (tag.equals(defaultTag)) {
                continue;
            }
            List<String> missing = defaults.keySet().stream()
                    .filter(key -> catalog.resolve(tag, key) == null)
                    .sorted()
                    .toList();
            if (!missing.isEmpty()) {
                findings.add(new LintFinding("TQL-YAML-1008", "warning", "messages",
                        "Catalog '" + tag + "' is missing " + missing.size()
                                + " key(s) present in the default locale '" + defaultTag
                                + "' (first: " + missing.get(0) + ")"));
            }
        }

        for (RouteFile route : manifest.routes()) {
            String source = appHome.relativize(route.source()).toString().replace('\\', '/');
            route.definition().validate().forEach((id, rule) -> lintMessageKey(catalog,
                    defaultTag, rule.message(), "Validation rule '" + id + "'", source,
                    findings));
            if (route.definition().errors() != null) {
                route.definition().errors().constraints()
                        .forEach((constraint, mapping) -> lintMessageKey(catalog, defaultTag,
                                mapping.message(), "Constraint mapping '" + constraint + "'",
                                source, findings));
            }
        }
    }

    /** Warns when a declared message key has no default-locale text to render. */
    private void lintMessageKey(io.tesseraql.yaml.i18n.MessageCatalog catalog, String defaultTag,
            String key, String owner, String source, List<LintFinding> findings) {
        if (key == null || key.isBlank() || key.startsWith("tql.")) {
            return;
        }
        if (catalog.resolve(defaultTag, key) == null) {
            findings.add(new LintFinding("TQL-FIELD-2005", "warning", source,
                    owner + " declares message key '" + key + "' that no messages/" + defaultTag
                            + ".yml entry resolves"));
        }
    }

    private void lintRoute(Path appHome, AppConfig config, RouteFile route,
            List<LintFinding> findings) {
        RouteDefinition definition = route.definition();
        String source = appHome.relativize(route.source()).toString().replace('\\', '/');

        if (!KNOWN_ROUTE_RECIPES.contains(definition.recipe())) {
            findings.add(new LintFinding("TQL-YAML-1002", "error", source,
                    "Unknown route recipe '" + definition.recipe() + "'",
                    lineOf(route.source(), "recipe:"), null));
        }
        if (definition.sql() != null && definition.sql().timeoutSeconds() != null
                && definition.sql().timeoutSeconds() < 0) {
            findings.add(new LintFinding("TQL-YAML-1021", "error", source,
                    "sql.timeoutSeconds must be >= 0 (0 disables the statement timeout)",
                    lineOf(route.source(), "timeoutSeconds:"), null));
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
        lintEmit(route, definition, source, findings);
        lintHttpSources(config, definition, source, findings);
        lintRateLimitScope(definition, source, findings);
        lintHttpCache(definition, source, findings);
        lintNotify(config, definition, source, findings);
        lintWebhook(config, definition, source, findings);
        lintPublish(config, definition, source, findings);
        if (definition.consume() != null) {
            findings.add(new LintFinding("TQL-YAML-1010", "error", source, "consume: is only"
                    + " supported on a queue-consume route under consume/, not the '"
                    + definition.recipe() + "' recipe"));
        }
        lintPdfExport(route, definition, source, findings);
        lintDatasource(config, route.source(), definition, source, findings);
        lintEmbeddedVariables(route, definition, source, findings);
        if (definition.security() != null && definition.security().policy() != null
                && !policyDefined(config, definition.security().policy())) {
            findings.add(new LintFinding("TQL-SEC-4030", "warning", source,
                    "Route references undefined policy '" + definition.security().policy()
                            + "' (deny by default)"));
        }
        lintTenantPredicate(config, route, definition, source, findings);
    }

    /** A {@code {placeholder}} reference inside an embedded-variable template. */
    private static final Pattern EMBEDDED_PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    /**
     * An embedded variable ({@code /*# … {x} … *}{@code /}) interpolates its placeholder values into
     * the SQL text, not a {@code ?} bind, so a request-controlled value there is an injection vector
     * unless allowlisted. This requires every placeholder that resolves to a request input to be
     * {@code enum}-constrained (the runtime guard against meta-characters is only defense in depth).
     */
    private void lintEmbeddedVariables(RouteFile route, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        SqlBinding sql = definition.sql();
        if (sql == null || sql.isContract() || sql.file() == null) {
            return;
        }
        Path sqlFile = route.source().getParent().resolve(sql.file());
        if (!Files.isRegularFile(sqlFile)) {
            return; // missing-file is reported separately
        }
        Set<String> placeholders = new LinkedHashSet<>();
        try {
            collectEmbeddedPlaceholders(Sql2WayParser.parse(Files.readString(sqlFile)),
                    placeholders);
        } catch (Exception ignored) {
            return; // SQL syntax / IO errors surface through other checks
        }
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
                findings.add(new LintFinding("TQL-SQL-2109", "error", source,
                        "Embedded variable '{" + placeholder + "}' interpolates request input '"
                                + input + "' into SQL; constrain it with an 'enum' allowlist to "
                                + "prevent injection"));
            }
        }
    }

    /** The input name a {@code sql.params} source binds from a request, or {@code null} otherwise. */
    private static String requestInput(String paramSource) {
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

    private static void collectEmbeddedPlaceholders(List<SqlNode> nodes, Set<String> out) {
        for (SqlNode node : nodes) {
            switch (node) {
                case SqlNode.Embedded embedded -> {
                    Matcher matcher = EMBEDDED_PLACEHOLDER.matcher(embedded.template());
                    while (matcher.find()) {
                        out.add(matcher.group(1).trim());
                    }
                }
                case SqlNode.If conditional -> conditional.branches()
                        .forEach(branch -> collectEmbeddedPlaceholders(branch.body(), out));
                case SqlNode.For loop -> collectEmbeddedPlaceholders(loop.body(), out);
                default -> {
                    // Text/Bind/ListBind/Scope hold no embedded placeholders.
                }
            }
        }
    }

    /**
     * Policy ids are dotted names ({@code users.read}): literal keys of the policies map, not
     * nested config paths, so a {@code navigate} walk on the full path never finds them.
     */
    private static boolean policyDefined(AppConfig config, String policy) {
        return config.navigate("tesseraql.security.policies") instanceof java.util.Map<?, ?> map
                && map.containsKey(policy);
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

    private static final Pattern SCOPE_DIRECTIVE = Pattern
            .compile("/\\*%\\s*scope\\s+([^*]+?)\\s*\\*/");
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Lints organizational data scoping (roadmap Phase 29): every {@code scope/} definition is
     * well-formed, and every {@code /*%scope%/} directive in a query names a declared scope with a
     * valid {@code on <alias>}.
     */
    private void lintScopes(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        Set<String> declared = new HashSet<>();
        for (ScopeFile scope : manifest.scopes()) {
            lintScopeDefinition(appHome, scope, findings);
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
        lintWriteScope(appHome, manifest, findings);
    }

    private static final Pattern SCOPED_TABLE_ALIASED = Pattern.compile(
            "(?is)\\b(?:from|join|into|update)\\s+([A-Za-z_][\\w.]*)\\s+(?:as\\s+)?ALIAS\\b");
    private static final Pattern WRITE_TARGET = Pattern.compile(
            "(?is)^\\s*(?:update|delete\\s+from)\\s+([A-Za-z_][\\w.]*)");

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
                if (Files.isRegularFile(sqlFile)) {
                    collectScopedTables(readQuietly(sqlFile), scopedTables);
                }
            }
        }
        if (scopedTables.isEmpty()) {
            return;
        }
        for (RouteFile route : allScopeRoutes(manifest)) {
            String source = relative(appHome, route.source());
            String id = route.definition().id();
            for (Map.Entry<String, SqlBinding> entry : writeBindings(route.definition())) {
                Path sqlFile = route.source().getParent().resolve(entry.getValue().file());
                if (!Files.isRegularFile(sqlFile)) {
                    continue;
                }
                String sql = readQuietly(sqlFile);
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
                    Matcher from = Pattern.compile("(?is)\\bfrom\\s+([A-Za-z_][\\w.]*)")
                            .matcher(sql);
                    if (from.find()) {
                        out.add(lastSegment(from.group(1)));
                    }
                }
            }
        }
    }

    /** The {@code (name, binding)} pairs of a route whose SQL runs in write ({@code update}) mode. */
    private static List<Map.Entry<String, SqlBinding>> writeBindings(RouteDefinition definition) {
        Map<String, SqlBinding> bindings = new LinkedHashMap<>();
        if (definition.sql() != null) {
            bindings.put("sql", definition.sql());
        }
        bindings.putAll(definition.steps());
        List<Map.Entry<String, SqlBinding>> writes = new ArrayList<>();
        for (Map.Entry<String, SqlBinding> entry : bindings.entrySet()) {
            SqlBinding binding = entry.getValue();
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

    private void lintAttachments(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        for (io.tesseraql.yaml.manifest.AttachmentFile attachment : manifest.attachments()) {
            lintAttachmentDefinition(appHome, attachment, findings);
        }
    }

    /**
     * Object-storage egress (roadmap Phase 30 slice 2): when {@code provider: s3}, every attachment's
     * resolved bucket must be in {@code tesseraql.object-storage.allowedBuckets} (deny-by-default,
     * mirroring the HTTP/poll egress allow-lists). The {@code file} provider needs no allow-list.
     */
    private void lintObjectStorageEgress(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        io.tesseraql.yaml.config.AppConfig config = manifest.config();
        String provider = config.getString("tesseraql.object-storage.provider").orElse("file");
        if (!"s3".equalsIgnoreCase(provider)) {
            return;
        }
        Set<String> allowed = new HashSet<>();
        if (config
                .navigate("tesseraql.object-storage.allowedBuckets") instanceof List<?> declared) {
            declared.forEach(value -> allowed.add(String.valueOf(value)));
        }
        for (io.tesseraql.yaml.manifest.AttachmentFile attachment : manifest.attachments()) {
            io.tesseraql.yaml.model.AttachmentDefinition def = attachment.definition();
            String source = relative(appHome, attachment.source());
            String logical = def.bucket();
            if (logical == null || logical.isBlank()) {
                findings.add(new LintFinding("TQL-SEC-4110", "error", source, "attachment '"
                        + def.id()
                        + "' must declare a bucket when tesseraql.object-storage.provider"
                        + " is s3"));
                continue;
            }
            String real = config.getString(
                    "tesseraql.object-storage.buckets." + logical + ".bucket").orElse(logical);
            if (!allowed.contains(real)) {
                findings.add(new LintFinding("TQL-SEC-4110", "error", source, "attachment '"
                        + def.id() + "' targets bucket '" + real + "' which is not in "
                        + "tesseraql.object-storage.allowedBuckets (deny by default)"));
            }
        }
    }

    /** Checks an attachment definition: kind, base path, owning record, and upload limits. */
    private void lintAttachmentDefinition(Path appHome,
            io.tesseraql.yaml.manifest.AttachmentFile attachment, List<LintFinding> findings) {
        String source = relative(appHome, attachment.source());
        io.tesseraql.yaml.model.AttachmentDefinition def = attachment.definition();
        String id = def.id();
        if (!"attachment".equals(def.kind())) {
            findings.add(new LintFinding("TQL-ATTACH-3401", "error", source,
                    "attachment '" + id + "' must declare kind: attachment"));
        }
        boolean hasBasePath = def.basePath() != null && !def.basePath().isBlank();
        if (!hasBasePath) {
            findings.add(new LintFinding("TQL-ATTACH-3402", "error", source,
                    "attachment '" + id + "' must declare a basePath"));
        }
        io.tesseraql.yaml.model.AttachmentDefinition.RecordSpec record = def.record();
        boolean hasEntity = record != null && record.entity() != null
                && !record.entity().isBlank();
        boolean hasKey = record != null && record.key() != null && !record.key().isBlank();
        if (!hasEntity || !hasKey) {
            findings.add(new LintFinding("TQL-ATTACH-3403", "error", source,
                    "attachment '" + id + "' must declare record.entity and record.key"));
        } else if (hasBasePath && !def.basePath().contains("{" + record.key() + "}")) {
            findings.add(new LintFinding("TQL-ATTACH-3404", "error", source,
                    "attachment '" + id + "' basePath must contain the record key '{"
                            + record.key() + "}' as a path parameter"));
        }
        io.tesseraql.yaml.model.AttachmentDefinition.Limits limits = def.limits();
        if (limits == null || limits.maxBytesValue() <= 0) {
            findings.add(new LintFinding("TQL-ATTACH-3405", "error", source,
                    "attachment '" + id + "' must declare a positive limits.maxBytes (e.g. 25MB)"));
        }
    }

    /** Checks a scope definition: each arm declares exactly one effect, a valid when, a real file. */
    private void lintScopeDefinition(Path appHome, ScopeFile scope, List<LintFinding> findings) {
        String source = relative(appHome, scope.source());
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
        if (set > 1) {
            findings.add(new LintFinding("TQL-SCOPE-3012", "error", source,
                    where + " when must set only one of role/permission/claim"));
        }
        if (when.claim() != null && when.value() == null) {
            findings.add(new LintFinding("TQL-SCOPE-3012", "error", source,
                    where + " when claim needs an 'equals' value"));
        }
    }

    /** Checks each {@code /*%scope%/} directive in a route's SQL names a declared scope. */
    private void lintScopeDirectives(Path appHome, RouteFile route, Set<String> declared,
            List<LintFinding> findings) {
        String source = relative(appHome, route.source());
        String id = route.definition().id();
        for (Path sqlFile : routeSqlFiles(route)) {
            if (!Files.isRegularFile(sqlFile)) {
                continue;
            }
            Matcher matcher = SCOPE_DIRECTIVE.matcher(readQuietly(sqlFile));
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

    /** The roots a transition guard may reference (roadmap Phase 28); {@code task} arrives in slice 2. */
    private static final Set<String> GUARD_ROOTS = Set.of("document", "task", "principal");

    /**
     * Lints approval workflows (roadmap Phase 28): each workflow's states and transitions are
     * well-formed (no undeclared/unreachable states, no dead ends), guards are valid whitelist
     * expressions over the allowed roots, referenced files exist, and the declared mode matches the
     * document fields it needs.
     */
    private void lintWorkflows(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        for (WorkflowFile workflow : manifest.workflows()) {
            lintWorkflow(appHome, manifest.config(), workflow, findings);
        }
    }

    private void lintWorkflow(Path appHome, AppConfig config, WorkflowFile workflow,
            List<LintFinding> findings) {
        String source = relative(appHome, workflow.source());
        WorkflowDefinition def = workflow.definition();
        String id = def.id();
        Path dir = workflow.source().getParent();

        Set<String> states = new LinkedHashSet<>();
        int initialMarked = 0;
        for (StateSpec state : def.states()) {
            if (state.id() != null) {
                states.add(state.id());
            }
            if (state.isInitial()) {
                initialMarked++;
            }
        }
        if (def.initial() != null && !states.contains(def.initial())) {
            findings.add(new LintFinding("TQL-WORKFLOW-3101", "error", source, "workflow '" + id
                    + "' initial state '" + def.initial() + "' is not declared in states"));
        }
        if (initialMarked > 1) {
            findings.add(new LintFinding("TQL-WORKFLOW-3102", "error", source,
                    "workflow '" + id + "' declares more than one initial state"));
        }

        Set<String> transitionIds = new LinkedHashSet<>();
        Map<String, String> transitionFrom = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        for (TransitionSpec t : def.transitions()) {
            if (t.id() != null) {
                transitionIds.add(t.id());
                transitionFrom.put(t.id(), t.from());
            }
            String where = "workflow '" + id + "' transition '" + t.id() + "'";
            if (t.from() == null || !states.contains(t.from())) {
                findings.add(new LintFinding("TQL-WORKFLOW-3101", "error", source,
                        where + " from-state '" + t.from() + "' is not declared in states"));
            } else {
                outgoing.computeIfAbsent(t.from(), k -> new ArrayList<>()).add(t.to());
            }
            if (t.to() == null || !states.contains(t.to())) {
                findings.add(new LintFinding("TQL-WORKFLOW-3101", "error", source,
                        where + " to-state '" + t.to() + "' is not declared in states"));
            }
            lintGuard(t.guard(), where, source, findings);
            if (t.command() != null && !Files.isRegularFile(dir.resolve(t.command()))) {
                findings.add(new LintFinding("TQL-WORKFLOW-3104", "error", source,
                        where + " references missing command '" + t.command() + "'"));
            }
            if (t.assign() != null && t.assign().file() != null
                    && !Files.isRegularFile(dir.resolve(t.assign().file()))) {
                findings.add(new LintFinding("TQL-WORKFLOW-3104", "error", source,
                        where + " references missing assignee file '" + t.assign().file() + "'"));
            }
        }

        if (def.initial() != null && states.contains(def.initial())) {
            Set<String> reachable = reachableStates(def.initial(), outgoing);
            for (StateSpec state : def.states()) {
                if (state.id() != null && !reachable.contains(state.id())) {
                    findings.add(new LintFinding("TQL-WORKFLOW-3102", "error", source,
                            "workflow '" + id + "' state '" + state.id()
                                    + "' is unreachable from the initial state"));
                }
            }
        }
        for (StateSpec state : def.states()) {
            boolean hasOutgoing = outgoing.containsKey(state.id());
            if (state.isTerminal() && hasOutgoing) {
                findings.add(new LintFinding("TQL-WORKFLOW-3105", "warning", source,
                        "workflow '" + id + "' terminal state '" + state.id()
                                + "' has an outgoing transition"));
            }
            if (!state.isTerminal() && !hasOutgoing) {
                findings.add(new LintFinding("TQL-WORKFLOW-3105", "warning", source,
                        "workflow '" + id + "' non-terminal state '" + state.id()
                                + "' has no outgoing transition (dead end)"));
            }
        }

        for (DeadlineSpec deadline : def.deadlines()) {
            String where = "workflow '" + id + "' deadline on '" + deadline.state() + "'";
            if (deadline.state() != null && !states.contains(deadline.state())) {
                findings.add(new LintFinding("TQL-WORKFLOW-3101", "error", source,
                        where + " names a state not declared in states"));
            }
            DeadlineSpec.OnBreachSpec onBreach = deadline.onBreach();
            if (onBreach != null) {
                if (onBreach.escalate() != null && !onBreach.escalate().isBlank()) {
                    if (!transitionIds.contains(onBreach.escalate())) {
                        findings.add(new LintFinding("TQL-WORKFLOW-3104", "error", source,
                                where + " escalate '" + onBreach.escalate()
                                        + "' is not a declared transition"));
                    } else if (!java.util.Objects.equals(transitionFrom.get(onBreach.escalate()),
                            deadline.state())) {
                        // The sweeper auto-fires it from the deadline's state, so it could never
                        // advance from a different from-state.
                        findings.add(new LintFinding("TQL-WORKFLOW-3107", "error", source,
                                where + " escalate '" + onBreach.escalate()
                                        + "' starts from '"
                                        + transitionFrom.get(onBreach.escalate())
                                        + "', not the deadline's state"));
                    }
                }
                if (onBreach.reassign() != null
                        && !Files.isRegularFile(dir.resolve(onBreach.reassign()))) {
                    findings.add(new LintFinding("TQL-WORKFLOW-3104", "error", source, where
                            + " references missing reassign file '" + onBreach.reassign() + "'"));
                }
            }
        }

        lintWorkflowMode(def, config, source, findings);
    }

    /** Parses a guard and checks every path it reads is rooted at an allowed variable. */
    private void lintGuard(String guard, String where, String source, List<LintFinding> findings) {
        if (guard == null || guard.isBlank()) {
            return;
        }
        Expr expr;
        try {
            expr = ExpressionParser.parse(guard);
        } catch (RuntimeException ex) {
            findings.add(new LintFinding("TQL-WORKFLOW-3103", "error", source,
                    where + " guard is not a valid expression: " + ex.getMessage()));
            return;
        }
        List<List<String>> paths = new ArrayList<>();
        collectGuardPaths(expr, paths);
        for (List<String> path : paths) {
            if (!path.isEmpty() && !GUARD_ROOTS.contains(path.get(0))) {
                findings.add(new LintFinding("TQL-WORKFLOW-3103", "error", source,
                        where + " guard references '" + String.join(".", path)
                                + "'; allowed roots are document, task, principal"));
            }
        }
    }

    private static void collectGuardPaths(Expr expr, List<List<String>> out) {
        if (expr instanceof Expr.Path p) {
            out.add(p.segments());
        } else if (expr instanceof Expr.Not n) {
            collectGuardPaths(n.operand(), out);
        } else if (expr instanceof Expr.Logical l) {
            collectGuardPaths(l.left(), out);
            collectGuardPaths(l.right(), out);
        } else if (expr instanceof Expr.Comparison c) {
            collectGuardPaths(c.left(), out);
            collectGuardPaths(c.right(), out);
        }
    }

    private static Set<String> reachableStates(String start, Map<String, List<String>> outgoing) {
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        reachable.add(start);
        while (!queue.isEmpty()) {
            String state = queue.poll();
            for (String next : outgoing.getOrDefault(state, List.of())) {
                if (next != null && reachable.add(next)) {
                    queue.add(next);
                }
            }
        }
        return reachable;
    }

    /** Checks the document fields the declared mode requires are present (roadmap Phase 28). */
    private void lintWorkflowMode(WorkflowDefinition def, AppConfig config, String source,
            List<LintFinding> findings) {
        String mode = def.mode();
        if (mode == null || mode.isBlank()) {
            mode = config.getString("tesseraql.workflow.mode").orElse("app");
        }
        boolean managed = "managed".equalsIgnoreCase(mode);
        WorkflowDefinition.DocumentSpec doc = def.document();
        if (doc == null) {
            findings.add(new LintFinding("TQL-WORKFLOW-3106", "error", source,
                    "workflow '" + def.id() + "' declares no document"));
            return;
        }
        List<String> missing = new ArrayList<>();
        if (isBlank(doc.table())) {
            missing.add("document.table");
        }
        if (isBlank(doc.key())) {
            missing.add("document.key");
        }
        if (managed) {
            if (isBlank(doc.type())) {
                missing.add("document.type");
            }
        } else if (isBlank(doc.stateColumn())) {
            missing.add("document.stateColumn");
        }
        if (!missing.isEmpty()) {
            findings.add(new LintFinding("TQL-WORKFLOW-3106", "error", source,
                    "workflow '" + def.id() + "' in " + (managed ? "managed" : "app")
                            + " mode requires " + String.join(", ", missing)));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** The non-contract SQL files a route references (its {@code sql}, {@code steps}, {@code queries}). */
    private static List<Path> routeSqlFiles(RouteFile route) {
        RouteDefinition definition = route.definition();
        Path dir = route.source().getParent();
        Map<String, SqlBinding> bindings = new LinkedHashMap<>();
        if (definition.sql() != null) {
            bindings.put("sql", definition.sql());
        }
        bindings.putAll(definition.steps());
        bindings.putAll(definition.queries());
        List<Path> files = new ArrayList<>();
        for (SqlBinding binding : bindings.values()) {
            if (binding != null && !binding.isContract() && binding.file() != null) {
                files.add(dir.resolve(binding.file()));
            }
        }
        return files;
    }

    private static String relative(Path appHome, Path source) {
        return appHome.relativize(source).toString().replace('\\', '/');
    }

    /** Drops the {@code as boolean} suffix so the scope name/alias parse the same as a predicate. */
    static String stripAsBoolean(String content) {
        return content.endsWith(" as boolean")
                ? content.substring(0, content.length() - " as boolean".length()).trim()
                : content;
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
     * Statically checks the inbound {@code webhook} recipe (roadmap Phase 26): the route names a
     * verifier ({@code TQL-SEC-4082}) that is configured under
     * {@code tesseraql.connectors.webhooks} ({@code TQL-SEC-4083}, so a webhook is never served
     * unverified), and runs a SQL pipeline ({@code TQL-YAML-1008}). A {@code webhook:} block on a
     * non-webhook recipe is a misuse.
     */
    private void lintWebhook(AppConfig config, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        if (!"webhook".equals(definition.recipe())) {
            if (definition.webhook() != null) {
                findings.add(new LintFinding("TQL-YAML-1008", "error", source,
                        "webhook: is only supported on the webhook recipe, not '"
                                + definition.recipe() + "'"));
            }
            return;
        }
        String provider = definition.webhook() == null ? null : definition.webhook().provider();
        if (provider == null || provider.isBlank()) {
            findings.add(new LintFinding("TQL-SEC-4082", "error", source,
                    "webhook route '" + definition.id() + "' needs a webhook.provider"));
        } else if (config.navigate("tesseraql.connectors.webhooks." + provider) == null) {
            findings.add(new LintFinding("TQL-SEC-4083", "error", source, "webhook route '"
                    + definition.id() + "' references verifier '" + provider
                    + "' not configured under tesseraql.connectors.webhooks"));
        }
        if (definition.sql() == null && definition.steps().isEmpty()) {
            findings.add(new LintFinding("TQL-YAML-1008", "error", source, "webhook route '"
                    + definition.id() + "' needs a sql: or steps: pipeline"));
        }
    }

    /**
     * Statically checks a {@code publish:} block (roadmap Phase 27): it rides a transactional
     * command ({@code TQL-YAML-1010}) and names a channel configured under
     * {@code tesseraql.messaging.channels} ({@code TQL-SEC-4091}), so a publish never targets a
     * channel that does not exist.
     */
    private void lintPublish(AppConfig config, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        var publish = definition.publish();
        if (publish == null) {
            return;
        }
        if (!PUBLISH_RECIPES.contains(definition.recipe())) {
            findings.add(new LintFinding("TQL-YAML-1010", "error", source, "publish: is only"
                    + " supported on command routes (command-json, webhook, queue-consume), not '"
                    + definition.recipe() + "'"));
            return;
        }
        if (publish.channel() == null || publish.channel().isBlank()) {
            findings.add(new LintFinding("TQL-SEC-4091", "error", source,
                    "publish: of '" + definition.id() + "' needs a channel"));
        } else if (config.navigate("tesseraql.messaging.channels." + publish.channel()) == null) {
            findings.add(new LintFinding("TQL-SEC-4091", "error", source, "publish: of '"
                    + definition.id() + "' references channel '" + publish.channel()
                    + "' not configured under tesseraql.messaging.channels"));
        }
    }

    /**
     * Statically checks a {@code queue-consume} route under {@code consume/} (roadmap Phase 27): it
     * uses the {@code queue-consume} recipe, names a channel/topic ({@code TQL-YAML-1009}) whose
     * channel is configured ({@code TQL-SEC-4090}, so a consumer is never wired to a channel that
     * does not exist), and runs a SQL pipeline. Its {@code publish:} and {@code notify:} blocks are
     * linted the same way a command route's are.
     */
    private void lintConsumer(Path appHome, AppConfig config, RouteFile consumer,
            List<LintFinding> findings) {
        RouteDefinition definition = consumer.definition();
        String source = appHome.relativize(consumer.source()).toString().replace('\\', '/');
        if (!"queue-consume".equals(definition.recipe())) {
            findings.add(new LintFinding("TQL-YAML-1010", "error", source, "a consume/ route must"
                    + " use the queue-consume recipe, not '" + definition.recipe() + "'"));
            return;
        }
        var consume = definition.consume();
        if (consume == null || consume.channel() == null || consume.channel().isBlank()
                || consume.topic() == null || consume.topic().isBlank()) {
            findings.add(new LintFinding("TQL-YAML-1009", "error", source, "queue-consume route '"
                    + definition.id() + "' needs consume.channel and consume.topic"));
        } else if (config.navigate("tesseraql.messaging.channels." + consume.channel()) == null) {
            findings.add(new LintFinding("TQL-SEC-4090", "error", source, "queue-consume route '"
                    + definition.id() + "' references channel '" + consume.channel()
                    + "' not configured under tesseraql.messaging.channels"));
        }
        if (definition.sql() == null && definition.steps().isEmpty()) {
            findings.add(new LintFinding("TQL-YAML-1009", "error", source, "queue-consume route '"
                    + definition.id() + "' needs a sql: or steps: pipeline"));
        }
        if (definition.sql() != null && definition.sql().file() != null && !Files.isRegularFile(
                consumer.source().getParent().resolve(definition.sql().file()))) {
            findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                    "Referenced SQL file is missing: " + definition.sql().file()));
        }
        definition.steps().forEach((name, step) -> {
            if (step.file() != null && !Files.isRegularFile(
                    consumer.source().getParent().resolve(step.file()))) {
                findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                        "Step '" + name + "' references a missing SQL file: " + step.file()));
            }
        });
        lintPublish(config, definition, source, findings);
        lintNotify(config, definition, source, findings);
        lintDatasource(config, consumer.source(), definition, source, findings);
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
    private void lintDatasource(AppConfig config, Path sourceFile, RouteDefinition definition,
            String source, List<LintFinding> findings) {
        boolean read = READ_DATASOURCE_RECIPES.contains(definition.recipe());
        if (declaredDatasource(definition.datasource())
                && !"main".equals(definition.datasource())) {
            if (read) {
                lintDatasourceName(config, sourceFile, definition.datasource(), source, findings);
            } else if (TRANSACTIONAL_DATASOURCE_RECIPES.contains(definition.recipe())) {
                if (mainAnchored(definition)) {
                    findings.add(new LintFinding("TQL-YAML-1036", "error", source,
                            "a 'datasource: " + definition.datasource() + "' route cannot declare"
                                    + " notify:/publish:/outbox: or sequence allocation - they"
                                    + " ride the main connector; project through main instead",
                            lineOf(sourceFile, "datasource:"), null));
                } else {
                    lintDatasourceName(config, sourceFile, definition.datasource(), source,
                            findings);
                }
            } else {
                findings.add(new LintFinding("TQL-YAML-1036", "error", source,
                        "datasource: is not supported on the '" + definition.recipe()
                                + "' recipe - its pipeline runs on main",
                        lineOf(sourceFile, "datasource:"), null));
            }
        }
        SqlBinding sql = definition.sql();
        if (sql != null && declaredDatasource(sql.datasource())) {
            if (read) {
                lintDatasourceName(config, sourceFile, sql.datasource(), source, findings);
            } else {
                findings.add(new LintFinding("TQL-YAML-1037", "error", source,
                        "sql.datasource on the '" + definition.recipe() + "' recipe would split"
                                + " the command transaction - a transactional pipeline runs on"
                                + " one connection"));
            }
        }
        definition.steps().forEach((name, step) -> {
            if (declaredDatasource(step.datasource())) {
                findings.add(new LintFinding("TQL-YAML-1037", "error", source,
                        "Step '" + name + "' declares datasource: - a transactional pipeline is"
                                + " one transaction on one connection and cannot pick a connector"
                                + " per step"));
            }
        });
        definition.queries().forEach((name, query) -> {
            if (declaredDatasource(query.datasource())) {
                lintDatasourceName(config, sourceFile, query.datasource(), source, findings);
            }
        });
        if (definition.fileImport() != null && definition.fileImport().sql() != null
                && declaredDatasource(definition.fileImport().sql().datasource())) {
            findings.add(new LintFinding("TQL-YAML-1037", "error", source,
                    "import.sql cannot declare datasource: - the import pipeline runs on main"));
        }
        if (definition.fileExport() != null && definition.fileExport().sql() != null
                && declaredDatasource(definition.fileExport().sql().datasource())) {
            findings.add(new LintFinding("TQL-YAML-1037", "error", source,
                    "export.sql cannot declare datasource: - the export pipeline runs on main"));
        }
    }

    /** Whether a {@code datasource:} value is actually declared (non-null, non-blank). */
    private static boolean declaredDatasource(String datasource) {
        return datasource != null && !datasource.isBlank();
    }

    /** Whether the route declares a feature whose tables live on the main connector. */
    private static boolean mainAnchored(RouteDefinition definition) {
        return !definition.notifications().isEmpty() || definition.publish() != null
                || definition.outbox() != null
                || (definition.sql() != null && definition.sql().isSequence())
                || definition.steps().values().stream().anyMatch(SqlBinding::isSequence);
    }

    /** {@code TQL-YAML-1035}: a non-main connector must exist under {@code tesseraql.datasources}
     * ({@code main} is always legal — an embedded database can supply it outside config). */
    private void lintDatasourceName(AppConfig config, Path sourceFile, String name, String source,
            List<LintFinding> findings) {
        if ("main".equals(name) || config.navigate("tesseraql.datasources." + name) != null) {
            return;
        }
        findings.add(new LintFinding("TQL-YAML-1035", "error", source,
                "datasource '" + name + "' is not declared under tesseraql.datasources",
                lineOf(sourceFile, "datasource:"), null));
    }

    /**
     * The duckdb datasource kind (docs/duckdb.md) is a query engine, never a system of record —
     * {@code TQL-YAML-1040} holds the structural constraints: {@code main} can never be duckdb,
     * a duckdb datasource has no migration tree, its {@code fileScopes} must declare
     * traversal-free roots (with {@code partitionBy} limited to {@code tenant}), it is never a
     * projection target, and route pipelines on it are read-shaped. {@code TQL-SQL-2111} holds the
     * SQL-content rules: file placeholders only on duckdb SQL, only naming declared scopes, and
     * file-reading functions never taking a raw argument.
     */
    private void lintDuckDb(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        AppConfig config = manifest.config();
        String configSource = "config/tesseraql.yml";
        if (duckDbDatasource(config, "main")) {
            findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                    "tesseraql.datasources.main cannot be a duckdb datasource - the engine holds"
                            + " nothing durable and framework tables live on main"));
        }
        if (config.navigate("tesseraql.datasources") instanceof java.util.Map<?, ?> datasources) {
            for (Object nameKey : datasources.keySet()) {
                String name = String.valueOf(nameKey);
                if (!duckDbDatasource(config, name)) {
                    continue;
                }
                lintFileScopes(config, name, configSource, findings);
                lintDuckDbEngineConfig(config, name, configSource, findings);
                if (Files.isDirectory(appHome.resolve("db").resolve(name).resolve("migration"))) {
                    findings.add(new LintFinding("TQL-YAML-1040", "error",
                            "db/" + name + "/migration",
                            "a duckdb datasource is a query engine with nothing durable to"
                                    + " migrate - remove db/" + name + "/migration"));
                }
            }
        }
        for (RouteFile route : manifest.routes()) {
            lintDuckDbRoute(appHome, config, route, findings);
        }
        for (io.tesseraql.yaml.manifest.JobFile job : manifest.jobs()) {
            String source = appHome.relativize(job.source()).toString().replace('\\', '/');
            String datasource = declaredDatasource(job.definition().datasource())
                    ? job.definition().datasource()
                    : "main";
            if (declaredDatasource(job.definition().datasource())
                    && !"main".equals(job.definition().datasource())
                    && config.navigate(
                            "tesseraql.datasources." + job.definition().datasource()) == null) {
                findings.add(new LintFinding("TQL-YAML-1035", "error", source,
                        "datasource '" + job.definition().datasource()
                                + "' is not declared under tesseraql.datasources"));
            }
            for (io.tesseraql.yaml.model.PipelineStep step : job.definition().effectiveSteps()) {
                if (step.sql() != null) {
                    lintDuckDbSql(config, job.source().getParent(), step.sql(), datasource,
                            source, findings);
                }
            }
        }
        for (RouteFile consumer : manifest.consumers()) {
            String datasource = consumer.definition().datasource();
            if (declaredDatasource(datasource) && duckDbDatasource(config, datasource)) {
                findings.add(new LintFinding("TQL-YAML-1040", "error",
                        appHome.relativize(consumer.source()).toString().replace('\\', '/'),
                        "a duckdb datasource is not a projection target - it holds nothing"
                                + " durable; project into a server datasource instead"));
            }
        }
    }

    /** Validates a duckdb datasource's declared {@code fileScopes} block. */
    private void lintFileScopes(AppConfig config, String name, String configSource,
            List<LintFinding> findings) {
        Object scopes = config.navigate("tesseraql.datasources." + name + ".duckdb.fileScopes");
        if (!(scopes instanceof java.util.Map<?, ?> scopeMap)) {
            return;
        }
        for (Object scopeKey : scopeMap.keySet()) {
            String scopeName = String.valueOf(scopeKey);
            String prefix = "tesseraql.datasources." + name + ".duckdb.fileScopes." + scopeName
                    + ".";
            String root = config.getString(prefix + "root").orElse(null);
            if (root == null || root.isBlank()) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "file scope '" + scopeName + "' on datasource '" + name
                                + "' declares no root: directory"));
            } else if (root.contains("..") || root.indexOf('\'') >= 0 || root.indexOf('\\') >= 0) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "file scope '" + scopeName + "' on datasource '" + name
                                + "' must declare a plain directory root without '..', quotes,"
                                + " or backslashes"));
            }
            String partitionBy = config.getString(prefix + "partitionBy").orElse(null);
            if (partitionBy != null && !"tenant".equals(partitionBy)) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "file scope '" + scopeName + "' on datasource '" + name
                                + "' partitionBy must be 'tenant', not '" + partitionBy + "'"));
            }
        }
    }

    /** The per-route duckdb rules: read-shaped pipelines and the SQL-content file rules. */
    private void lintDuckDbRoute(Path appHome, AppConfig config, RouteFile route,
            List<LintFinding> findings) {
        RouteDefinition definition = route.definition();
        String source = appHome.relativize(route.source()).toString().replace('\\', '/');
        String routeDatasource = declaredDatasource(definition.datasource())
                ? definition.datasource()
                : "main";
        if (duckDbDatasource(config, routeDatasource)
                && !READ_DATASOURCE_RECIPES.contains(definition.recipe())) {
            findings.add(new LintFinding("TQL-YAML-1040", "error", source,
                    "a duckdb datasource serves reads - the '" + definition.recipe()
                            + "' recipe carries durable state and belongs on a server"
                            + " datasource"));
        }
        lintDuckDbSql(config, route.source().getParent(), definition.sql(), routeDatasource,
                source, findings);
        definition.queries().forEach((name, query) -> lintDuckDbSql(config,
                route.source().getParent(), query,
                declaredDatasource(query.datasource()) ? query.datasource() : routeDatasource,
                source, findings));
    }

    /**
     * Validates a duckdb datasource's {@code extensions:} and {@code attach:} declarations so a
     * misdeclaration is a lint error here, not a boot failure — mirroring the runtime's checks.
     */
    private void lintDuckDbEngineConfig(AppConfig config, String name, String configSource,
            List<LintFinding> findings) {
        Object extensions = config.navigate("tesseraql.datasources." + name + ".duckdb.extensions");
        if (extensions instanceof java.util.List<?> list) {
            for (Object entry : list) {
                if (!String.valueOf(entry).matches("[a-z0-9_]+")) {
                    findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                            "duckdb extension '" + entry + "' on datasource '" + name
                                    + "' is not a plain extension name"));
                }
            }
        }
        if (config.navigate(
                "tesseraql.datasources." + name + ".duckdb.lake") instanceof java.util.Map<?, ?>) {
            String prefix = "tesseraql.datasources." + name + ".duckdb.lake.";
            String catalog = config.getString(prefix + "catalog").orElse("main");
            String schema = config.getString(prefix + "schema").orElse("ducklake");
            String data = config.getString(prefix + "data").orElse(null);
            String alias = config.getString(prefix + "as").orElse("lake");
            String mode = config.getString(prefix + "mode").orElse("readonly");
            if (data == null || data.isBlank()) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb lake on datasource '" + name + "' declares no data: directory"));
            } else if (data.contains("..") || data.indexOf('\'') >= 0 || data.indexOf('\\') >= 0) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb lake data: on datasource '" + name + "' must be a plain"
                                + " directory path without '..', quotes, or backslashes"));
            }
            if (!"main".equals(catalog)
                    && config.navigate("tesseraql.datasources." + catalog) == null) {
                findings.add(new LintFinding("TQL-YAML-1035", "error", configSource,
                        "datasource '" + catalog + "' is not declared under"
                                + " tesseraql.datasources"));
            }
            if (duckDbDatasource(config, catalog)) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb lake catalog '" + catalog + "' must be a PostgreSQL datasource"
                                + " holding the lake metadata"));
            }
            if (!schema.matches("[A-Za-z_][A-Za-z0-9_]*")
                    || !alias.matches("[A-Za-z_][A-Za-z0-9_]*") || "main".equals(alias)) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb lake schema/as on datasource '" + name + "' must be plain"
                                + " identifiers, and as: never 'main'"));
            }
            if (!"readonly".equals(mode) && !"readwrite".equals(mode)) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb lake mode must be readonly or readwrite, not '" + mode + "'"));
            }
            Object lakeExtensions = config.navigate(
                    "tesseraql.datasources." + name + ".duckdb.extensions");
            if (!(lakeExtensions instanceof java.util.List<?> lakeList)
                    || !lakeList.contains("ducklake") || !lakeList.contains("postgres")) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb lake on datasource '" + name + "' needs extensions:"
                                + " [ducklake, postgres] declared, so offline cache provisioning"
                                + " covers them"));
            }
            if (data != null && data.startsWith("s3://")) {
                if (!(lakeExtensions instanceof java.util.List<?> remoteList)
                        || !remoteList.contains("httpfs")) {
                    findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                            "a remote duckdb lake on datasource '" + name
                                    + "' needs httpfs in extensions:"));
                }
                if (!data.endsWith("/")) {
                    findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                            "duckdb lake data: on datasource '" + name + "' must be an s3://"
                                    + " prefix ending in '/' (the scoped secret covers exactly"
                                    + " this prefix)"));
                }
                Object credentials = config.navigate(
                        "tesseraql.datasources." + name + ".duckdb.lake.credentials");
                boolean keyed = credentials instanceof java.util.Map<?, ?> map
                        && map.containsKey("keyId") && map.containsKey("secret");
                boolean chain = "instance".equals(config.getString(
                        "tesseraql.datasources." + name + ".duckdb.lake.credentials")
                        .orElse(null));
                if (!keyed && !chain) {
                    findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                            "a remote duckdb lake on datasource '" + name + "' needs"
                                    + " credentials: {keyId, secret} secret references or"
                                    + " 'instance' for the AWS credential chain"));
                }
                if (config.navigate("tesseraql.datasources." + name
                        + ".duckdb.fileScopes") instanceof java.util.Map<?, ?>) {
                    findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                            "datasource '" + name + "' declares a remote lake and fileScopes:"
                                    + " - a remote-lake datasource has no governed local-file"
                                    + " surface; compose across two duckdb datasources"));
                }
            } else if (data != null && data.contains("://")) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb lake data: on datasource '" + name + "' must be a local"
                                + " directory or an s3:// prefix (S3-compatible stores use"
                                + " s3:// plus endpoint:)"));
            }
        }
        if (config.navigate("tesseraql.datasources." + name
                + ".duckdb.remotes") instanceof java.util.Map<?, ?> remotes) {
            for (Object remoteName : remotes.keySet()) {
                String prefix = "tesseraql.datasources." + name + ".duckdb.remotes."
                        + remoteName + ".";
                String url = config.getString(prefix + "url").orElse("");
                if (!url.startsWith("s3://") || !url.endsWith("/")) {
                    findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                            "duckdb remote '" + remoteName + "' on datasource '" + name
                                    + "' needs url: an s3:// prefix ending in '/'"));
                }
                boolean keyed = config.navigate(prefix.substring(0, prefix.length() - 1)
                        + ".credentials") instanceof java.util.Map<?, ?> map
                        && map.containsKey("keyId") && map.containsKey("secret");
                boolean chain = "instance".equals(
                        config.getString(prefix + "credentials").orElse(null));
                if (!keyed && !chain) {
                    findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                            "duckdb remote '" + remoteName + "' on datasource '" + name
                                    + "' needs credentials: {keyId, secret} or 'instance'"));
                }
            }
            Object remoteExtensions = config.navigate(
                    "tesseraql.datasources." + name + ".duckdb.extensions");
            if (!(remoteExtensions instanceof java.util.List<?> extList)
                    || !extList.contains("httpfs")) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb remotes: on datasource '" + name
                                + "' need httpfs in extensions:"));
            }
        }
        Object attach = config.navigate("tesseraql.datasources." + name + ".duckdb.attach");
        if (!(attach instanceof java.util.List<?> entries)) {
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (!(entries.get(i) instanceof java.util.Map<?, ?> entry)) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb attach entry " + i + " on datasource '" + name
                                + "' must be a mapping with datasource:"));
                continue;
            }
            String target = entry.get("datasource") == null
                    ? null
                    : config.resolve(String.valueOf(entry.get("datasource")));
            String alias = entry.get("as") == null
                    ? target
                    : config.resolve(String.valueOf(entry.get("as")));
            String mode = entry.get("mode") == null
                    ? "readonly"
                    : config.resolve(String.valueOf(entry.get("mode")));
            if (target == null || target.isBlank()) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb attach entry " + i + " on datasource '" + name
                                + "' declares no datasource:"));
                continue;
            }
            if (!"main".equals(target)
                    && config.navigate("tesseraql.datasources." + target) == null) {
                findings.add(new LintFinding("TQL-YAML-1035", "error", configSource,
                        "datasource '" + target + "' is not declared under"
                                + " tesseraql.datasources"));
            }
            if (duckDbDatasource(config, target)) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb attach target '" + target + "' is itself a duckdb datasource;"
                                + " attach targets are server datasources"));
            }
            if (alias == null || !alias.matches("[A-Za-z_][A-Za-z0-9_]*")
                    || "main".equals(alias)) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb attach '" + target + "' on datasource '" + name
                                + "' needs as: a plain identifier other than 'main' (DuckDB's"
                                + " own default schema is named main)"));
            }
            if (!"readonly".equals(mode) && !"readwrite".equals(mode)) {
                findings.add(new LintFinding("TQL-YAML-1040", "error", configSource,
                        "duckdb attach '" + target + "' mode must be readonly or readwrite,"
                                + " not '" + mode + "'"));
            }
        }
    }

    /** Functions that read a file; on duckdb SQL their argument must be a file placeholder. */
    private static final Pattern FILE_FUNCTION = Pattern.compile(
            "\\b(?:read_csv_auto|read_csv|read_parquet|read_json_auto|read_json|read_text"
                    + "|read_blob|parquet_scan|glob)\\s*\\(\\s*([^\\s])");

    /** The SQL-content file rules for one binding, against its effective datasource. */
    private void lintDuckDbSql(AppConfig config, Path sourceDir, SqlBinding sql,
            String datasource, String source, List<LintFinding> findings) {
        if (sql == null || sql.isContract() || sql.file() == null) {
            return;
        }
        Path sqlFile = sourceDir.resolve(sql.file());
        if (!Files.isRegularFile(sqlFile)) {
            return; // missing-file is reported separately
        }
        boolean duckDb = duckDbDatasource(config, datasource);
        String text;
        List<SqlNode> nodes;
        try {
            text = Files.readString(sqlFile);
            nodes = Sql2WayParser.parse(text);
        } catch (Exception ignored) {
            return; // SQL syntax / IO errors surface through other checks
        }
        List<SqlNode.FilePath> filePaths = new ArrayList<>();
        collectFilePaths(nodes, filePaths);
        for (SqlNode.FilePath filePath : filePaths) {
            String reference = "${" + filePath.channel() + "." + filePath.name() + "}";
            if (!duckDb) {
                findings.add(new LintFinding("TQL-SQL-2111", "error", source,
                        "File placeholder " + reference + " only resolves on a duckdb datasource;"
                                + " this SQL runs on '" + datasource + "'",
                        filePath.sourceLine(), null));
            } else if ("dataset".equals(filePath.channel())) {
                Map<String, String> params = sql.params() == null ? Map.of() : sql.params();
                if (!params.containsKey(filePath.name())) {
                    findings.add(new LintFinding("TQL-SQL-2111", "error", source,
                            "${dataset." + filePath.name() + "} needs a params: entry named '"
                                    + filePath.name() + "' binding the dataset reference",
                            filePath.sourceLine(), null));
                }
            } else if ("scope".equals(filePath.channel())
                    && (!(config.navigate("tesseraql.datasources." + datasource
                            + ".duckdb.fileScopes") instanceof java.util.Map<?, ?> scopeMap)
                            || !scopeMap.containsKey(filePath.name()))) {
                findings.add(new LintFinding("TQL-SQL-2111", "error", source,
                        "File scope '" + filePath.name() + "' is not declared under"
                                + " tesseraql.datasources." + datasource + ".duckdb.fileScopes",
                        filePath.sourceLine(), null));
            }
        }
        if (duckDb) {
            for (SqlNode.FilePath filePath : filePaths) {
                if ("remote".equals(filePath.channel())) {
                    if (!(config.navigate("tesseraql.datasources." + datasource
                            + ".duckdb.remotes") instanceof java.util.Map<?, ?> remoteMap)
                            || !remoteMap.containsKey(filePath.name())) {
                        findings.add(new LintFinding("TQL-SQL-2111", "error", source,
                                "Remote '" + filePath.name() + "' is not declared under"
                                        + " tesseraql.datasources." + datasource
                                        + ".duckdb.remotes",
                                filePath.sourceLine(), null));
                    }
                } else if (remoteTier(config, datasource)) {
                    findings.add(new LintFinding("TQL-SQL-2111", "error", source,
                            "A remote-tier datasource has no governed local-file surface;"
                                    + " ${scope.*}/${dataset.*} resolve on a local duckdb"
                                    + " datasource - compose across two datasources",
                            filePath.sourceLine(), null));
                }
            }
            lintEngineManagementStatements(text, source, findings);
            Matcher matcher = FILE_FUNCTION.matcher(text);
            while (matcher.find()) {
                // A placeholder site starts with the 2-way comment: `read_parquet(/* ${...} */ ...`.
                if (!"/".equals(matcher.group(1))) {
                    findings.add(new LintFinding("TQL-SQL-2111", "error", source,
                            "A file-reading function on a duckdb datasource must take a"
                                    + " ${scope.*} file placeholder, not a raw argument",
                            lineAt(text, matcher.start()), null));
                }
            }
        }
    }

    private static void collectFilePaths(List<SqlNode> nodes, List<SqlNode.FilePath> out) {
        for (SqlNode node : nodes) {
            switch (node) {
                case SqlNode.FilePath filePath -> out.add(filePath);
                case SqlNode.If conditional -> conditional.branches()
                        .forEach(branch -> collectFilePaths(branch.body(), out));
                case SqlNode.For loop -> collectFilePaths(loop.body(), out);
                default -> {
                    // Text/Bind/ListBind/Embedded/Scope carry no file placeholders.
                }
            }
        }
    }

    /**
     * App SQL on a duckdb datasource must be plain queries: engine-management statements are
     * init-time concerns the runtime owns. The local tier's fence refuses them at runtime
     * anyway; on the remote tier this rule is the load-bearing control (docs/duckdb.md,
     * decision point 13), so it errors at build time on both.
     */
    private void lintEngineManagementStatements(String text, String source,
            List<LintFinding> findings) {
        int offset = 0;
        for (String statement : text.split(";")) {
            String stripped = statement
                    .replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("(?m)--.*$", " ")
                    .strip()
                    .toUpperCase(java.util.Locale.ROOT);
            boolean management = stripped.startsWith("ATTACH") || stripped.startsWith("DETACH")
                    || stripped.startsWith("INSTALL") || stripped.startsWith("FORCE INSTALL")
                    || stripped.startsWith("LOAD ") || stripped.equals("LOAD")
                    || stripped.startsWith("SET ") || stripped.startsWith("RESET")
                    || stripped.startsWith("PRAGMA")
                    || stripped.matches("CREATE\\s+(OR\\s+REPLACE\\s+)?(PERSISTENT\\s+)?SECRET.*")
                    || stripped.startsWith("DROP SECRET");
            if (management) {
                findings.add(new LintFinding("TQL-SQL-2111", "error", source,
                        "App SQL on a duckdb datasource must be plain queries -"
                                + " ATTACH/DETACH/INSTALL/LOAD/CREATE SECRET/SET/PRAGMA are"
                                + " init-time concerns the runtime owns (docs/duckdb.md)",
                        lineAt(text, offset), null));
            }
            offset += statement.length() + 1;
        }
    }

    /** The 1-based line of a character offset in {@code text}. */
    private static int lineAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Whether the named duckdb datasource runs the remote tier (remote lake or remotes). */
    private static boolean remoteTier(AppConfig config, String name) {
        return remoteLake(config, name)
                || (duckDbDatasource(config, name)
                        && config.navigate("tesseraql.datasources." + name
                                + ".duckdb.remotes") instanceof java.util.Map<?, ?>);
    }

    /** Whether the named duckdb datasource declares a lake on object storage. */
    private static boolean remoteLake(AppConfig config, String name) {
        return duckDbDatasource(config, name)
                && config.navigate("tesseraql.datasources." + name
                        + ".duckdb.lake") instanceof java.util.Map<?, ?>
                && config.getString("tesseraql.datasources." + name + ".duckdb.lake.data")
                        .orElse("").startsWith("s3://");
    }

    /** Whether the named datasource resolves to the duckdb dialect (mirrors the compiler). */
    private static boolean duckDbDatasource(AppConfig config, String name) {
        String prefix = "tesseraql.datasources." + name + ".";
        String dialect = config.getString(prefix + "dialect").orElse(null);
        if (dialect != null) {
            return "duckdb".equalsIgnoreCase(dialect);
        }
        return config.getString(prefix + "jdbcUrl")
                .flatMap(io.tesseraql.core.dialect.Dialect::fromJdbcUrl)
                .filter(d -> d == io.tesseraql.core.dialect.Dialect.DUCKDB)
                .isPresent();
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
     * Statically checks a batch job's pipeline steps (roadmap Phase 20, 26): a step declares
     * exactly one of {@code sql:}, {@code notify:}, or {@code http-call:}; notify steps lint like a
     * route's, and http-call steps lint their egress against the allow-list (deny by default).
     */
    private void lintJob(Path appHome, AppConfig config, io.tesseraql.yaml.manifest.JobFile job,
            List<LintFinding> findings) {
        String source = appHome.relativize(job.source()).toString().replace('\\', '/');
        if (job.definition().trigger() != null && job.definition().trigger().poll() != null) {
            lintPollJob(config, job, source, findings);
        }
        for (io.tesseraql.yaml.model.PipelineStep step : job.definition().pipeline()) {
            int declared = 0;
            if (step.sql() != null) {
                declared++;
            }
            if (step.notification() != null) {
                declared++;
            }
            if (step.httpCall() != null) {
                declared++;
            }
            if (declared != 1) {
                findings.add(new LintFinding("TQL-FIELD-2004", "error", source, "Step '"
                        + step.id()
                        + "' must declare exactly one of sql:, notify:, or http-call:"));
                continue;
            }
            if (step.notification() != null) {
                lintNotifySpec(config, step.id(), step.notification(), source, findings);
            } else if (step.httpCall() != null) {
                lintHttpCall(config, step.id(), step.httpCall(), source, findings);
            }
        }
    }

    /**
     * Statically checks a {@code poll:}-triggered file-import job (roadmap Phase 26): the source is
     * a known kind with a path, a remote source has an allow-listed host
     * ({@code TQL-SEC-4080}, deny by default) and a configured credential ({@code TQL-SEC-4081}, a
     * warning), an SFTP source should verify the server's host key against
     * {@code tesseraql.connectors.poll.knownHostsFile} ({@code TQL-SEC-4084}, a warning), and the
     * job carries an {@code import:} block whose per-row SQL file exists.
     */
    private void lintPollJob(AppConfig config, io.tesseraql.yaml.manifest.JobFile job,
            String source,
            List<LintFinding> findings) {
        io.tesseraql.yaml.model.PollSpec poll = job.definition().trigger().poll();
        if (job.definition().trigger().schedule() != null) {
            findings.add(new LintFinding("TQL-YAML-1005", "error", source,
                    "Job '" + job.definition().id()
                            + "' declares both a schedule and a poll trigger; declare one"));
        }
        String kind = poll.effectiveSource();
        if (!List.of("local", "sftp", "ftps").contains(kind)) {
            findings.add(new LintFinding("TQL-YAML-1005", "error", source,
                    "Poll trigger source must be local, sftp, or ftps (was '" + poll.source()
                            + "')"));
        }
        if (poll.path() == null || poll.path().isBlank()) {
            findings.add(new LintFinding("TQL-YAML-1005", "error", source,
                    "Poll trigger needs a path: (the directory to poll)"));
        }
        if (poll.isRemote()) {
            if (poll.host() == null || poll.host().isBlank()) {
                findings.add(new LintFinding("TQL-YAML-1005", "error", source,
                        "Poll trigger source '" + kind + "' needs a host:"));
            } else {
                List<String> allowedHosts = new java.util.ArrayList<>();
                if (config
                        .navigate("tesseraql.connectors.poll.allowedHosts") instanceof List<?> h) {
                    h.forEach(value -> allowedHosts.add(String.valueOf(value)));
                }
                if (!io.tesseraql.yaml.http.HttpOutbound.hostAllowed(allowedHosts, poll.host())) {
                    findings.add(new LintFinding("TQL-SEC-4080", "error", source,
                            "Poll trigger targets host '" + poll.host() + "' which is not in"
                                    + " tesseraql.connectors.poll.allowedHosts (deny by default)"));
                }
            }
            if (poll.credential() != null && !poll.credential().isBlank()
                    && config.navigate(
                            "tesseraql.connectors.poll.credentials." + poll.credential()) == null) {
                findings.add(new LintFinding("TQL-SEC-4081", "warning", source,
                        "Poll trigger references undeclared credential '" + poll.credential()
                                + "'"));
            }
            if ("sftp".equals(kind)
                    && config.navigate("tesseraql.connectors.poll.knownHostsFile") == null) {
                findings.add(new LintFinding("TQL-SEC-4084", "warning", source,
                        "SFTP poll source does not verify the server's SSH host key; set"
                                + " tesseraql.connectors.poll.knownHostsFile to pin it"));
            }
        }
        io.tesseraql.yaml.model.ImportSpec importSpec = job.definition().fileImport();
        if (importSpec == null || importSpec.sql() == null || importSpec.sql().file() == null) {
            findings.add(new LintFinding("TQL-YAML-1006", "error", source, "Poll-triggered job '"
                    + job.definition().id() + "' needs an import: block with a per-row sql.file"));
        } else if (!Files.isRegularFile(
                job.source().getParent().resolve(importSpec.sql().file()))) {
            findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                    "Referenced SQL file is missing: " + importSpec.sql().file()));
        }
    }

    /**
     * cache: lints (docs/response-shaping.md, "HTTP caching") — TQL-YAML-1025: caching is a
     * query-recipe key (a command's response must never come from a cache); {@code public}
     * visibility only on {@code auth: public} routes (an authenticated response is
     * per-principal by definition); durations must parse.
     */
    private void lintHttpCache(RouteDefinition definition, String source,
            List<LintFinding> findings) {
        var cache = definition.cache();
        if (cache == null) {
            return;
        }
        String recipe = definition.recipe();
        if (!"query-json".equals(recipe) && !"query-html".equals(recipe)
                && !"page".equals(recipe)) {
            findings.add(new LintFinding("TQL-YAML-1025", "error", source,
                    "cache: is only supported on query recipes"
                            + " (query-json, query-html, page), not '" + recipe + "'"));
        }
        String visibility = cache.effectiveVisibility();
        if (!"private".equals(visibility) && !"public".equals(visibility)) {
            findings.add(new LintFinding("TQL-YAML-1025", "error", source,
                    "cache.visibility must be 'private' or 'public', got '" + visibility
                            + "'"));
        } else if ("public".equals(visibility) && (definition.security() == null
                || !"public".equals(definition.security().auth()))) {
            findings.add(new LintFinding("TQL-YAML-1025", "error", source,
                    "cache.visibility: public is only allowed on auth: public routes - an"
                            + " authenticated response is per-principal"));
        }
        for (String duration : new String[]{cache.maxAge(), cache.staleWhileRevalidate()}) {
            if (duration == null || duration.isBlank()) {
                continue;
            }
            try {
                io.tesseraql.core.util.Durations.toMillis(duration);
            } catch (RuntimeException ex) {
                findings.add(new LintFinding("TQL-YAML-1025", "error", source,
                        "cache: unparseable duration '" + duration + "'"));
            }
        }
    }

    /** rateLimit.scope is {@code node} or {@code cluster} (docs/deployment.md) — TQL-YAML-1023. */
    private void lintRateLimitScope(RouteDefinition definition, String source,
            List<LintFinding> findings) {
        var policy = definition.policy();
        if (policy == null || policy.rateLimit() == null) {
            return;
        }
        String scope = policy.rateLimit().scope();
        if (scope != null && !"node".equals(scope) && !"cluster".equals(scope)) {
            findings.add(new LintFinding("TQL-YAML-1023", "error", source,
                    "rateLimit.scope must be 'node' or 'cluster', got '" + scope + "'"));
        }
    }

    /**
     * http: source lints (docs/connectors.md, "HTTP sources"): sources belong to query
     * recipes only — a command must stay a pure transactional write (TQL-YAML-1022); a
     * source name must not shadow the {@code sql} result or a named query (the response
     * composes them side by side); and each source clears the same egress checks as a job's
     * http-call step (TQL-SEC-4070/4071/4072 via {@link #lintHttpCall}).
     */
    private void lintHttpSources(AppConfig config, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        if (definition.http().isEmpty()) {
            return;
        }
        String recipe = definition.recipe();
        if (!"query-json".equals(recipe) && !"query-html".equals(recipe)
                && !"page".equals(recipe)) {
            findings.add(new LintFinding("TQL-YAML-1022", "error", source,
                    "http: sources are only supported on query recipes"
                            + " (query-json, query-html, page), not '" + recipe + "'"));
        }
        definition.http().forEach((name, spec) -> {
            if ("sql".equals(name) || definition.queries().containsKey(name)) {
                findings.add(new LintFinding("TQL-YAML-1022", "error", source,
                        "http: source '" + name + "' shadows a SQL result key"));
            }
            lintHttpCall(config, name, spec.toCall(), source, findings);
        });
    }

    /**
     * Statically checks an {@code http-call} step's egress (roadmap Phase 26): the target host
     * must resolve to an allow-listed host ({@code TQL-SEC-4070}, deny by default), the url must be
     * an absolute http/https URL ({@code TQL-SEC-4071}), and a referenced credential should be
     * configured ({@code TQL-SEC-4072}, a warning since another environment may declare it). A url
     * carrying an unresolved {@code ${...}} secret in its host cannot be checked statically and is
     * left to the runtime's identical deny-by-default guard.
     */
    private void lintHttpCall(AppConfig config, String id,
            io.tesseraql.yaml.model.HttpCallSpec spec, String source, List<LintFinding> findings) {
        String resolved = null;
        if (spec.url() != null && !spec.url().isBlank()) {
            try {
                resolved = config.resolve(spec.url());
            } catch (RuntimeException ex) {
                resolved = spec.url();
            }
        }
        String host = null;
        String scheme = null;
        if (resolved != null) {
            try {
                java.net.URI uri = java.net.URI.create(resolved);
                host = uri.getHost();
                scheme = uri.getScheme();
            } catch (RuntimeException ex) {
                host = null;
            }
        }
        boolean absoluteHttp = host != null && scheme != null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        if (!absoluteHttp) {
            // Flag a genuinely missing or relative url, but not one we merely cannot resolve yet
            // (an unresolved ${...} secret in the host is checked by the runtime instead).
            if (resolved == null || !resolved.contains("${")) {
                findings.add(new LintFinding("TQL-SEC-4071", "error", source,
                        "http-call step '" + id + "' needs an absolute http or https url:"));
            }
            lintHttpCredential(config, id, spec, source, findings);
            return;
        }
        List<String> allowedHosts = new java.util.ArrayList<>();
        if (config.navigate("tesseraql.http.outbound.allowedHosts") instanceof List<?> declared) {
            declared.forEach(value -> allowedHosts.add(String.valueOf(value)));
        }
        if (!io.tesseraql.yaml.http.HttpOutbound.hostAllowed(allowedHosts, host)) {
            findings.add(new LintFinding("TQL-SEC-4070", "error", source, "http-call step '" + id
                    + "' targets host '" + host + "' which is not in"
                    + " tesseraql.http.outbound.allowedHosts (deny by default)"));
        }
        lintHttpCredential(config, id, spec, source, findings);
    }

    private void lintHttpCredential(AppConfig config, String id,
            io.tesseraql.yaml.model.HttpCallSpec spec, String source, List<LintFinding> findings) {
        String credential = spec.credential();
        if (credential == null || credential.isBlank()) {
            return;
        }
        if (config.navigate("tesseraql.http.outbound.credentials." + credential) == null) {
            findings.add(new LintFinding("TQL-SEC-4072", "warning", source, "http-call step '" + id
                    + "' references undeclared credential '" + credential + "'"));
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
        // An inbox message must be addressed (roadmap Phase 49): without a recipient there
        // is no user to deliver to, so this fails the build instead of dead-lettering.
        if (spec.channel() != null && "inbox".equals(config.getString(
                "tesseraql.notifications.channels." + spec.channel() + ".type")
                .orElse(null))
                && (spec.recipient() == null || spec.recipient().isBlank())) {
            findings.add(new LintFinding("TQL-YAML-1034", "error", source,
                    "Notification '" + id + "' delivers to inbox channel '" + spec.channel()
                            + "' but declares no recipient:"));
        }
    }

    /**
     * Statically checks a printable-document export (roadmap Phase 21): {@code format: pdf} is a
     * print format, so the workbook-only options ({@code sheet:}, {@code startCell:}) do not
     * apply, and the template - rendered through the standard template engine - must be an
     * {@code .html} file colocated with the route.
     */
    private void lintPdfExport(RouteFile route, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        io.tesseraql.yaml.model.ExportSpec spec = definition.fileExport();
        if (spec == null || !"pdf".equals(spec.format())) {
            return;
        }
        if (spec.sheet() != null || spec.startCell() != null) {
            findings.add(new LintFinding("TQL-YAML-1005", "error", source,
                    "pdf export: sheet:/startCell: are workbook options - a pdf lays out"
                            + " through its template, not cell placement"));
        }
        if (spec.template() == null) {
            return;
        }
        if (!spec.template().endsWith(".html")) {
            findings.add(new LintFinding("TQL-YAML-1006", "error", source,
                    "pdf export template '" + spec.template()
                            + "' must be an .html file (it renders through the template"
                            + " engine before PDF conversion)"));
        } else if (!Files.isRegularFile(route.source().getParent().resolve(spec.template()))) {
            findings.add(new LintFinding("TQL-YAML-1006", "error", source,
                    "pdf export references a missing template: " + spec.template()));
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

    /**
     * The 1-based line of {@code token}'s first occurrence in {@code source} (authoring
     * feedback, roadmap Phase 43) — a best-effort position for document rules, so editors can
     * jump near the offending key; null when the file is unreadable or the token is absent.
     */
    private static Integer lineOf(Path source, String token) {
        String text = readQuietly(source);
        int at = text.indexOf(token);
        if (at < 0) {
            return null;
        }
        int line = 1;
        for (int i = 0; i < at; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
