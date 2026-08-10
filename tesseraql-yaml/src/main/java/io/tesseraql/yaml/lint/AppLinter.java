package io.tesseraql.yaml.lint;

import io.tesseraql.core.expr.Expr;
import io.tesseraql.core.expr.ExpressionParser;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.manifest.ScopeFile;
import io.tesseraql.yaml.manifest.ToolFile;
import io.tesseraql.yaml.manifest.WorkflowFile;
import io.tesseraql.yaml.model.DeadlineSpec;
import io.tesseraql.yaml.model.ImportSpec;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.InputPolicy;
import io.tesseraql.yaml.model.JobDefinition;
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

    /**
     * The route recipes whose compiled pipeline runs {@code validate:} — every recipe that can
     * reach the transactional command processor. {@code command-json} and {@code query-json}
     * both route into it once a validate block is present, and {@code webhook} delegates to it
     * unconditionally. Queue consumers and MCP tools run it too; they are linted separately
     * because they carry no route recipe.
     */
    private static final List<String> VALIDATING_RECIPES = List.of("command-json", "query-json",
            "webhook");

    private static final Set<String> KNOWN_AUTH_MODES = Set.of("bearer", "browser", "api-key",
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
        io.tesseraql.yaml.calendar.Calendars calendars = lintCalendars(appHome, findings);
        for (io.tesseraql.yaml.manifest.JobFile job : manifest.jobs()) {
            lintJob(appHome, manifest.config(), job, calendars, findings);
        }
        lintJobChaining(appHome, manifest, findings);
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
        lintDocTypeLiterals(appHome, manifest, findings);
        lintWorkflowConfig(manifest.config(), findings);
        lintAttachments(appHome, manifest, findings);
        lintMailChannels(appHome, manifest.config(), findings);
        lintObjectStorageEgress(appHome, manifest, findings);
        lintViews(appHome, manifest, findings);
        lintBasePathLinks(appHome, manifest.config(), findings);
        lintDuckDb(appHome, manifest, findings);
        for (RouteFile route : manifest.routes()) {
            lintInputs(appHome, route, findings);
        }
        lintUnclaimedFiles(appHome, findings);
        return findings;
    }

    /** HTTP method stems a {@code web/**} route file may carry (mirrors {@code ManifestLoader}). */
    private static final Set<String> HTTP_METHOD_STEMS = Set.of("get", "post", "put", "patch",
            "delete", "head", "options");

    /**
     * Reports YAML documents that sit in a loadable app tree but no loader claims (TQL-APP-4205):
     * a {@code .yaml} extension where every loader filters on {@code .yml}, a {@code web/**} file
     * whose stem is not an HTTP method, or a {@code domains/rules/decisions/calendars} file in a
     * subdirectory (those loaders are non-recursive). Such a file simply does not exist at runtime
     * — the route 404s, the domain reference is unknown — with nothing pointing at the filename.
     */
    private void lintUnclaimedFiles(Path appHome, List<LintFinding> findings) {
        // Recursive route/document trees: any *.yml is claimed; a *.yaml is the giveaway.
        sweepYamlTree(appHome, "web", findings, (rel, stem, isYaml) -> {
            if (isYaml) {
                return "expected .yml, not .yaml";
            }
            // .view.yml (views) and .sample.yml (Studio preview fixtures) are legitimate.
            if (rel.endsWith(".view.yml") || rel.endsWith(".sample.yml")) {
                return null;
            }
            return HTTP_METHOD_STEMS.contains(stem)
                    ? null
                    : "a web/ route file must be named <method>.yml (get|post|put|patch|delete"
                            + "|head|options), or <name>.view.yml for a view";
        });
        for (String tree : List.of("batch", "workflow", "scope", "consume", "mcp", "attachments",
                "tests")) {
            sweepYamlTree(appHome, tree, findings,
                    (rel, stem, isYaml) -> isYaml ? "expected .yml, not .yaml" : null);
        }
        // Non-recursive shared-definition trees: a *.yaml, or a *.yml in a subdirectory, is dropped.
        for (String tree : List.of("domains", "rules", "decisions", "calendars")) {
            sweepYamlTree(appHome, tree, findings, (rel, stem, isYaml) -> {
                if (isYaml) {
                    return "expected .yml, not .yaml";
                }
                // rel is tree-relative; a separator means it is nested below the tree root.
                return rel.indexOf('/') >= 0
                        ? tree + "/ is loaded non-recursively; move this file to the " + tree
                                + "/ root"
                        : null;
            });
        }
    }

    /** A per-file verdict: a finding message, or {@code null} when the file is fine. */
    private interface UnclaimedRule {
        String check(String treeRelativePath, String stem, boolean isYaml);
    }

    private void sweepYamlTree(Path appHome, String tree, List<LintFinding> findings,
            UnclaimedRule rule) {
        Path root = appHome.resolve(tree);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                String name = file.getFileName().toString();
                boolean isYaml = name.endsWith(".yaml");
                if (!isYaml && !name.endsWith(".yml")) {
                    return;
                }
                String treeRelative = root.relativize(file).toString().replace('\\', '/');
                String stem = name.replaceFirst("\\.ya?ml$", "");
                String problem = rule.check(treeRelative, stem, isYaml);
                if (problem != null) {
                    findings.add(new LintFinding("TQL-APP-4205", "error",
                            relative(appHome, file),
                            "'" + tree + "/" + treeRelative + "' is not loaded — " + problem));
                }
            });
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    /**
     * Top-level keys renamed before v1 (docs/vocabulary-cleanup.md), per document-family root
     * record. The old spelling deserializes away silently, dropping the whole block — so it is a
     * hard error naming the replacement (TQL-YAML-1044), not a generic unknown-key warning. Only
     * unambiguously top-level renames live here; {@code notify:} is current on routes and renamed
     * only on workflows, so the map is keyed by the record it applies to.
     */
    private static final Map<Class<?>, Map<String, String>> REMOVED_TOP_LEVEL_KEYS = Map.of(
            RouteDefinition.class, Map.of("page", "pagination", "policy", "admission",
                    "params", "input"),
            JobDefinition.class, Map.of("params", "input"),
            WorkflowDefinition.class, Map.of("notify", "reminders"));

    private final Map<Class<?>, Set<String>> acceptedKeyCache = new java.util.HashMap<>();

    /**
     * The YAML keys a document-family root record accepts, derived from its record components and
     * their {@code @JsonProperty} overrides (so {@code notify}/{@code import}/{@code export} map
     * correctly). Cached per class.
     */
    private Set<String> acceptedTopLevelKeys(Class<?> recordClass) {
        return acceptedKeyCache.computeIfAbsent(recordClass, cls -> {
            Set<String> keys = new java.util.TreeSet<>();
            for (java.lang.reflect.RecordComponent component : cls.getRecordComponents()) {
                com.fasterxml.jackson.annotation.JsonProperty json = component
                        .getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
                keys.add(json != null && !json.value().isEmpty()
                        ? json.value()
                        : component.getName());
            }
            return keys;
        });
    }

    /**
     * Flags unknown top-level keys on a document (TQL-YAML-1043, warning) and renamed ones
     * (TQL-YAML-1044, error). The model records are {@code @JsonIgnoreProperties(ignoreUnknown)},
     * so without this a typo'd {@code securty:} block drops auth with no diagnostic. Top-level
     * only for now — nested blocks stay a follow-up. {@code extraKeys} carries keys a loader reads
     * from the raw tree rather than the record (e.g. mcp {@code description}/{@code uri}).
     */
    private void lintUnknownTopLevelKeys(Path appHome, Path file, Class<?> recordClass,
            Set<String> extraKeys, List<LintFinding> findings) {
        Map<String, Object> tree;
        try {
            tree = new io.tesseraql.yaml.SimpleYamlParser().parseTree(file);
        } catch (RuntimeException malformed) {
            // A malformed document already failed the manifest load before lint ran; skip it.
            return;
        }
        Set<String> accepted = acceptedTopLevelKeys(recordClass);
        Map<String, String> renamed = REMOVED_TOP_LEVEL_KEYS.getOrDefault(recordClass, Map.of());
        String source = relative(appHome, file);
        for (String key : tree.keySet()) {
            if (accepted.contains(key) || extraKeys.contains(key)) {
                continue;
            }
            String replacement = renamed.get(key);
            if (replacement != null) {
                findings.add(new LintFinding("TQL-YAML-1044", "error", source,
                        "'" + key + ":' was renamed to '" + replacement + ":' before v1 and is now "
                                + "silently dropped — rename it"));
            } else {
                findings.add(new LintFinding("TQL-YAML-1043", "warning", source,
                        "Unknown key '" + key + ":' (accepted: " + accepted
                                + ") — it is silently ignored"));
            }
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
     *
     * <p>Reached from routes, queue consumers, and MCP tools alike. The file the definition came
     * from was never read here, and taking it as a parameter is what made this look like a
     * route-only lint for as long as tools went unchecked.
     */
    private void lintEmit(RouteDefinition definition, String source,
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
     * Validates declarative views (roadmap Phase 39, docs/declarative-views.md).
     * Document-shape checks run once per view document — parse errors with the exception's own
     * code, a form's {@code action:} naming a POST route with an {@code input:} block
     * ({@code TQL-VIEW-3303}) whose fields the {@code fields:} entries actually declare
     * ({@code TQL-VIEW-3304}) with known widgets ({@code TQL-VIEW-3305}), slot names and
     * references ({@code TQL-VIEW-3306}/{@code 3302}), and {@code refreshOn:} wiring. Per
     * referencing route: the id resolves in the registry and is not combined with
     * {@code template:} ({@code TQL-VIEW-3302}), and source/search/sort wiring
     * ({@code TQL-VIEW-3308/3309/3310}). An app's {@code templates/tql/view/*.html} pattern
     * override carries the expected fragment signature ({@code TQL-VIEW-3307}, warning).
     */
    private void lintViews(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        lintViewDocuments(appHome, manifest, findings);
        for (RouteFile route : manifest.routes()) {
            var response = route.definition().response();
            var html = response == null ? null : response.html();
            if (html == null) {
                continue;
            }
            String routeSource = appHome.relativize(route.source()).toString()
                    .replace('\\', '/');
            if (!java.util.Set.of("auto", "always", "never")
                    .contains(html.effectiveShell())) {
                findings.add(new LintFinding("TQL-VIEW-3317", "error", routeSource,
                        "response.html.shell must be 'auto', 'always' or 'never', got: "
                                + html.shell()));
            }
            // views: binds declarative parts to a template: route (wave 2c): each id must
            // resolve, and a view: route embeds through its own document instead.
            if (!html.views().isEmpty() && html.view() != null) {
                findings.add(new LintFinding("TQL-VIEW-3302", "error", routeSource,
                        "response.html.views binds declarative parts to a template: route — a"
                                + " view: route embeds through its own document instead"));
            }
            for (String bound : html.views()) {
                if (manifest.viewById(bound) == null) {
                    findings.add(new LintFinding("TQL-VIEW-3302", "error", routeSource,
                            "views: " + bound + " does not resolve to a view document id"));
                }
            }
            if (html.view() == null) {
                continue;
            }
            String source = routeSource;
            if (html.template() != null) {
                findings.add(new LintFinding("TQL-VIEW-3302", "error", source,
                        "response.html declares both template: and view: — they are mutually"
                                + " exclusive"));
            }
            io.tesseraql.yaml.manifest.ViewFile viewFile = manifest.viewById(html.view());
            if (viewFile == null) {
                findings.add(new LintFinding("TQL-VIEW-3302", "error", source,
                        "view: " + html.view() + " does not resolve to a view document id"
                                + " (ids come from *.view.yml under web/ or templates/)"));
                continue;
            }
            io.tesseraql.yaml.view.ViewSpec spec = viewFile.spec();
            for (io.tesseraql.yaml.view.ViewSpec.Child child : spec.children()) {
                if (!declaresViewSource(route.definition(), child.source())) {
                    findings.add(new LintFinding("TQL-VIEW-3308", "error", source,
                            "view " + spec.id() + ": children source " + child.source()
                                    + " is not a named query or http source of the route"));
                }
            }
            for (io.tesseraql.yaml.view.ViewSpec.Panel panel : spec.panels()) {
                String panelSource = panel.source() == null || panel.source().isBlank()
                        ? "sql"
                        : panel.source();
                if (!declaresViewSource(route.definition(), panelSource)) {
                    findings.add(new LintFinding("TQL-VIEW-3308", "error", source,
                            "view " + spec.id() + ": panel source " + panelSource
                                    + " is not a named query or http source of the route"));
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

    /**
     * The per-document pass (docs/view-composition.md wave 1): every {@code *.view.yml} under
     * {@code web/} and {@code templates/} parses — reported once per document with the parse
     * error's own code (TQL-VIEW-3301/3313/3314), not once per referencing route. Parseable
     * documents are already in the manifest's registry; duplicate ids fail the manifest load
     * itself (TQL-VIEW-3315, the domains posture).
     */
    private void lintViewDocuments(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        java.util.Set<Path> indexed = new java.util.HashSet<>();
        io.tesseraql.yaml.domain.FieldDomains appDomains = io.tesseraql.yaml.domain.FieldDomains
                .load(appHome);
        for (io.tesseraql.yaml.manifest.ViewFile view : manifest.views()) {
            indexed.add(view.source());
            String source = appHome.relativize(view.source()).toString().replace('\\', '/');
            io.tesseraql.yaml.view.ViewSpec spec = view.spec();
            if (io.tesseraql.yaml.view.ViewSpec.FORM.equals(spec.view())) {
                lintFormView(manifest, source, spec, findings);
            }
            lintRefreshOn(manifest, source, spec, findings);
            // Read-side domain references (wave 3a): a column/field domain: must be declared.
            java.util.stream.Stream.concat(
                    spec.columns().stream().map(io.tesseraql.yaml.view.ViewSpec.Column::domain),
                    spec.fields().stream().map(io.tesseraql.yaml.view.ViewSpec.Field::domain))
                    .filter(java.util.Objects::nonNull)
                    .forEach(name -> {
                        try {
                            appDomains.require(name, "view " + spec.id());
                        } catch (io.tesseraql.core.error.TqlException ex) {
                            findings.add(new LintFinding(ex.code().toString(), "error", source,
                                    ex.getMessage()));
                        }
                    });
            // Embedded views (docs/view-composition.md wave 2b): the id resolves in the
            // registry, and the embedded document does not embed further (depth is 1).
            java.util.List<String> embeds = new ArrayList<>();
            spec.children().stream().map(io.tesseraql.yaml.view.ViewSpec.Child::view)
                    .filter(java.util.Objects::nonNull).forEach(embeds::add);
            spec.panels().stream().map(io.tesseraql.yaml.view.ViewSpec.Panel::view)
                    .filter(java.util.Objects::nonNull).forEach(embeds::add);
            for (String embedId : embeds) {
                io.tesseraql.yaml.manifest.ViewFile embedded = manifest.viewById(embedId);
                if (embedded == null) {
                    findings.add(new LintFinding("TQL-VIEW-3302", "error", source,
                            "view " + spec.id() + ": embedded view " + embedId
                                    + " does not resolve to a view document id"));
                    continue;
                }
                boolean embedsFurther = embedded.spec().children().stream()
                        .anyMatch(child -> child.view() != null)
                        || embedded.spec().panels().stream()
                                .anyMatch(panel -> panel.view() != null);
                if (embedsFurther) {
                    findings.add(new LintFinding("TQL-VIEW-3318", "error", source,
                            "view " + spec.id() + ": embedded view " + embedId
                                    + " embeds views itself — embedding depth is 1"));
                }
            }
            Path viewDir = view.source().getParent();
            for (String slotName : spec.slots().keySet()) {
                java.util.Set<String> allowed = io.tesseraql.yaml.view.ViewSpec
                        .slotsFor(spec.view());
                if (!allowed.contains(slotName)) {
                    findings.add(new LintFinding("TQL-VIEW-3306", "error", source,
                            "view " + spec.id() + ": unknown slot " + slotName + " (a "
                                    + spec.view() + " view offers " + allowed + ")"));
                    continue;
                }
                // Slot templates resolve against the view document's own directory, then
                // templates/ — never a referencing route's (docs/view-composition.md wave 1).
                String ref = spec.slots().get(slotName);
                int separator = ref.indexOf("::");
                String template = separator < 1 ? ref : ref.substring(0, separator).trim();
                Path slotColocated = viewDir.resolve(template).normalize();
                Path slotFile = Files.isRegularFile(slotColocated)
                        ? slotColocated
                        : appHome.resolve("templates").resolve(template).normalize();
                if (separator < 1 || !Files.isRegularFile(slotFile)) {
                    findings.add(new LintFinding("TQL-VIEW-3302", "error", source,
                            "view " + spec.id() + ": slot " + slotName + " reference " + ref
                                    + " does not resolve ('<template> :: <fragment>')"));
                }
            }
        }
        for (String root : List.of("web", "templates")) {
            Path tree = appHome.resolve(root);
            if (!Files.isDirectory(tree)) {
                continue;
            }
            try (var files = Files.walk(tree)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".view.yml"))
                        .filter(p -> !indexed.contains(p))
                        .sorted()
                        .forEach(file -> {
                            String source = appHome.relativize(file).toString()
                                    .replace('\\', '/');
                            try {
                                io.tesseraql.yaml.view.ViewSpec.parse(file);
                            } catch (io.tesseraql.core.error.TqlException ex) {
                                findings.add(new LintFinding(ex.code().toString(), "error",
                                        source, ex.getMessage()));
                            }
                        });
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }
    }

    /**
     * A child/panel {@code source:} must be {@code sql} or one of the route's {@code queries:}
     * or {@code http:} sources (TQL-VIEW-3308) — both publish the {@code {rows}} shape the
     * view model reads.
     */
    private static boolean declaresViewSource(RouteDefinition definition, String source) {
        if ("sql".equals(source)) {
            return true;
        }
        var queries = definition.queries();
        var http = definition.http();
        return (queries != null && queries.containsKey(source))
                || (http != null && http.containsKey(source));
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

    /** Attribute names whose value the browser resolves as a URL. */
    private static final String URL_ATTRIBUTES = "href|src|action|formaction|hx-get|hx-post"
            + "|hx-put|hx-patch|hx-delete|sse-connect";

    /**
     * A root-absolute URL in the application's own markup: {@code href="/orders"}, or the same
     * written as a Thymeleaf literal substitution, {@code th:href="|/orders/${id}|"}.
     */
    private static final Pattern ROOT_ABSOLUTE_URL = Pattern.compile(
            "(?<![\\w:-])(?:th:)?(" + URL_ATTRIBUTES + ")=\"\\|?(/(?!/)[^\"]*)\"");

    /**
     * TQL-TPL-2004, a warning: an application served under {@code tesseraql.http.basePath}
     * emits a URL rooted at the origin, where its own runtime does not answer
     * (docs/base-path.md decision 3). The remedy is a link expression — {@code th:href="@{/x}"}
     * — which resolves against the prefix.
     *
     * <p>A warning rather than an error, and only for applications that configured a prefix: a
     * page may legitimately link off-site or to a path outside its own mount point, and nothing
     * here can tell which. Applications served at the root of their origin are never told
     * anything, so the lint is silent for everyone until the day it is useful.
     */
    private void lintBasePathLinks(Path appHome, AppConfig config, List<LintFinding> findings) {
        if (io.tesseraql.core.http.BasePaths.normalize(
                config.getString("tesseraql.http.basePath").orElse(null)).isEmpty()) {
            return;
        }
        for (String tree : new String[]{"web", "templates"}) {
            Path root = appHome.resolve(tree);
            if (!java.nio.file.Files.isDirectory(root)) {
                continue;
            }
            try (var files = java.nio.file.Files.walk(root)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".html")).sorted()
                        .toList()) {
                    String source = appHome.relativize(file).toString().replace('\\', '/');
                    Matcher urls = ROOT_ABSOLUTE_URL.matcher(java.nio.file.Files.readString(file));
                    while (urls.find()) {
                        findings.add(new LintFinding("TQL-TPL-2004", "warning", source,
                                urls.group(1) + "=\"" + urls.group(2) + "\" is rooted at the"
                                        + " origin, and this application is served under"
                                        + " tesseraql.http.basePath — write th:" + urls.group(1)
                                        + "=\"@{" + urls.group(2) + "}\" unless the link is"
                                        + " deliberately outside the application"));
                    }
                }
            } catch (java.io.IOException ex) {
                findings.add(new LintFinding("TQL-TPL-2004", "warning", tree,
                        "templates could not be read: " + ex.getMessage()));
            }
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
     * {@code auth: api-key} route requires API-key config whose clients each store a key hash. Reads
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
        lintSamlConfig(config, findings);
        lintSecurityDefaults(appHome, manifest, config, findings);
        lintFieldDomains(appHome, manifest, findings);
        lintResponseHeaderDefaults(appHome, manifest, config, findings);
        lintAmbientPrincipal(appHome, manifest, findings);
        lintComponentPolicy(config, findings);
        lintRuleSets(appHome, manifest, findings);
        lintDecisions(appHome, manifest, findings);
    }

    /**
     * Lints decision tables (docs/decision-tables.md). Malformed contracts, bad cells, and
     * overlapping unique rows already failed the load (TQL-DECISION-4700..4707, 4714); what
     * remains is what only a whole-app view can see: a {@code decision.*} bind naming no
     * {@code decide:} entry of its route (which could otherwise only fail at runtime, the
     * TQL-SEC-4136 line), a first-hit row shadowed entirely by an earlier row, and a decision
     * nothing references.
     */
    private void lintDecisions(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        Set<String> referenced = new HashSet<>();
        for (Map.Entry<Path, RouteDefinition> document : authoringDocuments(manifest)) {
            RouteDefinition def = document.getValue();
            String source = appHome.relativize(document.getKey()).toString().replace('\\', '/');
            def.decide().values().forEach(use -> referenced.add(use.use()));
            for (String bind : decisionBinds(document.getKey(), def)) {
                String alias = bind.split("\\.")[1];
                if (!def.decide().containsKey(alias)) {
                    findings.add(new LintFinding("TQL-DECISION-4711", "error", source,
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
                        findings.add(new LintFinding("TQL-DECISION-4715", "warning", "decisions",
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
                .forEach(name -> findings.add(new LintFinding("TQL-DECISION-4716", "warning",
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
                        Map<String, Object> tree;
                        try {
                            tree = new io.tesseraql.yaml.SimpleYamlParser().parseTree(file);
                        } catch (RuntimeException malformed) {
                            return;
                        }
                        if (!(tree.get("decisions") instanceof Map<?, ?> decisions)) {
                            return;
                        }
                        String source = relative(appHome, file);
                        for (Map.Entry<?, ?> decision : decisions.entrySet()) {
                            if (decision.getValue() instanceof Map<?, ?> body
                                    && body.get("source") instanceof Map<?, ?> src
                                    && ((Map<String, Object>) src).containsKey("id")) {
                                findings.add(new LintFinding("TQL-DECISION-4718", "error", source,
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
        for (Map.Entry<Path, RouteDefinition> document : authoringDocuments(manifest)) {
            RouteDefinition def = document.getValue();
            String source = appHome.relativize(document.getKey()).toString().replace('\\', '/');
            def.steps().forEach((name, step) -> {
                if (step.when() != null && !step.when().isBlank()) {
                    checkDecisionExpression(source, "step '" + name + "' when:", step.when(),
                            def.decide(), findings);
                }
            });
        }
        for (WorkflowFile workflow : manifest.workflows()) {
            String source = relative(appHome, workflow.source());
            Map<String, List<io.tesseraql.yaml.model.TransitionSpec>> byFrom = new LinkedHashMap<>();
            for (io.tesseraql.yaml.model.TransitionSpec transition : workflow.definition()
                    .transitions()) {
                if (transition.guard() != null && transition.guard().expression() != null
                        && !transition.guard().expression().isBlank()) {
                    checkDecisionExpression(source, "transition '" + transition.id()
                            + "' guard", transition.guard().expression(),
                            effectiveDecide(workflow.definition(), transition), findings);
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
            List<LintFinding> findings) {
        Expr parsed;
        try {
            parsed = io.tesseraql.core.expr.ExpressionParser.parse(expression);
        } catch (RuntimeException unparseable) {
            // A malformed expression is its own lint's concern.
            return;
        }
        List<List<String>> paths = new ArrayList<>();
        collectGuardPaths(parsed, paths);
        for (List<String> path : paths) {
            if (path.size() >= 2 && "decision".equals(path.get(0))
                    && !decide.containsKey(path.get(1))) {
                findings.add(new LintFinding("TQL-DECISION-4711", "error", source,
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
                findings.add(new LintFinding("TQL-DECISION-4713", "error", source,
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
            findings.add(new LintFinding("TQL-DECISION-4712", "warning", source,
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
                findings.add(new LintFinding("TQL-DECISION-4717", "error", "decisions",
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
                findings.add(new LintFinding("TQL-DECISION-4710", "error", "decisions",
                        "Decision '" + name + "' maps table '" + table + "', which the schema"
                                + " sidecar does not know — regenerate .tesseraql/docs/"
                                + "schema.json or fix the mapping"));
                return;
            }
            columns.stream()
                    .filter(column -> column != null && !column.isBlank())
                    .filter(column -> !present.contains(column.toLowerCase(java.util.Locale.ROOT)))
                    .forEach(column -> findings.add(new LintFinding("TQL-DECISION-4710",
                            "error", "decisions", "Decision '" + name + "' maps column '"
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
        Set<String> found = new LinkedHashSet<>();
        Path dir = source.getParent();
        List<String> files = new ArrayList<>();
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
                collectDecisionBinds(Sql2WayParser.parse(Files.readString(sqlFile)), found);
            } catch (Exception ignored) {
                // Unparseable SQL is its own lint's concern.
            }
        }
        return found;
    }

    private static void collectDecisionBinds(List<SqlNode> nodes, Set<String> found) {
        for (SqlNode node : nodes) {
            switch (node) {
                case SqlNode.Bind bind -> addIfDecision(bind.expressionSource(), found);
                case SqlNode.ListBind bind -> addIfDecision(bind.expressionSource(), found);
                case SqlNode.If cond -> cond.branches()
                        .forEach(branch -> collectDecisionBinds(branch.body(), found));
                case SqlNode.For loop -> collectDecisionBinds(loop.body(), found);
                default -> {
                }
            }
        }
    }

    private static void addIfDecision(String expressionSource, Set<String> found) {
        String expression = expressionSource == null ? "" : expressionSource.trim();
        if (expression.startsWith(io.tesseraql.core.sql.AmbientBinds.DECISION + ".")
                && expression.split("\\.").length >= 2) {
            found.add(expression);
        }
    }

    /**
     * Lints shared validation rules (docs/validation-rule-sets.md): a rule nothing references
     * is either dead or a missed reference, and a route-local rule that says the same thing as
     * a shared one is the copy-paste rule sets exist to replace. Unknown references, bind
     * contracts on both sides, and duplicates already failed the load (TQL-FIELD-4604..4609).
     */
    private void lintRuleSets(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        io.tesseraql.yaml.rules.ValidationRuleSets sets = io.tesseraql.yaml.rules.ValidationRuleSets
                .load(appHome, new io.tesseraql.yaml.SimpleYamlParser());
        if (sets.isEmpty()) {
            return;
        }
        Set<String> referenced = new HashSet<>();
        for (Map.Entry<Path, RouteDefinition> document : authoringDocuments(manifest)) {
            String source = appHome.relativize(document.getKey()).toString();
            document.getValue().validate().forEach((id, rule) -> {
                if (rule.use() != null) {
                    referenced.add(rule.use());
                    return;
                }
                duplicateOf(rule, sets).ifPresent(shared -> findings.add(new LintFinding(
                        "TQL-FIELD-4613", "warning", source,
                        "Validation rule '" + id + "' repeats shared rule '" + shared
                                + "' — reference it with use: so the two cannot drift apart")));
            });
        }
        sets.rules().keySet().stream()
                .filter(name -> !referenced.contains(name))
                .forEach(name -> findings.add(new LintFinding("TQL-FIELD-4612", "warning",
                        "rules", "Rule '" + name + "' is declared but never referenced")));
    }

    /**
     * The shared rule a route-local one restates, if any. Only the rule's own substance counts —
     * expression text, or SQL file contents — because {@code field:}, {@code when:} and the
     * message are the reference's local wiring and differ legitimately between two uses of the
     * same rule.
     */
    private static java.util.Optional<String> duplicateOf(
            io.tesseraql.yaml.model.ValidationRule local,
            io.tesseraql.yaml.rules.ValidationRuleSets sets) {
        if (local.rule() == null || local.rule().isBlank()) {
            // Two SQL rules are the same rule when they name the same file, which the shared
            // declaration already expresses; comparing file *contents* would flag a route that
            // legitimately keeps its own copy of similar SQL.
            return java.util.Optional.empty();
        }
        String expression = local.rule().trim();
        return sets.rules().entrySet().stream()
                .filter(entry -> entry.getValue().rule() != null
                        && entry.getValue().rule().trim().equals(expression))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /**
     * Lints the Camel component policy (docs/component-guard.md): a config entry that tries to
     * re-allow a baseline-denied component is ignored by the guard — surfacing the attempt is
     * the difference between "I widened the posture" and reality.
     */
    private void lintComponentPolicy(AppConfig config, List<LintFinding> findings) {
        io.tesseraql.yaml.config.ComponentPolicy policy = io.tesseraql.yaml.config.ComponentPolicy
                .from(config);
        for (String name : policy.allowed()) {
            if (io.tesseraql.yaml.config.ComponentPolicy.BASELINE_DENIED.contains(name)) {
                findings.add(new LintFinding("TQL-SEC-4139", "warning", "config",
                        "tesseraql.camel.components.allowed lists '" + name + "', but the"
                                + " built-in baseline refuses it — the entry has no effect"));
            }
        }
    }

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
            lintAmbientPrincipal(appHome, route.source(), route.definition(), false, findings);
        }
        // A queue consumer runs off a message, not a request: there is no caller to authenticate,
        // so a principal.* bind in its SQL can only ever fail at runtime.
        for (RouteFile consumer : manifest.consumers()) {
            lintAmbientPrincipal(appHome, consumer.source(), consumer.definition(), true,
                    findings);
        }
        // An MCP tool threads the caller's bearer token, so it carries a principal exactly when
        // its own security block says it does.
        for (ToolFile tool : manifest.tools()) {
            lintAmbientPrincipal(appHome, tool.source(), tool.definition(), false, findings);
        }
    }

    private void lintAmbientPrincipal(Path appHome, Path file, RouteDefinition def,
            boolean neverAuthenticated, List<LintFinding> findings) {
        String source = appHome.relativize(file).toString().replace('\\', '/');
        boolean noPrincipal = neverAuthenticated
                || "webhook".equals(def.recipe())
                || def.security() == null
                || "public".equals(def.security().auth());
        if (noPrincipal) {
            for (String bind : principalBinds(file, def)) {
                findings.add(new LintFinding("TQL-SEC-4136", "error", source,
                        "Route '" + def.id() + "' binds '" + bind + "' but never carries an"
                                + " authenticated principal — the bind can only fail as an"
                                + " unbound parameter at runtime"));
            }
        }
        sqlParamMaps(def).forEach((where, params) -> params.forEach((bindName, expr) -> {
            if (expr != null && io.tesseraql.core.sql.AmbientBinds.isAmbient(expr)
                    && expr.startsWith("principal.")) {
                findings.add(new LintFinding("TQL-SEC-4137", "warning", source,
                        "Route '" + def.id() + "' " + where + " wires '" + bindName + ": "
                                + expr + "' — the ambient bind /* " + expr + " */ makes the"
                                + " wiring unnecessary"));
            }
        }));
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

    /** The distinct {@code principal.*} bind expressions across a document's parseable SQL files. */
    private Set<String> principalBinds(Path source, RouteDefinition def) {
        Set<String> found = new LinkedHashSet<>();
        Path dir = source.getParent();
        List<String> files = new ArrayList<>();
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

    /** The principal half of the ambient set; the framework owns the list, not this linter. */
    private static void addIfAmbientPrincipal(String expressionSource, Set<String> found) {
        String expression = expressionSource == null ? "" : expressionSource.trim();
        if (expression.startsWith("principal.")
                && io.tesseraql.core.sql.AmbientBinds.isAmbient(expression)) {
            found.add(expression);
        }
    }

    private static void collectPrincipalBinds(List<SqlNode> nodes, Set<String> found) {
        for (SqlNode node : nodes) {
            switch (node) {
                case SqlNode.Bind bind -> addIfAmbientPrincipal(bind.expressionSource(), found);
                case SqlNode.ListBind bind ->
                    addIfAmbientPrincipal(bind.expressionSource(), found);
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
        for (Map.Entry<Path, RouteDefinition> document : authoringDocuments(manifest)) {
            String source = appHome.relativize(document.getKey()).toString();
            document.getValue().input().forEach((name, field) -> {
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

    /**
     * Every document that can declare {@code input:} or {@code validate:} — web routes, queue
     * consumers, and MCP tools. Any check that answers "is this shared definition referenced?"
     * has to see all three, or resolving them everywhere just moves the bug: a domain used only
     * by a tool would be reported as unreferenced.
     */
    private static List<Map.Entry<Path, RouteDefinition>> authoringDocuments(
            AppManifest manifest) {
        List<Map.Entry<Path, RouteDefinition>> documents = new ArrayList<>();
        manifest.routes().forEach(r -> documents.add(Map.entry(r.source(), r.definition())));
        manifest.consumers().forEach(c -> documents.add(Map.entry(c.source(), c.definition())));
        manifest.tools().forEach(t -> documents.add(Map.entry(t.source(), t.definition())));
        return documents;
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

    /**
     * Lints the SAML service-provider config (roadmap Phase 26): an enabled SP without
     * {@code sp.acsUrl} silently turns off the SubjectConfirmation {@code Recipient} check — the
     * assertion is then accepted no matter which service provider it was addressed to, so an
     * assertion captured at another SP of the same IdP replays here. The URL stays optional
     * (IdP-initiated-only deployments have no ACS to advertise), so this is a warning and not an
     * error: exactly the {@code TQL-SEC-4065} stance for the analogous mTLS {@code trustBundle},
     * which is the asymmetry this closes. Reads raw config nodes — never resolving secrets.
     */
    private void lintSamlConfig(AppConfig config, List<LintFinding> findings) {
        if (!"true".equalsIgnoreCase(rawString(config, "tesseraql.saml.enabled"))) {
            return;
        }
        if (rawString(config, "tesseraql.saml.sp.acsUrl") == null) {
            findings.add(new LintFinding("TQL-SEC-4092", "warning", "config",
                    "SAML is enabled but declares no tesseraql.saml.sp.acsUrl; the assertion's"
                            + " SubjectConfirmation recipient is not checked, and neither the login"
                            + " route nor the SP metadata endpoint is published"));
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
                if (security != null && "api-key".equals(security.auth())) {
                    String source = appHome.relativize(route.source()).toString().replace('\\',
                            '/');
                    findings.add(new LintFinding("TQL-SEC-4044", "error", source,
                            "Route '" + route.definition().id() + "' declares auth: api-key but no"
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

    /** The type-qualified Subject Alternative Name matchers an mTLS client may declare. */
    private static final List<String> MTLS_SAN_KEYS = List.of("sanDns", "sanUri", "sanEmail",
            "sanIp");

    /**
     * Lints the mutual-TLS config (roadmap Phase 25): an {@code auth: mtls} route requires mTLS
     * config; the config must name the forwarded-certificate header and each client must declare
     * exactly one certificate matcher (subjectDn/sanDns/sanUri/sanEmail/sanIp/sha256). A missing
     * trustBundle is a warning —
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
            // The untyped san: matched a value against every kind of Subject Alternative Name at
            // once, so a certificate carrying it as an email or URI satisfied a matcher that meant
            // DNS. It is gone rather than deprecated: a config kept working while meaning something
            // weaker is the failure this replaces.
            if (client.get("san") != null) {
                findings.add(new LintFinding("TQL-SEC-4066", "error", "config",
                        "mTLS client '" + id + "' declares the removed untyped san:; name the kind"
                                + " with sanDns/sanUri/sanEmail/sanIp so a certificate's name of"
                                + " one kind cannot satisfy a matcher meaning another"));
            }
            int matchers = 0;
            if (client.get("subjectDn") != null) {
                matchers++;
            }
            for (String typed : MTLS_SAN_KEYS) {
                if (client.get(typed) != null) {
                    matchers++;
                }
            }
            if (client.get("sha256") != null) {
                matchers++;
            }
            if (matchers == 0) {
                findings.add(new LintFinding("TQL-SEC-4062", "error", "config",
                        "mTLS client '" + id + "' declares no certificate matcher; set exactly one"
                                + " of subjectDn/sanDns/sanUri/sanEmail/sanIp/sha256"));
            } else if (matchers > 1) {
                findings.add(new LintFinding("TQL-SEC-4063", "error", "config",
                        "mTLS client '" + id + "' declares more than one certificate matcher; set"
                                + " exactly one of subjectDn/sanDns/sanUri/sanEmail/sanIp/sha256"));
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
        definition.queries().forEach((name, query) -> {
            if (query.file() != null
                    && !Files.isRegularFile(tool.source().getParent().resolve(query.file()))) {
                findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                        "Query '" + name + "' references a missing SQL file: " + query.file()));
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
        // A tool's validate: runs through the same transactional pipeline a route's does.
        lintValidation(tool.source(), definition, source, findings);
        lintEmit(definition, source, findings);
        // emit: is a command-json route key. A tool may legally carry that recipe, so the route
        // check would pass it while the compiled tool pipeline broadcasts nothing — say so.
        if (!definition.emit().isEmpty()) {
            findings.add(new LintFinding("TQL-YAML-1038", "error", source,
                    "emit: has no effect on an MCP tool — the compiled tool pipeline does not"
                            + " broadcast live-view topics"));
        }
        lintDatasource(config, tool.source(), definition, source, findings);
        // An MCP tool's SQL is model-driven — its arguments come from an LLM — so it is the
        // highest-risk surface for embedded-variable injection, and it was the one not checked.
        lintEmbeddedVariables(tool.source(), definition, source, findings);
        // A tool writes with the same bindings a command route does; the write-safety
        // and isolation nudges apply to it identically (docs/silent-tolerance.md K-e).
        lintOptimisticLocking(tool.source(), definition, source, findings);
        lintTenantPredicate(config, tool.source(), definition, source, findings);
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
        AppConfig config = manifest.config();
        String defaultTag = java.util.Locale.forLanguageTag(
                config.getString("tesseraql.i18n.defaultLocale").orElse("en")).toLanguageTag();
        boolean hasCatalog = Files.isDirectory(appHome.resolve("messages"));
        io.tesseraql.yaml.i18n.MessageCatalog catalog;
        if (hasCatalog) {
            try {
                catalog = io.tesseraql.yaml.i18n.MessageCatalog.load(appHome.resolve("messages"));
            } catch (io.tesseraql.core.error.TqlException ex) {
                findings.add(new LintFinding("TQL-YAML-1007", "error", "messages",
                        ex.getMessage()));
                return;
            }
        } else {
            // No catalog files to check, but a declared message: key must still resolve — with no
            // messages/ every non-tql. key falls through to the raw key at runtime (the user sees
            // 'order.qty.tooLarge' as the error text), which the reference loop below now reports.
            catalog = io.tesseraql.yaml.i18n.MessageCatalog.empty();
            lintMessageKeyReferences(appHome, manifest, catalog, defaultTag, findings);
            return;
        }

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

        lintMessageKeyReferences(appHome, manifest, catalog, defaultTag, findings);
    }

    /**
     * Checks every declared {@code message:} key (validate rules, constraint mappings) resolves in
     * the catalog. Runs whether or not a {@code messages/} tree exists — an app with no catalog but
     * a {@code message:} reference otherwise gets no diagnostic and the raw key leaks to the user.
     */
    private void lintMessageKeyReferences(Path appHome, AppManifest manifest,
            io.tesseraql.yaml.i18n.MessageCatalog catalog, String defaultTag,
            List<LintFinding> findings) {
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
        lintUnknownTopLevelKeys(appHome, route.source(), RouteDefinition.class, Set.of(), findings);

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
        // A negative timeout on a step or named query was clamped to 0 = unlimited by the
        // compiler — the inverse of the author's intent — so the guard was missing here.
        definition.steps().forEach((name, step) -> {
            if (step.timeoutSeconds() != null && step.timeoutSeconds() < 0) {
                findings.add(new LintFinding("TQL-YAML-1021", "error", source,
                        "Step '" + name + "' timeoutSeconds must be >= 0"));
            }
        });
        definition.queries().forEach((name, query) -> {
            if (query.timeoutSeconds() != null && query.timeoutSeconds() < 0) {
                findings.add(new LintFinding("TQL-YAML-1021", "error", source,
                        "Query '" + name + "' timeoutSeconds must be >= 0"));
            }
        });
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
        definition.queries().forEach((name, query) -> {
            if (query.file() != null
                    && !Files.isRegularFile(route.source().getParent().resolve(query.file()))) {
                findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                        "Query '" + name + "' references a missing SQL file: " + query.file()));
            }
        });
        lintOptimisticLocking(route.source(), definition, source, findings);
        // Whether the recipe honors validate: at all is a route-level question; the rules'
        // shape is checked the same way wherever they are declared.
        if (!definition.validate().isEmpty() && definition.recipe() != null
                && !VALIDATING_RECIPES.contains(definition.recipe())) {
            findings.add(new LintFinding("TQL-YAML-1003", "error", source,
                    "validate: has no effect on '" + definition.recipe() + "' routes — it is"
                            + " honored on " + String.join(", ", VALIDATING_RECIPES)
                            + ", queue consumers, and MCP tools"));
        }
        lintValidation(route.source(), definition, source, findings);
        lintEmit(definition, source, findings);
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
        lintRouteExport(route, definition, source, findings);
        lintExportRowCap(definition.fileExport(), "", source, findings);
        lintDatasource(config, route.source(), definition, source, findings);
        lintEmbeddedVariables(route.source(), definition, source, findings);
        if (definition.security() != null && definition.security().policy() != null
                && !policyDefined(config, definition.security().policy())) {
            findings.add(new LintFinding("TQL-SEC-4030", "warning", source,
                    "Route references undefined policy '" + definition.security().policy()
                            + "' (deny by default)"));
        }
        if (definition.security() != null) {
            String csrf = definition.security().csrf();
            // The route-local csrf value gets the same enum check the config-side
            // security.defaults rules already enforce (TQL-SEC-4132) — a typo like `requred`
            // silently resolved to auto (no enforcement on a bearer route) before this.
            if (csrf != null && !"auto".equals(csrf) && !"required".equals(csrf)
                    && !"off".equals(csrf)) {
                findings.add(new LintFinding("TQL-SEC-4132", "error", source,
                        "Route '" + definition.id() + "' csrf must be auto, required or off, not '"
                                + csrf + "'"));
            }
        }
        lintInputPolicy(definition, source, findings);
        lintInputPolicy(definition, source, findings);
        lintTenantPredicate(config, route.source(), definition, source, findings);
    }

    /**
     * Validates the {@code inputPolicy:} value vocabulary (TQL-FIELD-2006). A value outside the
     * enum silently disabled the guard: {@code unknownFields: Reject} (or {@code rejct}, {@code
     * deny}) made {@code rejectsUnknownFields()} false and admitted every undeclared body field.
     * The schema types {@code inputPolicy} openly, so this is the only value check.
     */
    private void lintInputPolicy(RouteDefinition definition, String source,
            List<LintFinding> findings) {
        InputPolicy policy = definition.inputPolicy();
        if (policy == null) {
            return;
        }
        String unknown = policy.unknownFields();
        if (unknown != null && !"reject".equals(unknown) && !"ignore".equals(unknown)) {
            findings.add(new LintFinding("TQL-FIELD-2006", "error", source,
                    "Route '" + definition.id() + "' inputPolicy.unknownFields must be reject or "
                            + "ignore, not '" + unknown + "' (an unrecognized value silently "
                            + "disables the mass-assignment guard)"));
        }
        String readOnly = policy.readOnlyFieldBehavior();
        if (readOnly != null && !"reject".equals(readOnly) && !"ignore".equals(readOnly)
                && !"warn".equals(readOnly)) {
            findings.add(new LintFinding("TQL-FIELD-2006", "error", source,
                    "Route '" + definition.id() + "' inputPolicy.readOnlyFieldBehavior must be "
                            + "reject, ignore or warn, not '" + readOnly + "'"));
        }
    }

    /** A {@code {placeholder}} reference inside an embedded-variable template. */
    private static final Pattern EMBEDDED_PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    /**
     * An embedded variable ({@code /*# … {x} … *}{@code /}) interpolates its placeholder values into
     * the SQL text, not a {@code ?} bind, so a request-controlled value there is an injection vector
     * unless allowlisted. This requires every placeholder that resolves to a request input to be
     * {@code enum}-constrained (the runtime guard against meta-characters is only defense in depth).
     */
    private void lintEmbeddedVariables(Path documentSource, RouteDefinition definition,
            String source, List<LintFinding> findings) {
        SqlBinding sql = definition.sql();
        if (sql == null || sql.isContract() || sql.file() == null) {
            return;
        }
        Path sqlFile = documentSource.getParent().resolve(sql.file());
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
    private void lintTenantPredicate(AppConfig config, Path documentSource,
            RouteDefinition definition, String source, List<LintFinding> findings) {
        // getBoolean, not parseBoolean: `tenancy.enabled: yes` used to read as false here and
        // silently switch the whole tenant lint off (docs/silent-tolerance.md K-e).
        boolean enabled = config.getBoolean("tenancy.enabled", false);
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
        Path sqlFile = documentSource.getParent().resolve(definition.sql().file());
        if (Files.isRegularFile(sqlFile) && readQuietly(sqlFile).toLowerCase().contains("tenant")) {
            return;
        }
        findings.add(new LintFinding("TQL-TENANT-3001", "warning", source,
                "Shared-schema route '" + definition.id()
                        + "' has no tenant predicate; bind tenant.id or filter by a tenant column"));
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
    private void lintScopes(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        Set<String> declared = new HashSet<>();
        for (ScopeFile scope : manifest.scopes()) {
            lintScopeDefinition(appHome, scope, findings);
            lintUnknownTopLevelKeys(appHome, scope.source(), ScopeDefinition.class, Set.of(),
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
            String source = relative(appHome, job.source());
            for (String file : jobSqlFiles(job)) {
                Path sqlFile = dir.resolve(file);
                if (Files.isRegularFile(sqlFile)
                        && SCOPE_DIRECTIVE.matcher(readQuietly(sqlFile)).find()) {
                    findings.add(new LintFinding("TQL-SCOPE-3014", "error", source,
                            "batch job '" + job.definition().id() + "' uses a /*%scope … */"
                                    + " directive in " + file + ", but a job runs with no"
                                    + " principal to scope against — it would fail at execution"
                                    + " time (TQL-SQL-2106); filter with a job parameter instead"));
                }
            }
        }
    }

    /** Every SQL file a job references: its own binding, pipeline steps, and import row SQL. */
    private static List<String> jobSqlFiles(JobFile job) {
        List<String> files = new ArrayList<>();
        JobDefinition definition = job.definition();
        if (definition.sql() != null && definition.sql().file() != null) {
            files.add(definition.sql().file());
        }
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
        ImportSpec fileImport = definition.fileImport();
        if (fileImport != null && fileImport.sql() != null && fileImport.sql().file() != null) {
            files.add(fileImport.sql().file());
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
        String source = relative(appHome, file);
        String id = definition.id();
        for (Path sqlFile : routeSqlFiles(file, definition)) {
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

    /** The roots a transition guard may reference (roadmap Phase 28); {@code decision} covers
     * the transition's own {@code decide:} outputs (docs/decision-tables.md). */
    private static final Set<String> GUARD_ROOTS = Set.of("document", "task", "principal",
            "decision");

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

    private static final java.util.regex.Pattern DOC_TYPE_LITERAL = java.util.regex.Pattern
            .compile("\\bdoc_type\\s*(?:=|in\\s*\\()\\s*'([^']*)'",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern CURRENT_STATE_LITERAL = java.util.regex.Pattern
            .compile("\\bcurrent_state\\s*(?:=|in\\s*\\()\\s*'([^']*)'",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The managed-table literal lints (docs/workflow-expressiveness.md slice 4 and
     * docs/transition-engine.md track D): in SQL that references the managed
     * {@code tql_workflow_instance} table, a string literal compared to {@code doc_type}
     * must name a declared workflow {@code document.type} ({@code TQL-WORKFLOW-3114}) and
     * one compared to {@code current_state} must name a declared state
     * ({@code TQL-WORKFLOW-3115}) — either typo otherwise survives to runtime as an
     * always-empty join. When the file pins exactly one declared document type, its
     * {@code current_state} literals narrow to that workflow's states; otherwise the
     * union of all declared states applies. SQL that never mentions the managed table is
     * skipped, so an application's own columns stay out of scope.
     */
    private void lintDocTypeLiterals(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        Set<String> declared = new LinkedHashSet<>();
        Map<String, Set<String>> statesByType = new LinkedHashMap<>();
        Set<String> allStates = new LinkedHashSet<>();
        for (WorkflowFile workflow : manifest.workflows()) {
            WorkflowDefinition def = workflow.definition();
            if (def.document() == null || def.document().type() == null) {
                continue;
            }
            declared.add(def.document().type());
            Set<String> states = new LinkedHashSet<>();
            for (StateSpec state : def.states()) {
                if (state.id() != null) {
                    states.add(state.id());
                }
            }
            statesByType.merge(def.document().type(), states, (a, b) -> {
                a.addAll(b);
                return a;
            });
            allStates.addAll(states);
        }
        if (declared.isEmpty()) {
            return;
        }
        for (String root : List.of("web", "workflow", "rules", "scope")) {
            Path dir = appHome.resolve(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
                for (Path file : files
                        .filter(f -> f.getFileName().toString().endsWith(".sql")).toList()) {
                    String sql;
                    try {
                        sql = Files.readString(file);
                    } catch (java.io.IOException unreadable) {
                        continue;
                    }
                    if (!sql.contains("tql_workflow_instance")) {
                        continue;
                    }
                    Set<String> pinnedTypes = new LinkedHashSet<>();
                    java.util.regex.Matcher literals = DOC_TYPE_LITERAL.matcher(sql);
                    while (literals.find()) {
                        String literal = literals.group(1);
                        if (declared.contains(literal)) {
                            pinnedTypes.add(literal);
                        } else {
                            findings.add(new LintFinding("TQL-WORKFLOW-3114", "warning",
                                    relative(appHome, file),
                                    "doc_type literal '" + literal
                                            + "' names no declared workflow document type"
                                            + " (declared: " + declared + ")"));
                        }
                    }
                    // The narrowing: one pinned type means the file's states are that
                    // workflow's; anything else falls back to the union.
                    Set<String> states = pinnedTypes.size() == 1
                            ? statesByType.get(pinnedTypes.iterator().next())
                            : allStates;
                    java.util.regex.Matcher stateLiterals = CURRENT_STATE_LITERAL.matcher(sql);
                    while (stateLiterals.find()) {
                        String literal = stateLiterals.group(1);
                        if (!states.contains(literal)) {
                            findings.add(new LintFinding("TQL-WORKFLOW-3115", "warning",
                                    relative(appHome, file),
                                    "current_state literal '" + literal
                                            + "' names no declared workflow state"
                                            + (pinnedTypes.size() == 1
                                                    ? " of document type '"
                                                            + pinnedTypes.iterator().next()
                                                            + "'"
                                                    : "")
                                            + " (declared: " + states + ")"));
                        }
                    }
                }
            } catch (java.io.IOException unreadable) {
                // An unwalkable tree is its own problem; the lint stays quiet.
            }
        }
    }

    private void lintWorkflow(Path appHome, AppConfig config, WorkflowFile workflow,
            List<LintFinding> findings) {
        String source = relative(appHome, workflow.source());
        lintUnknownTopLevelKeys(appHome, workflow.source(), WorkflowDefinition.class, Set.of(),
                findings);
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
            lintGuard(t.guard(), dir, where, source, findings);
            lintStamp(t, where, source, findings);
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
        // One-action dispatches (docs/workflow-expressiveness.md slice 3): every member
        // exists and starts from one shared state (3112); a member without a guard that is
        // not last makes its followers unreachable (3113).
        for (io.tesseraql.yaml.model.DispatchSpec dispatch : def.dispatch()) {
            String where = "workflow '" + id + "' dispatch '" + dispatch.id() + "'";
            if (dispatch.id() != null && def.transitions().stream()
                    .anyMatch(t -> dispatch.id().equals(t.id()))) {
                findings.add(new LintFinding("TQL-WORKFLOW-3112", "error", source,
                        where + " collides with a transition of the same id"));
            }
            if (dispatch.oneOf().size() < 2) {
                findings.add(new LintFinding("TQL-WORKFLOW-3112", "error", source,
                        where + " needs at least two member transitions"));
            }
            String sharedFrom = null;
            io.tesseraql.yaml.model.SecuritySpec sharedSecurity = null;
            boolean securitySeen = false;
            for (int i = 0; i < dispatch.oneOf().size(); i++) {
                String member = dispatch.oneOf().get(i);
                io.tesseraql.yaml.model.TransitionSpec found = def.transitions().stream()
                        .filter(t -> member.equals(t.id())).findFirst().orElse(null);
                if (found == null) {
                    findings.add(new LintFinding("TQL-WORKFLOW-3112", "error", source,
                            where + " names unknown transition '" + member + "'"));
                    continue;
                }
                if (sharedFrom == null) {
                    sharedFrom = found.from();
                } else if (!sharedFrom.equals(found.from())) {
                    findings.add(new LintFinding("TQL-WORKFLOW-3112", "error", source,
                            where + " members start from different states ('" + sharedFrom
                                    + "' vs '" + found.from() + "')"));
                }
                // A dispatch is one action, one audience: every member must carry the
                // same effective security spec — the selector has none of its own, each
                // attempt enforces its member's.
                io.tesseraql.yaml.model.SecuritySpec effective = found.security() != null
                        ? found.security()
                        : def.security();
                if (!securitySeen) {
                    sharedSecurity = effective;
                    securitySeen = true;
                } else if (!java.util.Objects.equals(sharedSecurity, effective)) {
                    findings.add(new LintFinding("TQL-WORKFLOW-3112", "error", source,
                            where + " members carry different security specs"));
                }
                if (found.guard() == null && i < dispatch.oneOf().size() - 1) {
                    findings.add(new LintFinding("TQL-WORKFLOW-3113", "warning", source,
                            where + " member '" + member + "' has no guard and is not last -"
                                    + " the members after it are unreachable"));
                }
                // One name, one evaluation (docs/transition-engine.md track B): a member
                // alias shadowing a dispatch-level alias could only confuse.
                for (String alias : dispatch.decide().keySet()) {
                    if (found.decide().containsKey(alias)) {
                        findings.add(new LintFinding("TQL-WORKFLOW-3112", "error", source,
                                where + " decide alias '" + alias + "' collides with member '"
                                        + member + "' declaring its own '" + alias + "'"));
                    }
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
                if (onBreach.reassign() != null && onBreach.reassign().file() != null
                        && !Files.isRegularFile(dir.resolve(onBreach.reassign().file()))) {
                    findings.add(new LintFinding("TQL-WORKFLOW-3104", "error", source, where
                            + " references missing reassign file '"
                            + onBreach.reassign().file() + "'"));
                }
            }
        }

        lintWorkflowMode(def, config, source, findings);
    }

    /**
     * Lints a transition's decision stamps (docs/workflow-expressiveness.md slice 2): a
     * column must be a plain identifier — the only string reaching an UPDATE's column
     * position — and a {@code decision.*} value must name a declared {@code decide:} alias
     * ({@code TQL-WORKFLOW-3111}). A dotted string outside the whitelist roots is stamped
     * as a literal; the common context roots get a warning so a typo is not silent.
     */
    private void lintStamp(io.tesseraql.yaml.model.TransitionSpec transition, String where,
            String source, List<LintFinding> findings) {
        transition.stamp().forEach((column, value) -> {
            if (!io.tesseraql.core.sql.SqlIdentifiers.isIdentifier(column)) {
                findings.add(new LintFinding("TQL-WORKFLOW-3111", "error", source,
                        where + " stamp column '" + column + "' is not a plain identifier"));
            }
            if (value instanceof String path) {
                if (path.startsWith("decision.")) {
                    String alias = path.split("\\.").length > 1 ? path.split("\\.")[1] : "";
                    if (!transition.decide().containsKey(alias)) {
                        findings.add(new LintFinding("TQL-WORKFLOW-3111", "error", source,
                                where + " stamps '" + path + "' but declares no decide: entry '"
                                        + alias + "'"));
                    }
                } else if (path.matches("(task|params|body|query|path|audit)\\..+")) {
                    findings.add(new LintFinding("TQL-WORKFLOW-3111", "warning", source,
                            where + " stamp value '" + path + "' is outside the "
                                    + "decision/document/principal whitelist and will be "
                                    + "stamped as a literal string"));
                }
            }
        });
    }

    /**
     * Lints a guard in either form (docs/workflow-expressiveness.md): the expression form
     * parses and reads only allowed roots ({@code TQL-WORKFLOW-3103}); the SQL form names
     * exactly one of expression/file ({@code 3108}), the file exists ({@code 3104}), and the
     * file is a query — a guard must never write ({@code 3109}).
     */
    private void lintGuard(io.tesseraql.yaml.model.GuardSpec guard, Path dir, String where,
            String source, List<LintFinding> findings) {
        if (guard == null) {
            return;
        }
        boolean hasExpression = guard.expression() != null && !guard.expression().isBlank();
        boolean hasFile = guard.file() != null && !guard.file().isBlank();
        if (hasExpression == hasFile) {
            findings.add(new LintFinding("TQL-WORKFLOW-3108", "error", source,
                    where + " guard must declare exactly one of an expression or a file"));
            return;
        }
        if (hasFile) {
            Path file = dir.resolve(guard.file());
            if (!Files.isRegularFile(file)) {
                findings.add(new LintFinding("TQL-WORKFLOW-3104", "error", source,
                        where + " references missing guard file '" + guard.file() + "'"));
                return;
            }
            String sql;
            try {
                sql = Files.readString(file);
            } catch (java.io.IOException unreadable) {
                findings.add(new LintFinding("TQL-WORKFLOW-3104", "error", source,
                        where + " guard file '" + guard.file() + "' is unreadable: "
                                + unreadable.getMessage()));
                return;
            }
            String head = sql.replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("(?m)^\\s*--.*$", " ").strip()
                    .toLowerCase(java.util.Locale.ROOT);
            if (!head.startsWith("select") && !head.startsWith("with")) {
                findings.add(new LintFinding("TQL-WORKFLOW-3109", "error", source,
                        where + " guard file '" + guard.file()
                                + "' must be a query - a guard never writes"));
            }
            return;
        }
        Expr expr;
        try {
            expr = ExpressionParser.parse(guard.expression());
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
                                + "'; allowed roots are document, task, principal, decision"));
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

    private static List<Path> routeSqlFiles(RouteFile route) {
        return routeSqlFiles(route.source(), route.definition());
    }

    /** The non-contract SQL files a document references ({@code sql}, {@code steps}, {@code queries}). */
    private static List<Path> routeSqlFiles(Path source, RouteDefinition definition) {
        Path dir = source.getParent();
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

    /** The write recipes an optimistic-locking nudge applies to, whatever surface mounts them. */
    private static final java.util.Set<String> WRITE_RECIPES = java.util.Set.of("command-json",
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
    private void lintOptimisticLocking(Path documentSource, RouteDefinition definition,
            String source, List<LintFinding> findings) {
        if (!WRITE_RECIPES.contains(definition.recipe())) {
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
            Path file = documentSource.getParent().resolve(binding.file());
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
    private void lintValidation(Path file, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        if (definition.validate().isEmpty()) {
            return;
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
            Path sqlFile = file.getParent().resolve(rule.file());
            if (!Files.isRegularFile(sqlFile)) {
                findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                        "Validation rule '" + id + "' references a missing SQL file: "
                                + rule.file()));
            } else if (!io.tesseraql.core.validation.ValidationRules
                    .isSelect(readQuietly(sqlFile))) {
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
        lintUnknownTopLevelKeys(appHome, consumer.source(), RouteDefinition.class, Set.of(),
                findings);
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
        // A consumer's validate: is compiled and run exactly like a command's, so its rules get
        // the same static checks — a typo'd validation SQL filename used to reach startup.
        lintValidation(consumer.source(), definition, source, findings);
        lintEmit(definition, source, findings);
        lintPublish(config, definition, source, findings);
        lintNotify(config, definition, source, findings);
        lintDatasource(config, consumer.source(), definition, source, findings);
        // A consumer's SQL is fed by an external message payload — as untrusted as an HTTP body —
        // so the embedded-variable injection guard (TQL-SQL-2109) applies here, not just on routes.
        lintEmbeddedVariables(consumer.source(), definition, source, findings);
        lintOptimisticLocking(consumer.source(), definition, source, findings);
        lintTenantPredicate(config, consumer.source(), definition, source, findings);
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
            if (!io.tesseraql.core.sql.SqlIdentifiers.isIdentifier(schema)
                    || !io.tesseraql.core.sql.SqlIdentifiers.isIdentifier(alias)
                    || "main".equals(alias)) {
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
            if (!io.tesseraql.core.sql.SqlIdentifiers.isIdentifier(alias)
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
     * exactly one of {@code sql:}, {@code notify:}, or {@code httpCall:}; notify steps lint like a
     * route's, and httpCall steps lint their egress against the allow-list (deny by default).
     */
    private void lintJob(Path appHome, AppConfig config, io.tesseraql.yaml.manifest.JobFile job,
            io.tesseraql.yaml.calendar.Calendars calendars, List<LintFinding> findings) {
        String source = appHome.relativize(job.source()).toString().replace('\\', '/');
        lintUnknownTopLevelKeys(appHome, job.source(), JobDefinition.class, Set.of(), findings);
        if (job.definition().trigger() != null && job.definition().trigger().poll() != null) {
            lintPollJob(config, job, source, findings);
        }
        if (job.definition().trigger() != null && job.definition().trigger().schedule() != null) {
            lintScheduleCalendar(job, job.definition().trigger().schedule(), calendars, source,
                    findings);
        }
        lintOverlapAndSla(job, source, findings);
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
            if (step.chunk() != null) {
                declared++;
            }
            if (step.export() != null) {
                declared++;
            }
            if (step.push() != null) {
                declared++;
            }
            if (declared != 1) {
                findings.add(new LintFinding("TQL-FIELD-2004", "error", source, "Step '"
                        + step.id() + "' must declare exactly one of sql:, notify:, httpCall:,"
                        + " chunk:, export:, or push:"));
                continue;
            }
            if (step.notification() != null) {
                lintNotifySpec(config, step.id(), step.notification(), source, findings);
            } else if (step.httpCall() != null) {
                lintHttpCall(config, step.id(), step.httpCall(), source, findings);
            } else if (step.chunk() != null) {
                lintChunk(job, step, source, findings);
            } else if (step.export() != null) {
                lintExportStep(job, step, source, findings);
            } else if (step.push() != null) {
                lintPushStep(config, step, source, findings);
            }
        }
    }

    /**
     * Statically checks a push step (docs/analytics-experience.md): the transfer reference and
     * target are required, a remote target needs its host and credential, and the delivered
     * name stays a bare filename — separators or placeholder-shaped values would let a YAML
     * scalar steer the write ({@code TQL-YAML-1042}). Host allow-listing stays a runtime
     * refusal ({@code TQL-SEC-4141}): the allow-list is deployment config another environment
     * may declare differently.
     */
    private void lintPushStep(AppConfig config, io.tesseraql.yaml.model.PipelineStep step,
            String source, List<LintFinding> findings) {
        io.tesseraql.yaml.model.PushSpec push = step.push();
        if (push.file() == null || push.file().isBlank()) {
            findings.add(new LintFinding("TQL-YAML-1042", "error", source, "Step '" + step.id()
                    + "': push needs file: (a context path resolving to a transfer id, e.g."
                    + " step.report.transferId)"));
        }
        String transport = push.effectiveTransport();
        if (!"local".equals(transport) && !"sftp".equals(transport) && !"ftps".equals(transport)) {
            findings.add(new LintFinding("TQL-YAML-1042", "error", source, "Step '" + step.id()
                    + "': push transport: must be local, sftp, or ftps"));
            return;
        }
        if (push.path() == null || push.path().isBlank()) {
            findings.add(new LintFinding("TQL-YAML-1042", "error", source, "Step '" + step.id()
                    + "': push needs path: (the directory to deliver into)"));
        }
        if (push.isRemote()) {
            if (push.host() == null || push.host().isBlank()) {
                findings.add(new LintFinding("TQL-YAML-1042", "error", source, "Step '"
                        + step.id() + "': a remote push target needs host:"));
            }
            if (push.credential() == null || push.credential().isBlank()) {
                findings.add(new LintFinding("TQL-YAML-1042", "error", source, "Step '"
                        + step.id() + "': a remote push target needs credential: (declared"
                        + " under tesseraql.connectors.push.credentials)"));
            } else if (config.navigate("tesseraql.connectors.push.credentials."
                    + push.credential()) == null) {
                // A warning, not an error: another environment's config may declare it.
                findings.add(new LintFinding("TQL-YAML-1102", "warning", source, "Step '"
                        + step.id() + "' references undeclared push credential '"
                        + push.credential() + "'"));
            }
        }
        if (push.as() != null && (push.as().contains("/") || push.as().contains("\\")
                || push.as().contains("..") || push.as().contains("${"))) {
            findings.add(new LintFinding("TQL-YAML-1042", "error", source, "Step '" + step.id()
                    + "': push as: must be a plain file name ({dotted.path} placeholders"
                    + " resolve against the job context)"));
        }
        // The poll side's server-identity nudges, mirrored (docs/connectors.md): an SFTP
        // target without host-key pinning is a warning, an FTPS target without a trust
        // store is an error — the runtime refuses it anyway, so the build says it first.
        if ("sftp".equals(transport)
                && config.getString("tesseraql.connectors.push.knownHostsFile")
                        .filter(value -> !value.isBlank()).isEmpty()) {
            findings.add(new LintFinding("TQL-SEC-4084", "warning", source, "Step '" + step.id()
                    + "': sftp push without tesseraql.connectors.push.knownHostsFile — the"
                    + " server's host key is not verified"));
        }
        if ("ftps".equals(transport)
                && config.navigate("tesseraql.connectors.push.trustStore") == null) {
            findings.add(new LintFinding("TQL-SEC-4085", "error", source, "Step '" + step.id()
                    + "': ftps push needs tesseraql.connectors.push.trustStore — without it"
                    + " the server certificate is not verified and TLS proves nothing about"
                    + " the peer"));
        }
    }

    /**
     * Statically checks an export step (docs/analytics-experience.md track 3): the extraction
     * query is required, the step runs on the job's datasource (the {@code TQL-YAML-1037}
     * stance — a pipeline step cannot pick its own connector), and {@code after.timing:
     * download} stays route vocabulary — a job-produced file's download is an ops action, not
     * a business signal, so the only follow-up a step supports is the extraction-transaction
     * one ({@code TQL-YAML-1041}).
     */
    private void lintExportStep(io.tesseraql.yaml.manifest.JobFile job,
            io.tesseraql.yaml.model.PipelineStep step, String source,
            List<LintFinding> findings) {
        io.tesseraql.yaml.model.ExportSpec export = step.export();
        if (export.sql() == null || export.sql().file() == null
                || export.sql().file().isBlank()) {
            findings.add(new LintFinding("TQL-YAML-1041", "error", source, "Step '" + step.id()
                    + "': export needs sql: { file: … } (the extraction query)"));
            return;
        }
        if (export.sql().datasource() != null) {
            findings.add(new LintFinding("TQL-YAML-1037", "error", source, "Step '" + step.id()
                    + "': export.sql cannot declare datasource: — a pipeline step runs on the"
                    + " job's datasource"));
        }
        if (export.format() == null || export.format().isBlank()) {
            findings.add(new LintFinding("TQL-YAML-1041", "error", source, "Step '" + step.id()
                    + "': export needs format: (csv, excel, or pdf)"));
        }
        if (export.after() != null && io.tesseraql.core.files.FileTransferService.AFTER_DOWNLOAD
                .equals(export.after().effectiveTiming())) {
            findings.add(new LintFinding("TQL-YAML-1041", "error", source, "Step '" + step.id()
                    + "': after.timing: download is route vocabulary — an export step supports"
                    + " timing: extract only"));
        }
        if ("pdf".equals(export.format())) {
            if (export.sheet() != null || export.startCell() != null) {
                findings.add(new LintFinding("TQL-YAML-1005", "error", source,
                        "pdf export: sheet:/startCell: are workbook options - a pdf lays out"
                                + " through its template, not cell placement"));
            }
            if (export.template() != null && !export.template().endsWith(".html")) {
                findings.add(new LintFinding("TQL-YAML-1006", "error", source,
                        "pdf export template '" + export.template()
                                + "' must be an .html file (it renders through the template"
                                + " engine before PDF conversion)"));
            }
        }
        if (!"pdf".equals(export.format()) && export.startCell() != null
                && export.template() == null) {
            findings.add(new LintFinding("TQL-YAML-1041", "error", source, "Step '" + step.id()
                    + "': startCell: places data into a template, but none is declared - add"
                    + " template:, or drop startCell: for a plain grid"));
        }
        if (export.template() != null && (!"pdf".equals(export.format())
                || export.template().endsWith(".html"))
                && !Files.isRegularFile(
                        job.source().getParent().resolve(export.template()))) {
            findings.add(new LintFinding("TQL-YAML-1006", "error", source, "Step '" + step.id()
                    + "': export references a missing template: " + export.template()));
        }
        lintExportRowCap(export, "Step '" + step.id() + "': ", source, findings);
    }

    /**
     * Statically checks a chunk step (docs/batch-platform.md track C). The restart contract
     * lives in the reader's SQL, so the reader is read here: without an {@code order by} the
     * resume point is undefined ({@code TQL-BATCH-4207}, an error), and a reader that never
     * binds {@code chunk.after} reprocesses from the top on every restart — legal for an
     * idempotent writer, worth saying out loud ({@code TQL-BATCH-4208}, a warning).
     */
    private void lintChunk(io.tesseraql.yaml.manifest.JobFile job,
            io.tesseraql.yaml.model.PipelineStep step, String source,
            List<LintFinding> findings) {
        io.tesseraql.yaml.model.ChunkSpec chunk = step.chunk();
        if (chunk.reader() == null || chunk.reader().file() == null
                || chunk.reader().file().isBlank()
                || chunk.writer() == null || chunk.writer().file() == null
                || chunk.writer().file().isBlank()) {
            findings.add(new LintFinding("TQL-BATCH-4206", "error", source, "Step '" + step.id()
                    + "': chunk needs reader: { file: … } and writer: { file: … }"));
            return;
        }
        if (chunk.commitEvery() != null && chunk.commitEvery() < 1) {
            findings.add(new LintFinding("TQL-BATCH-4206", "error", source, "Step '" + step.id()
                    + "': chunk commitEvery must be at least 1 (was " + chunk.commitEvery()
                    + ")"));
        }
        if (chunk.onError() != null && !List.of("fail", "skip").contains(chunk.onError())) {
            findings.add(new LintFinding("TQL-BATCH-4206", "error", source, "Step '" + step.id()
                    + "': chunk onError must be fail or skip (was '" + chunk.onError() + "')"));
        }
        if (chunk.skipLimit() != null && chunk.skipLimit() < 0) {
            findings.add(new LintFinding("TQL-BATCH-4206", "error", source, "Step '" + step.id()
                    + "': chunk skipLimit must not be negative (was " + chunk.skipLimit()
                    + ")"));
        }
        Path readerPath = job.source().getParent().resolve(chunk.reader().file()).normalize();
        if (!java.nio.file.Files.isRegularFile(readerPath)) {
            return; // the missing file is its own finding where SQL files are checked
        }
        String readerSql;
        try {
            readerSql = java.nio.file.Files.readString(readerPath);
        } catch (java.io.IOException unreadable) {
            return;
        }
        String lower = readerSql.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("order by")) {
            findings.add(new LintFinding("TQL-BATCH-4207", "error", source, "Step '" + step.id()
                    + "': the chunk reader has no order by — without a deterministic order"
                    + " the checkpoint cannot say where to resume"));
        }
        if (!readerSql.contains("chunk.after")) {
            findings.add(new LintFinding("TQL-BATCH-4208", "warning", source, "Step '"
                    + step.id() + "': the chunk reader never binds chunk.after — a restart"
                    + " reprocesses from the top, which is only safe for an idempotent"
                    + " writer"));
        }
    }

    /**
     * Statically checks {@code trigger: after:} chaining (docs/batch-platform.md track D): the
     * named job must exist ({@code TQL-BATCH-4209}), a chain must not loop (the runtime's
     * fired-set would silently drop the repeat — the declaration is the mistake), and a
     * trigger declares one kind: {@code after:} does not combine with a schedule or a poll.
     */
    private void lintJobChaining(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        Map<String, String> parents = new java.util.LinkedHashMap<>();
        java.util.Set<String> jobIds = new java.util.LinkedHashSet<>();
        manifest.jobs().forEach(job -> jobIds.add(job.definition().id()));
        for (io.tesseraql.yaml.manifest.JobFile job : manifest.jobs()) {
            io.tesseraql.yaml.model.TriggerSpec trigger = job.definition().trigger();
            if (trigger == null || trigger.after() == null || trigger.after().isBlank()) {
                continue;
            }
            String source = appHome.relativize(job.source()).toString().replace('\\', '/');
            String jobId = job.definition().id();
            if (trigger.schedule() != null || trigger.poll() != null) {
                findings.add(new LintFinding("TQL-YAML-1005", "error", source,
                        "Job '" + jobId + "' declares after: together with another trigger"
                                + " kind; declare one"));
            }
            if (!jobIds.contains(trigger.after())) {
                findings.add(new LintFinding("TQL-BATCH-4209", "error", source,
                        "Job '" + jobId + "' chains after unknown job '" + trigger.after()
                                + "' — it would never fire"));
                continue;
            }
            parents.put(jobId, trigger.after());
        }
        for (io.tesseraql.yaml.manifest.JobFile job : manifest.jobs()) {
            String jobId = job.definition().id();
            java.util.Set<String> walked = new java.util.LinkedHashSet<>();
            String current = jobId;
            while (parents.containsKey(current) && walked.add(current)) {
                current = parents.get(current);
            }
            if (parents.containsKey(current) && current.equals(jobId)) {
                findings.add(new LintFinding("TQL-BATCH-4209", "error",
                        appHome.relativize(job.source()).toString().replace('\\', '/'),
                        "Job '" + jobId + "' is part of an after: cycle (" + walked
                                + ") — a chain must end"));
            }
        }
    }

    /**
     * Statically checks {@code overlap:} and {@code sla:} (docs/batch-platform.md track E):
     * both are operational promises evaluated long after the deploy, so a value that cannot
     * mean anything — an unknown overlap policy, a deadline that does not parse — must be a
     * build error, not a sweep that silently never fires ({@code TQL-BATCH-4210}).
     */
    private void lintOverlapAndSla(io.tesseraql.yaml.manifest.JobFile job, String source,
            List<LintFinding> findings) {
        String jobId = job.definition().id();
        String overlap = job.definition().overlap();
        if (overlap != null && !List.of("concurrent", "skip").contains(overlap)) {
            findings.add(new LintFinding("TQL-BATCH-4210", "error", source, "Job '" + jobId
                    + "' overlap '" + overlap + "' is not one of concurrent, skip"));
        }
        io.tesseraql.yaml.model.SlaSpec sla = job.definition().sla();
        if (sla == null) {
            return;
        }
        if ((sla.completeBy() == null || sla.completeBy().isBlank())
                && (sla.runningLongerThan() == null || sla.runningLongerThan().isBlank())) {
            findings.add(new LintFinding("TQL-BATCH-4210", "error", source, "Job '" + jobId
                    + "' declares sla: without completeBy: or runningLongerThan:"));
        }
        if (sla.completeBy() != null && !sla.completeBy().isBlank()) {
            try {
                java.time.LocalTime.parse(sla.completeBy());
            } catch (java.time.format.DateTimeParseException ex) {
                findings.add(new LintFinding("TQL-BATCH-4210", "error", source, "Job '" + jobId
                        + "' sla completeBy '" + sla.completeBy()
                        + "' is not a wall-clock time (HH:mm)"));
            }
        }
        if (sla.runningLongerThan() != null && !sla.runningLongerThan().isBlank()) {
            try {
                io.tesseraql.core.util.Durations.toMillis(sla.runningLongerThan());
            } catch (RuntimeException ex) {
                findings.add(new LintFinding("TQL-BATCH-4210", "error", source, "Job '" + jobId
                        + "' sla runningLongerThan '" + sla.runningLongerThan()
                        + "' is not a duration (e.g. 2h, 30m)"));
            }
        }
    }

    /**
     * Loads the app's business-day calendars for reference checking (docs/batch-platform.md
     * track B). A structurally broken calendars/ dir — duplicate names, bad dates, unknown day
     * names — surfaces as an error finding instead of aborting the whole lint, and a calendar
     * declaring both {@code dates:} and {@code source:} gets {@code TQL-BATCH-4203}: holiday
     * rows have exactly one home.
     */
    private io.tesseraql.yaml.calendar.Calendars lintCalendars(Path appHome,
            List<LintFinding> findings) {
        io.tesseraql.yaml.calendar.Calendars calendars;
        try {
            calendars = io.tesseraql.yaml.calendar.Calendars.load(appHome,
                    new io.tesseraql.yaml.SimpleYamlParser());
        } catch (io.tesseraql.core.error.TqlException ex) {
            findings.add(new LintFinding(ex.code().toString(), "error", "calendars/",
                    ex.getMessage()));
            return io.tesseraql.yaml.calendar.Calendars.empty();
        }
        calendars.calendars().forEach((name, calendar) -> {
            if (calendar.holidays() != null && !calendar.holidays().dates().isEmpty()
                    && calendar.holidays().source() != null) {
                findings.add(new LintFinding("TQL-BATCH-4203", "error",
                        calendars.sourceOf(name), "Calendar '" + name
                                + "' declares both dates: and source: — holiday rows have"
                                + " exactly one home"));
            }
        });
        return calendars;
    }

    /**
     * Statically checks a schedule's calendar qualifiers (docs/batch-platform.md track B): the
     * named calendar must exist ({@code TQL-BATCH-4201}) and {@code runOn:} must ride on a
     * {@code calendar:} with a known value ({@code TQL-BATCH-4202}) — at fire time both fail
     * open, so the place to hear about a typo is the build.
     */
    private void lintScheduleCalendar(io.tesseraql.yaml.manifest.JobFile job,
            io.tesseraql.yaml.model.TriggerSpec.Schedule schedule,
            io.tesseraql.yaml.calendar.Calendars calendars, String source,
            List<LintFinding> findings) {
        String jobId = job.definition().id();
        boolean hasCalendar = schedule.calendar() != null && !schedule.calendar().isBlank();
        if (hasCalendar && !calendars.calendars().containsKey(schedule.calendar())) {
            findings.add(new LintFinding("TQL-BATCH-4201", "error", source,
                    "Job '" + jobId + "' schedule names unknown calendar '"
                            + schedule.calendar()
                            + "' — declare it under calendars/ or fix the reference"));
        }
        if (schedule.runOn() != null) {
            if (!hasCalendar) {
                findings.add(new LintFinding("TQL-BATCH-4202", "error", source,
                        "Job '" + jobId + "' schedule declares runOn: without calendar: —"
                                + " runOn qualifies a business-day calendar"));
            }
            if (!io.tesseraql.yaml.calendar.Calendars.RUN_ON.contains(schedule.runOn())) {
                findings.add(new LintFinding("TQL-BATCH-4202", "error", source,
                        "Job '" + jobId + "' schedule runOn '" + schedule.runOn()
                                + "' is not one of "
                                + new java.util.TreeSet<>(
                                        io.tesseraql.yaml.calendar.Calendars.RUN_ON)));
            }
        }
        if (schedule.dayOfMonth() != null) {
            if (!hasCalendar) {
                findings.add(new LintFinding("TQL-BATCH-4202", "error", source,
                        "Job '" + jobId + "' schedule declares dayOfMonth: without calendar:"
                                + " — the shift needs a business-day calendar"));
            }
            if (schedule.dayOfMonth() < 1 || schedule.dayOfMonth() > 31) {
                findings.add(new LintFinding("TQL-BATCH-4202", "error", source,
                        "Job '" + jobId + "' schedule dayOfMonth " + schedule.dayOfMonth()
                                + " is outside 1-31"));
            }
            if (schedule.runOn() != null) {
                findings.add(new LintFinding("TQL-BATCH-4202", "error", source,
                        "Job '" + jobId + "' schedule declares both runOn: and dayOfMonth: —"
                                + " one qualifier decides which firings count"));
            }
        }
        if (schedule.shift() != null) {
            if (schedule.dayOfMonth() == null) {
                findings.add(new LintFinding("TQL-BATCH-4202", "error", source,
                        "Job '" + jobId + "' schedule declares shift: without dayOfMonth: —"
                                + " a shift moves a nominal day"));
            }
            if (!io.tesseraql.yaml.calendar.Calendars.SHIFTS.contains(schedule.shift())) {
                findings.add(new LintFinding("TQL-BATCH-4202", "error", source,
                        "Job '" + jobId + "' schedule shift '" + schedule.shift()
                                + "' is not one of "
                                + new java.util.TreeSet<>(
                                        io.tesseraql.yaml.calendar.Calendars.SHIFTS)));
            }
        }
    }

    /**
     * Statically checks a {@code poll:}-triggered file-import job (roadmap Phase 26): the source is
     * a known kind with a path, a remote source has an allow-listed host
     * ({@code TQL-SEC-4080}, deny by default) and a configured credential ({@code TQL-SEC-4081}, a
     * warning), an SFTP source should verify the server's host key against
     * {@code tesseraql.connectors.poll.knownHostsFile} ({@code TQL-SEC-4084}, a warning), an FTPS
     * source must verify the server certificate against
     * {@code tesseraql.connectors.poll.trustStore} ({@code TQL-SEC-4085}, an error — unlike SSH
     * host keys there is no first-use posture to preserve), and the job carries an
     * {@code import:} block whose per-row SQL file exists.
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
        String kind = poll.effectiveTransport();
        if (!List.of("local", "sftp", "ftps").contains(kind)) {
            findings.add(new LintFinding("TQL-YAML-1005", "error", source,
                    "Poll trigger transport must be local, sftp, or ftps (was '"
                            + poll.transport() + "')"));
        }
        if (poll.path() == null || poll.path().isBlank()) {
            findings.add(new LintFinding("TQL-YAML-1005", "error", source,
                    "Poll trigger needs a path: (the directory to poll)"));
        }
        // Values that reach the endpoint URI. delay throws inside wire() where the failure is
        // logged and the job dropped, so the app boots healthy with a route that never runs;
        // port fails at connect. Both are better answered here.
        if (poll.delay() != null && !poll.delay().isBlank()) {
            try {
                io.tesseraql.core.util.Durations.toMillis(poll.delay());
            } catch (RuntimeException ex) {
                findings.add(new LintFinding("TQL-YAML-1005", "error", source,
                        "Poll trigger delay '" + poll.delay() + "' is not a duration — the job"
                                + " would be dropped at startup, leaving the app healthy with"
                                + " nothing arriving"));
            }
        }
        if (poll.port() != null && (poll.port() < 1 || poll.port() > 65535)) {
            findings.add(new LintFinding("TQL-YAML-1005", "error", source,
                    "Poll trigger port " + poll.port() + " is outside 1-65535"));
        }
        if (!poll.isRemote()) {
            // Keys that belong to a remote source parse cleanly and are then discarded, so an
            // author converting a job between kinds gets no signal that they now mean nothing.
            if (poll.host() != null && !poll.host().isBlank()) {
                findings.add(new LintFinding("TQL-YAML-1005", "warning", source,
                        "Poll trigger source '" + kind + "' ignores host: — remove it or use a"
                                + " remote source"));
            }
            if (poll.credential() != null && !poll.credential().isBlank()) {
                findings.add(new LintFinding("TQL-YAML-1005", "warning", source,
                        "Poll trigger source '" + kind + "' ignores credential: — remove it or"
                                + " use a remote source"));
            }
            if (config.navigate("tesseraql.connectors.poll.allowedPaths") == null) {
                findings.add(new LintFinding("TQL-SEC-4086", "error", source,
                        "Local poll source has no tesseraql.connectors.poll.allowedPaths root:"
                                + " without one the job can read — and move — files anywhere the"
                                + " process can reach"));
            }
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
            // The FTPS counterpart, and an error rather than a warning: without a trust store
            // the client accepts any in-date certificate from any host, so the handshake proves
            // nothing about the peer. The runtime refuses to wire the job either way — lint is
            // the place the author finds out.
            if ("ftps".equals(kind)
                    && config.navigate("tesseraql.connectors.poll.trustStore") == null) {
                findings.add(new LintFinding("TQL-SEC-4085", "error", source,
                        "FTPS poll source does not verify the server certificate; set"
                                + " tesseraql.connectors.poll.trustStore (file:, password:) to"
                                + " pin the CA that signs it"));
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
        var admission = definition.admission();
        if (admission == null || admission.rateLimit() == null) {
            return;
        }
        String scope = admission.rateLimit().scope();
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
     * httpCall step (TQL-SEC-4070/4071/4072 via {@link #lintHttpCall}).
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
     * Statically checks an {@code httpCall} step's egress (roadmap Phase 26): the target host
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
                        "httpCall step '" + id + "' needs an absolute http or https url:"));
            }
            lintHttpCredential(config, id, spec, source, findings);
            return;
        }
        List<String> allowedHosts = new java.util.ArrayList<>();
        if (config.navigate("tesseraql.http.outbound.allowedHosts") instanceof List<?> declared) {
            declared.forEach(value -> allowedHosts.add(String.valueOf(value)));
        }
        if (!io.tesseraql.yaml.http.HttpOutbound.hostAllowed(allowedHosts, host)) {
            findings.add(new LintFinding("TQL-SEC-4070", "error", source, "httpCall step '" + id
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
            findings.add(new LintFinding("TQL-SEC-4072", "warning", source, "httpCall step '" + id
                    + "' references undeclared credential '" + credential + "'"));
        }
    }

    /** {@code ~{tql/email/<library> :: <fragment>} references in a mail body. */
    private static final Pattern EMAIL_FRAGMENT_REF = Pattern
            .compile("~\\{tql/email/(hc-email(?:-layout)?)\\s*::\\s*("
                    + io.tesseraql.core.sql.SqlIdentifiers.IDENTIFIER + ")");
    /** The root identifier of a {@code ${...}} expression. */
    private static final Pattern EXPR_ROOT = Pattern
            .compile("\\$\\{\\s*(" + io.tesseraql.core.sql.SqlIdentifiers.IDENTIFIER + ")");
    /** {@code th:each="alias[, iterStat] : ..."} alias declarations. */
    private static final Pattern EACH_ALIAS = Pattern.compile(
            "th:each=\"\\s*(" + io.tesseraql.core.sql.SqlIdentifiers.IDENTIFIER
                    + ")\\s*(?:,\\s*(" + io.tesseraql.core.sql.SqlIdentifiers.IDENTIFIER
                    + "))?\\s*:");
    /** {@code th:with="a=..., b=..."} alias declarations. */
    private static final Pattern WITH_ALIAS = Pattern.compile("th:with=\"([^\"]*)\"");
    /** Expression roots that are always fine: the mail model plus literal keywords. */
    private static final Set<String> MAIL_ROOTS = Set.of("payload", "event", "true", "false",
            "null");

    /**
     * The mail wiring lints (docs/pages-and-mail-lints.md D2): a mail channel's template is
     * only exercised at delivery time, so the wiring is validated at build time instead —
     * the {@code template:} file exists inside the app home (the send-time
     * {@code TQL-BATCH-5304} surfaced early), an {@code .html} body references only
     * fragments the {@code tql/email} library actually declares ({@code TQL-TPL-2002},
     * read from the app's shadow copy when present, else the bundled library — whichever
     * resolves at render), and {@code ${...}} roots in the body and the channel's
     * {@code subject} resolve against the mail model — {@code payload}, {@code event}, or
     * an alias the template itself defines ({@code TQL-TPL-2003}, a warning: expression
     * aliasing can be arbitrarily clever). A channel whose {@code template:} value carries
     * a {@code ${...}} config placeholder is environment-dependent and skipped.
     */
    private void lintMailChannels(Path appHome, AppConfig config, List<LintFinding> findings) {
        if (!(config.navigate("tesseraql.notifications.channels") instanceof Map<?, ?> map)) {
            return;
        }
        String configSource = "config/tesseraql.yml";
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> channel)
                    || !"mail".equals(channel.get("type"))) {
                continue;
            }
            String name = String.valueOf(entry.getKey());
            if (channel.get("subject") instanceof String subject) {
                lintMailExpressionRoots(name, "subject", subject, Set.of(), configSource,
                        findings);
            }
            // No default recipient: delivery fails at send unless every notification's
            // payload carries a to key — legal, but worth saying at build time.
            if (channel.get("to") == null) {
                findings.add(new LintFinding("TQL-BATCH-5304", "warning", configSource,
                        "Mail channel '" + name + "' declares no to: — delivery fails"
                                + " unless every notification payload carries a to key"));
            }
            if (!(channel.get("template") instanceof String template)
                    || template.contains("${")) {
                continue;
            }
            Path resolved = appHome.resolve(template).normalize();
            if (!resolved.startsWith(appHome) || !Files.isRegularFile(resolved)) {
                findings.add(new LintFinding("TQL-BATCH-5304", "error", configSource,
                        "Mail channel '" + name + "': template '" + template
                                + "' is not a file inside the app home"));
                continue;
            }
            String body;
            try {
                body = Files.readString(resolved);
            } catch (java.io.IOException ex) {
                findings.add(new LintFinding("TQL-BATCH-5304", "warning", template,
                        "Mail channel '" + name + "': template could not be read: "
                                + ex.getMessage()));
                continue;
            }
            if (template.endsWith(".html")) {
                Matcher ref = EMAIL_FRAGMENT_REF.matcher(body);
                while (ref.find()) {
                    String library = ref.group(1);
                    String fragment = ref.group(2);
                    Set<String> declared = io.tesseraql.yaml.template.EmailFragments
                            .signatures(appHome, library).keySet();
                    if (!declared.contains(fragment)) {
                        findings.add(new LintFinding("TQL-TPL-2002", "error", template,
                                "Mail channel '" + name + "': unknown fragment '" + fragment
                                        + "' — tql/email/" + library + " declares "
                                        + declared));
                    }
                }
            }
            lintMailExpressionRoots(name, "template", body, templateAliases(body), template,
                    findings);
        }
    }

    /** The aliases a template defines itself ({@code th:each} / {@code th:with}). */
    private Set<String> templateAliases(String body) {
        Set<String> aliases = new HashSet<>();
        Matcher each = EACH_ALIAS.matcher(body);
        while (each.find()) {
            aliases.add(each.group(1));
            if (each.group(2) != null) {
                aliases.add(each.group(2));
            }
        }
        Matcher with = WITH_ALIAS.matcher(body);
        while (with.find()) {
            for (String assignment : with.group(1).split(",")) {
                int eq = assignment.indexOf('=');
                if (eq > 0) {
                    aliases.add(assignment.substring(0, eq).trim());
                }
            }
        }
        return aliases;
    }

    /** One warning per unresolvable root — the helpdesk {@code ${ticket}} bug class. */
    private void lintMailExpressionRoots(String channel, String where, String text,
            Set<String> aliases, String source, List<LintFinding> findings) {
        Set<String> reported = new HashSet<>();
        Matcher matcher = EXPR_ROOT.matcher(text);
        while (matcher.find()) {
            String root = matcher.group(1);
            if (MAIL_ROOTS.contains(root) || aliases.contains(root) || !reported.add(root)) {
                continue;
            }
            findings.add(new LintFinding("TQL-TPL-2003", "warning", source,
                    "Mail channel '" + channel + "' " + where + ": '${" + root
                            + "…}' does not resolve — the mail model carries payload and"
                            + " event"));
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
        // attach: rides only mail (docs/analytics-experience.md): a webhook posts JSON and an
        // inbox message links, so declaring an attachment there would silently drop it — the
        // build says so instead. The check only fires when the channel's declared type says
        // it is not mail; an undeclared channel already warned above.
        if (spec.attach() != null && !spec.attach().isBlank() && spec.channel() != null) {
            String type = config.getString("tesseraql.notifications.channels."
                    + spec.channel() + ".type").orElse(null);
            if (type != null && !"mail".equals(type)) {
                findings.add(new LintFinding("TQL-FIELD-2004", "error", source,
                        "Notification '" + id + "' declares attach: but channel '"
                                + spec.channel() + "' is type " + type
                                + " — attachments ride mail channels only"));
            }
        }
    }

    /**
     * Statically checks a route's {@code export:} block (docs/export-pipeline.md, decision 4).
     *
     * <p>The workbook mode is inferred from the declaration — a template with {@code startCell:}
     * is placement, a template alone is a jxls report, neither is a grid — so a declaration that
     * cannot mean what it says silently produces a different document. A missing template file
     * used to fall through to a plain grid on routes (job steps have been checked all along), and
     * {@code startCell:} without a template named a mode that does not exist.
     *
     * <p>{@code format: pdf} is a print format on top of that: the workbook-only options do not
     * apply, and its template renders through the standard template engine, so it must be
     * {@code .html}.
     */
    private void lintRouteExport(RouteFile route, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        io.tesseraql.yaml.model.ExportSpec spec = definition.fileExport();
        if (spec == null) {
            return;
        }
        boolean pdf = "pdf".equals(spec.format());
        if (pdf && (spec.sheet() != null || spec.startCell() != null)) {
            findings.add(new LintFinding("TQL-YAML-1005", "error", source,
                    "pdf export: sheet:/startCell: are workbook options - a pdf lays out"
                            + " through its template, not cell placement"));
        }
        if (!pdf && spec.startCell() != null && spec.template() == null) {
            findings.add(new LintFinding("TQL-YAML-1005", "error", source,
                    "export: startCell: places data into a template, but none is declared -"
                            + " add template:, or drop startCell: for a plain grid"));
        }
        if (spec.template() == null) {
            return;
        }
        if (pdf && !spec.template().endsWith(".html")) {
            findings.add(new LintFinding("TQL-YAML-1006", "error", source,
                    "pdf export template '" + spec.template()
                            + "' must be an .html file (it renders through the template"
                            + " engine before PDF conversion)"));
            return;
        }
        if (!Files.isRegularFile(route.source().getParent().resolve(spec.template()))) {
            findings.add(new LintFinding("TQL-YAML-1006", "error", source,
                    "export references a missing template: " + spec.template()));
        }
    }

    /**
     * An export through a format that holds every row before it writes runs under a ceiling
     * (docs/export-pipeline.md, decision 7), and an author who has not chosen one is the case
     * worth naming: until that decision, nothing at all stood between such an export and the heap.
     *
     * <p>The runtime authority is {@code FileCodec.streams(spec)}, which the linter cannot ask —
     * the optional codec modules are not on its classpath. This reads the declaration instead,
     * which answers for the formats the framework ships; anything else can say {@code maxRows: -1}
     * to state that it streams.
     */
    private void lintExportRowCap(io.tesseraql.yaml.model.ExportSpec spec, String label,
            String source, List<LintFinding> findings) {
        if (spec == null || spec.maxRows() != null) {
            return;
        }
        boolean buffers = "pdf".equals(spec.format())
                || ("excel".equals(spec.format()) && spec.template() != null);
        if (!buffers) {
            return;
        }
        findings.add(new LintFinding("TQL-LD-5310", "warning", source, label
                + "export holds every row before it writes (" + spec.format()
                + (spec.template() == null ? "" : " through a template")
                + ") and declares no maxRows:, so it runs under the app-wide default - declare"
                + " export.maxRows: for the number this document can actually carry"));
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
