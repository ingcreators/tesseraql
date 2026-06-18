package io.tesseraql.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.ResponseSpec;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.scaffold.CrudScaffolder;
import io.tesseraql.yaml.scaffold.ScaffoldChecksum;
import io.tesseraql.yaml.scaffold.ScaffoldWriter;
import io.tesseraql.yaml.scaffold.ScaffoldedFile;
import io.tesseraql.yaml.scaffold.TableSchema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The backend for TesseraQL Studio (design ch. 16): an explorer over the app's routes and jobs,
 * read-only access to their source files, and draft editing.
 *
 * <p>All file access is confined to the app home (no {@code ../} traversal, design ch. 20.2). In
 * read-only mode — the default for production — draft writes are rejected so Studio can be safely
 * exposed against a running app (ch. 16.9).
 */
public final class StudioService {

    private static final TqlErrorCode TRAVERSAL = new TqlErrorCode(TqlDomain.STUDIO, 4002);
    private static final TqlErrorCode READ_ONLY = new TqlErrorCode(TqlDomain.STUDIO, 4030);
    private static final TqlErrorCode NOT_FOUND = new TqlErrorCode(TqlDomain.STUDIO, 4040);
    private static final TqlErrorCode INVALID_DRAFT = new TqlErrorCode(TqlDomain.STUDIO, 4221);
    private static final TqlErrorCode RENDER = new TqlErrorCode(TqlDomain.STUDIO, 4222);
    private static final TqlErrorCode NEW_ROUTE = new TqlErrorCode(TqlDomain.STUDIO, 4224);
    private static final TqlErrorCode CONFLICT = new TqlErrorCode(TqlDomain.STUDIO, 4090);

    /** The HTTP-method stems that name a route document under {@code web/} (and its fixtures). */
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete",
            "head", "options");

    private final SimpleYamlParser parser = new SimpleYamlParser();
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final boolean readOnly;
    private AppManifest manifest;
    private Path appHome;

    public StudioService(AppManifest manifest, boolean readOnly) {
        this.manifest = manifest;
        this.appHome = manifest.appHome();
        this.readOnly = readOnly;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    /** A summary of every route and job in the app, for the explorer view. */
    public Explorer explorer() {
        return explorer(null);
    }

    /**
     * The explorer view narrowed to routes and jobs matching {@code query} (Studio backlog C4): a
     * case-insensitive substring over each entry's id, source path, recipe, and (for a route) its
     * HTTP method and URL path. A blank or null query matches everything. The directory tree the view
     * renders is built from the matching entries' source paths, so filtering prunes the tree.
     */
    public Explorer explorer(String query) {
        String appName = manifest.config().getString("tesseraql.app.name").orElse("app");
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        List<RouteSummary> routes = manifest.routes().stream()
                .map(this::routeSummary)
                .filter(route -> q.isEmpty() || routeMatches(route, q))
                .sorted(java.util.Comparator.comparing(RouteSummary::id))
                .toList();
        List<JobSummary> jobs = manifest.jobs().stream()
                .map(this::jobSummary)
                .filter(job -> q.isEmpty() || jobMatches(job, q))
                .sorted(java.util.Comparator.comparing(JobSummary::id))
                .toList();
        return new Explorer(appName, readOnly, routes, jobs);
    }

    private static boolean routeMatches(RouteSummary route, String q) {
        return contains(route.id(), q) || contains(route.method(), q) || contains(route.path(), q)
                || contains(route.recipe(), q) || contains(route.source(), q);
    }

    private static boolean jobMatches(JobSummary job, String q) {
        return contains(job.id(), q) || contains(job.recipe(), q) || contains(job.source(), q);
    }

    private static boolean contains(String value, String lowerQuery) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(lowerQuery);
    }

    /** Reads a source file (YAML/SQL/template) by its app-relative path. */
    public String source(String relativePath) {
        String content = sourceIfExists(relativePath);
        if (content == null) {
            throw new TqlException(NOT_FOUND, "No such file: " + relativePath);
        }
        return content;
    }

    /**
     * Reads a source file by its app-relative path, or {@code null} when no such file exists — the
     * case of a draft for a not-yet-applied new file, where there is no source to compare against.
     */
    public String sourceIfExists(String relativePath) {
        Path file = resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Saves a draft edit of {@code relativePath} under {@code work/studio/drafts} without touching
     * the source of truth (design ch. 16.7). Rejected in read-only mode.
     */
    public Path saveDraft(String relativePath, String content) {
        if (readOnly) {
            throw new TqlException(READ_ONLY, "Studio is read-only; drafts are disabled");
        }
        resolve(relativePath); // validate the target path before writing the draft
        Path draft = draftPath(relativePath);
        // The first save records the source the edit is based on, so a later apply can detect that
        // the source changed underneath it (concurrent-edit conflict, Studio backlog D5).
        boolean firstSave = !Files.isRegularFile(draft);
        try {
            Files.createDirectories(draft.getParent());
            Files.writeString(draft, content);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        if (firstSave) {
            writeBaseMeta(relativePath, sourceIfExists(relativePath));
        }
        return draft;
    }

    /**
     * Discards a saved draft of {@code relativePath} without touching the source of truth, so an
     * edit can be abandoned and the editor falls back to the source. Rejected in read-only mode;
     * idempotent (a no-op when no draft exists).
     *
     * @return whether a draft was actually removed
     */
    public boolean deleteDraft(String relativePath) {
        if (readOnly) {
            throw new TqlException(READ_ONLY, "Studio is read-only; drafts are disabled");
        }
        resolve(relativePath); // validate the target path before touching the draft
        try {
            boolean removed = Files.deleteIfExists(draftPath(relativePath));
            Files.deleteIfExists(draftMetaPath(relativePath));
            return removed;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Whether applying {@code relativePath}'s draft would overwrite a source that changed since the
     * draft was started (Studio backlog D5): the recorded base differs from the current source. False
     * when there is no draft or no recorded base (e.g. a draft from before base tracking).
     */
    public boolean draftConflicts(String relativePath) {
        if (readDraft(relativePath) == null) {
            return false;
        }
        BaseMeta meta = readBaseMeta(relativePath);
        if (meta == null) {
            return false;
        }
        return !java.util.Objects.equals(meta.base(), sourceIfExists(relativePath));
    }

    /**
     * Compiles a draft (or current source) without applying it, so edits can be validated before
     * they touch the source of truth (design ch. 16.6): route YAML is parsed, SQL is rendered.
     */
    public PreviewResult preview(String relativePath, String content) {
        String text = content != null ? content : source(relativePath);
        if (relativePath.endsWith(".sql")) {
            try {
                BoundSql bound = SqlRenderer.render(Sql2WayParser.parse(text), Map.of());
                return PreviewResult.valid("sql", bound.sql());
            } catch (RuntimeException ex) {
                return PreviewResult.invalid("sql", ex.getMessage());
            }
        }
        if (relativePath.startsWith("web/") && relativePath.endsWith(".yml")) {
            try {
                RouteDefinition definition = parser.parseRoute(text, relativePath);
                return PreviewResult.valid("route",
                        "id=" + definition.id() + ", recipe=" + definition.recipe());
            } catch (RuntimeException ex) {
                return PreviewResult.invalid("route", ex.getMessage());
            }
        }
        if (relativePath.endsWith(".html") || relativePath.endsWith(".tpl")) {
            return previewTemplate(relativePath, text);
        }
        return PreviewResult.valid("text", text);
    }

    /**
     * Validates a draft template by processing it with the standard engine and an empty model
     * (design ch. 16.6): framework {@code tql/*} fragments resolve from the classpath and other
     * app templates from the app home, so cross-references are checked too. Markup/parse errors
     * are invalid; expression errors that need real route data still count as parsed.
     */
    private PreviewResult previewTemplate(String relativePath, String content) {
        try {
            templateEngine(relativePath).process(content, new org.thymeleaf.context.Context());
            return PreviewResult.valid("template",
                    "template parses and renders with an empty model");
        } catch (RuntimeException ex) {
            if (isDataDependent(ex)) {
                return PreviewResult.valid("template",
                        "template parses; full render needs route data (" + rootMessage(ex) + ")");
            }
            return PreviewResult.invalid("template", rootMessage(ex));
        }
    }

    /**
     * Renders a draft (or current source) against a sample model and returns the actual output
     * (design ch. 16.6, Studio backlog A1) — the step past {@link #preview}, which only proves a
     * data-dependent template parses. Two shapes render:
     *
     * <ul>
     *   <li>a <b>template file</b> ({@code .html}/{@code .tpl}) renders against {@code sampleModel}
     *       read as the template's top-level variables;</li>
     *   <li>a <b>web route</b> ({@code web/**}/{@code <method>.yml}) renders its {@code response}
     *       against {@code sampleModel} read as the execution context (e.g. {@code params},
     *       {@code sql.rows}): a {@code query-html}/{@code page} route resolves
     *       {@code response.html.model} and renders the route's template, a {@code query-json} route
     *       resolves {@code response.json.body} and pretty-prints it.</li>
     * </ul>
     *
     * <p>{@code sampleModel} is YAML/JSON; when blank it falls back to the colocated
     * {@code *.sample.yml} fixture, then an empty model. The same three-resolver engine as
     * {@link #preview} resolves {@code tql/*} fragments and sibling app templates, so HTML output
     * matches a real response. Field-level output masking ({@code response.json.fields}) is not
     * applied in preview.
     */
    public RenderResult render(String relativePath, String content, String sampleModel) {
        return render(relativePath, content, sampleModel, null);
    }

    /**
     * As {@link #render(String, String, String)}, but for a web route a non-null {@code liveRows}
     * supplies the {@code sql} rows by executing the route's query against the dev datasource (the
     * Studio backlog A1 "real bound params" end, powered by the A2 sandbox) instead of taking them
     * from the hand-authored sample. Studio itself stays database-free: the caller (the runtime)
     * provides the sandboxed row source.
     */
    public RenderResult render(String relativePath, String content, String sampleModel,
            RowSource liveRows) {
        if (isTemplate(relativePath)) {
            return renderTemplateFile(relativePath, content, sampleModel);
        }
        if (isRouteYaml(relativePath)) {
            return renderRoute(relativePath, content, sampleModel, liveRows);
        }
        return RenderResult.invalid("text",
                "Rendered preview is only available for templates and web routes");
    }

    /**
     * Supplies live {@code sql} rows for a route render by executing its query against the dev
     * datasource (Studio backlog A1/A2). Implemented by the runtime over the sandboxed datasource so
     * Studio stays database-free.
     */
    @FunctionalInterface
    public interface RowSource {
        /**
         * The live {@code sql} result ({@code rows}/{@code rowCount}) for the route's main query,
         * its bind params resolved from {@code context}; null to keep the hand-authored sample's
         * {@code sql}. May throw to report a query failure.
         */
        Map<String, Object> rowsFor(RouteDefinition route, Path routeDir,
                Map<String, Object> context);
    }

    private RenderResult renderTemplateFile(String relativePath, String content,
            String sampleModel) {
        String text = content != null ? content : source(relativePath);
        Map<String, Object> model;
        try {
            model = parseSample(relativePath, sampleModel);
        } catch (RuntimeException ex) {
            return RenderResult.invalid("sample", "Sample data: " + rootMessage(ex));
        }
        return renderTemplateContent(relativePath, text, model);
    }

    private RenderResult renderRoute(String relativePath, String content, String sampleModel,
            RowSource liveRows) {
        String text = content != null ? content : source(relativePath);
        RouteDefinition definition;
        try {
            definition = parser.parseRoute(text, relativePath);
        } catch (RuntimeException ex) {
            return RenderResult.invalid("route", rootMessage(ex));
        }
        Map<String, Object> context;
        try {
            // A mutable copy: live rows are injected as the `sql` key before model resolution.
            context = new LinkedHashMap<>(parseSample(relativePath, sampleModel));
        } catch (RuntimeException ex) {
            return RenderResult.invalid("sample", "Sample data: " + rootMessage(ex));
        }
        if (liveRows != null) {
            try {
                Map<String, Object> sql = liveRows.rowsFor(definition,
                        resolve(relativePath).getParent(), context);
                if (sql != null) {
                    context.put("sql", sql);
                }
            } catch (RuntimeException ex) {
                return RenderResult.invalid("route", "Live data: " + rootMessage(ex));
            }
        }
        EvaluationContext evaluation = new EvaluationContext(context);
        ResponseSpec response = definition.response();
        if (response != null && response.html() != null) {
            return renderHtmlRoute(relativePath, response.html(), evaluation);
        }
        if (response != null && response.json() != null) {
            return renderJsonRoute(response.json(), evaluation);
        }
        return RenderResult.invalid("route",
                "Rendered preview supports query-html/page and query-json routes only");
    }

    private RenderResult renderHtmlRoute(String routePath, ResponseSpec.HtmlResponse html,
            EvaluationContext evaluation) {
        String templateRel;
        try {
            templateRel = resolveRouteTemplate(routePath, html.template());
        } catch (RuntimeException ex) {
            return RenderResult.invalid("html", rootMessage(ex));
        }
        String templateContent = sourceIfExists(templateRel);
        if (templateContent == null) {
            return RenderResult.invalid("html", "Template not found: " + html.template());
        }
        Map<String, Object> model = new LinkedHashMap<>();
        html.model().forEach((key, expr) -> model.put(key,
                evaluation.resolve(Arrays.asList(String.valueOf(expr).split("\\.")))));
        return renderTemplateContent(templateRel, templateContent, model);
    }

    private RenderResult renderJsonRoute(ResponseSpec.JsonResponse json,
            EvaluationContext evaluation) {
        Object body = resolveJson(json.body(), evaluation);
        try {
            return RenderResult.ok("json",
                    jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return RenderResult.invalid("json", "Failed to serialize JSON: " + ex.getMessage());
        }
    }

    /**
     * Recursively resolves a JSON body template against the context (the same walk as the compiler's
     * {@code JsonResponseRenderer}): leaf strings are dotted-path expressions, maps and lists recurse,
     * other scalars are literals.
     */
    private Object resolveJson(Object template, EvaluationContext evaluation) {
        if (template instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((key, value) -> resolved.put(String.valueOf(key),
                    resolveJson(value, evaluation)));
            return resolved;
        }
        if (template instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>(list.size());
            list.forEach(element -> resolved.add(resolveJson(element, evaluation)));
            return resolved;
        }
        if (template instanceof String expression) {
            return evaluation.resolve(Arrays.asList(expression.split("\\.")));
        }
        return template;
    }

    /** Parses the effective sample model: the given text, else the colocated fixture, else empty. */
    private Map<String, Object> parseSample(String relativePath, String sampleModel) {
        String effective = sampleModel != null && !sampleModel.isBlank()
                ? sampleModel
                : sampleModel(relativePath);
        return parser.parseTree(effective);
    }

    private RenderResult renderTemplateContent(String templateRelPath, String content,
            Map<String, Object> model) {
        String kind = templateRelPath.endsWith(".html") ? "html" : "text";
        try {
            String output = templateEngine(templateRelPath).process(content,
                    new org.thymeleaf.context.Context(java.util.Locale.ENGLISH, model));
            return RenderResult.ok(kind, output);
        } catch (RuntimeException ex) {
            return RenderResult.invalid(kind, rootMessage(ex));
        }
    }

    /**
     * Resolves a route's template like the compiler's {@code HtmlResponseRenderer}: colocated next to
     * the route first, then the shared {@code templates/} root; confined to the app home. Returns the
     * app-home-relative path.
     */
    private String resolveRouteTemplate(String routePath, String template) {
        Path routeDir = resolve(routePath).getParent();
        Path colocated = routeDir.resolve(template).normalize();
        Path file = Files.isRegularFile(colocated)
                ? colocated
                : appHome.resolve("templates").resolve(template).normalize();
        if (!file.startsWith(appHome)) {
            throw new TqlException(RENDER, "Template escapes app home: " + template);
        }
        return appHome.relativize(file).toString().replace('\\', '/');
    }

    /**
     * The colocated sample-data fixture for a renderable file — {@code <base>.sample.yml} next to it
     * (e.g. {@code .../table.html} → {@code .../table.sample.yml}, {@code .../get.yml} →
     * {@code .../get.sample.yml}), or null when the file is not renderable or no fixture exists. The
     * fixture is a YAML map: the template's variables for a template, or the execution context
     * ({@code params}, {@code sql.rows}, …) for a route. The manifest loader ignores it (only
     * HTTP-method {@code *.yml} files under {@code web/} are routes), so it lives beside its file.
     */
    public String sampleModel(String relativePath) {
        if (!isTemplate(relativePath) && !isRouteYaml(relativePath)) {
            return null;
        }
        int dot = relativePath.lastIndexOf('.');
        String fixture = relativePath.substring(0, dot) + ".sample.yml";
        return sourceIfExists(fixture);
    }

    /**
     * Previews the CRUD slice the scaffold would generate for an introspected table (Studio backlog
     * B3, roadmap Phase 23) without writing anything: every generated file with its content and the
     * disposition an {@link io.tesseraql.yaml.scaffold.ScaffoldWriter} apply would give it. Studio
     * itself stays database-free — the caller (the runtime) introspects the dev datasource and hands
     * over the {@link TableSchema}, exactly the shape the CLI {@code scaffold crud} works from, so the
     * preview and a later apply are byte-identical to the command-line generator.
     *
     * <p>Each file's {@code status} mirrors {@code ScaffoldWriter.decide}: {@code new} (no such file
     * yet), {@code unchanged} (already byte-identical to the generation), {@code regenerate} (a
     * pristine generated file an apply would overwrite), or {@code conflict} (a file the user edited
     * or owns, which an apply leaves alone unless forced). Generation throws when the table cannot be
     * scaffolded (e.g. a composite or missing primary key).
     */
    public ScaffoldPreview scaffoldPreview(TableSchema table) {
        List<ScaffoldFile> files = new ArrayList<>();
        int writes = 0;
        int conflicts = 0;
        for (ScaffoldedFile file : new CrudScaffolder().scaffold(table)) {
            String status = scaffoldStatus(file);
            files.add(new ScaffoldFile(file.path(), file.content(), status));
            if ("new".equals(status) || "regenerate".equals(status)) {
                writes++;
            } else if ("conflict".equals(status)) {
                conflicts++;
            }
        }
        return new ScaffoldPreview(table.name(), files, files.size(), writes, conflicts);
    }

    /**
     * Writes a table's scaffolded CRUD slice into the app home (Studio backlog B3, roadmap Phase 23),
     * honoring the scaffold's edit-detection contract: a pristine generated file is regenerated in
     * place, but a file the user edited or owns is left alone and reported as skipped unless
     * {@code force} overrides it. Rejected in read-only mode.
     *
     * <p>Generation is the same pure {@link CrudScaffolder} the preview and the CLI use, so the
     * written files are byte-identical across all three. Newly written route documents
     * ({@code web/**}/{@code <method>.yml} the manifest did not already declare) are reported
     * separately: the hot reloader only swaps existing routes, so serving a new route needs a restart
     * (design ch. 16.8).
     */
    public ScaffoldResult scaffoldApply(TableSchema table, boolean force) {
        return scaffoldApply(table, force, null);
    }

    /**
     * As {@link #scaffoldApply(TableSchema, boolean)}, but {@code actor} (the caller) is recorded to
     * the audit trail once files are written (Studio backlog D6).
     */
    public ScaffoldResult scaffoldApply(TableSchema table, boolean force, String actor) {
        if (readOnly) {
            throw new TqlException(READ_ONLY, "Studio is read-only; scaffolding is disabled");
        }
        List<ScaffoldedFile> files = new CrudScaffolder().scaffold(table);
        Set<String> existingRoutes = manifest.routes().stream()
                .map(route -> relative(route.source()))
                .collect(java.util.stream.Collectors.toSet());
        ScaffoldWriter.Report report = new ScaffoldWriter().apply(appHome, files, force);
        List<String> newRoutes = report.written().stream()
                .filter(StudioService::isRouteYaml)
                .filter(path -> !existingRoutes.contains(path))
                .toList();
        if (!report.written().isEmpty()) {
            recordAudit(actor, "scaffold", table.name());
        }
        return new ScaffoldResult(table.name(), report.written(), report.unchanged(),
                report.skipped(), newRoutes, report.blocked());
    }

    /**
     * Creates a new route from a starter skeleton for the given {@code recipe} (Studio backlog B3):
     * it saves the skeleton as a draft at {@code path} — a {@code web/**}/{@code <method>.yml} file
     * that must not already exist — so the source editor's validate → apply flow then finishes
     * creating it (the new route needs a restart to be served, like any newly added route). Rejected
     * in read-only mode. Returns the draft path.
     */
    public Path newRouteDraft(String path, String recipe) {
        if (readOnly) {
            throw new TqlException(READ_ONLY, "Studio is read-only; creating routes is disabled");
        }
        if (!isRouteYaml(path)) {
            throw new TqlException(NEW_ROUTE,
                    "A new route path must be a web/**/<method>.yml file: " + path);
        }
        if (sourceIfExists(path) != null) {
            throw new TqlException(NEW_ROUTE, "A file already exists at " + path
                    + "; open it in the editor instead");
        }
        return saveDraft(path, starterRoute(path, recipe));
    }

    /** The starter route skeleton for a recipe: a parseable draft the author then completes. */
    private static String starterRoute(String path, String recipe) {
        String id = path.substring("web/".length(), path.length() - ".yml".length())
                .replace('/', '.');
        return switch (recipe) {
            case "query-html" ->
                """
                        version: tesseraql/v1
                        id: %s
                        kind: route
                        recipe: query-html

                        security:
                          auth: bearer

                        sql:
                          file: query.sql
                          mode: query

                        response:
                          html:
                            status: 200
                            template: page.html
                            model:
                              rows: sql.rows
                            headers:
                              Content-Security-Policy: "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'"
                              X-Content-Type-Options: nosniff
                              X-Frame-Options: DENY
                              Referrer-Policy: no-referrer
                        """
                        .formatted(id);
            case "command-json" -> """
                    version: tesseraql/v1
                    id: %s
                    kind: route
                    recipe: command-json

                    security:
                      auth: bearer

                    sql:
                      file: command.sql
                      mode: update

                    response:
                      json:
                        status: 200
                        body:
                          affected: sql.affectedRows
                    """.formatted(id);
            default -> """
                    version: tesseraql/v1
                    id: %s
                    kind: route
                    recipe: query-json

                    security:
                      auth: bearer

                    sql:
                      file: query.sql
                      mode: query

                    response:
                      json:
                        status: 200
                        body:
                          data: sql.rows
                    """.formatted(id);
        };
    }

    /** The disposition an apply would give a generated file, by reading its on-disk counterpart. */
    private String scaffoldStatus(ScaffoldedFile file) {
        Path target = resolve(file.path());
        if (!Files.isRegularFile(target)) {
            return "new";
        }
        String existing;
        try {
            existing = Files.readString(target);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        if (existing.equals(file.stampedContent())) {
            return "unchanged";
        }
        return ScaffoldChecksum.status(existing) == ScaffoldChecksum.Status.PRISTINE
                ? "regenerate"
                : "conflict";
    }

    private static boolean isTemplate(String relativePath) {
        return relativePath.endsWith(".html") || relativePath.endsWith(".tpl");
    }

    /** Whether the path is a web route document ({@code web/**}/{@code <method>.yml}). */
    private static boolean isRouteYaml(String relativePath) {
        if (!relativePath.startsWith("web/") || !relativePath.endsWith(".yml")) {
            return false;
        }
        int slash = relativePath.lastIndexOf('/');
        String stem = relativePath.substring(slash + 1, relativePath.length() - ".yml".length());
        return HTTP_METHODS.contains(stem);
    }

    /**
     * Builds a Thymeleaf engine matching the production stack (design ch. 12) for previewing or
     * rendering a draft string: framework {@code tql/*} fragments resolve from the classpath, sibling
     * {@code *.html} app templates from the app home (so cross-references resolve), and the draft
     * itself from the in-memory string — in HTML mode for {@code .html}, TEXT mode otherwise.
     */
    private org.thymeleaf.TemplateEngine templateEngine(String relativePath) {
        org.thymeleaf.TemplateEngine engine = new org.thymeleaf.TemplateEngine();

        org.thymeleaf.templateresolver.ClassLoaderTemplateResolver shared = new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver(
                StudioService.class.getClassLoader());
        shared.setPrefix("tesseraql/templates/");
        shared.setSuffix(".html");
        shared.setTemplateMode(org.thymeleaf.templatemode.TemplateMode.HTML);
        shared.setResolvablePatterns(java.util.Set.of("tql/*"));
        shared.setOrder(1);
        engine.addTemplateResolver(shared);

        org.thymeleaf.templateresolver.FileTemplateResolver files = new org.thymeleaf.templateresolver.FileTemplateResolver();
        files.setPrefix(appHome.toString() + java.io.File.separator);
        files.setTemplateMode(org.thymeleaf.templatemode.TemplateMode.HTML);
        files.setResolvablePatterns(java.util.Set.of("*.html"));
        files.setCheckExistence(true);
        files.setOrder(2);
        engine.addTemplateResolver(files);

        org.thymeleaf.templateresolver.StringTemplateResolver draft = new org.thymeleaf.templateresolver.StringTemplateResolver();
        draft.setTemplateMode(relativePath.endsWith(".html")
                ? org.thymeleaf.templatemode.TemplateMode.HTML
                : org.thymeleaf.templatemode.TemplateMode.TEXT);
        draft.setOrder(3);
        engine.addTemplateResolver(draft);
        return engine;
    }

    /**
     * Whether the failure only happens because the empty preview model lacks route data (an
     * expression evaluated over null), as opposed to a static authoring error (malformed markup,
     * unparseable expression, unresolvable template reference).
     */
    private static boolean isDataDependent(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t.getClass().getName().startsWith("ognl.") || t instanceof NullPointerException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }

    /**
     * Promotes a saved draft to the source of truth after validating it compiles (design ch. 16.7).
     * Rejected in read-only mode; the draft is removed once applied.
     */
    public Path applyDraft(String relativePath) {
        return applyDraft(relativePath, false, null);
    }

    public Path applyDraft(String relativePath, boolean force) {
        return applyDraft(relativePath, force, null);
    }

    /**
     * As {@link #applyDraft(String)}, but {@code force} overwrites a source that changed since the
     * draft was started (Studio backlog D5), and {@code actor} (the caller, for the audit trail,
     * Studio backlog D6) is recorded once the draft is promoted. Without {@code force}, a
     * concurrent-edit conflict is rejected so the draft cannot silently clobber another change
     * (last-apply-wins).
     */
    public Path applyDraft(String relativePath, boolean force, String actor) {
        if (readOnly) {
            throw new TqlException(READ_ONLY, "Studio is read-only; apply is disabled");
        }
        String draft = readDraft(relativePath);
        if (draft == null) {
            throw new TqlException(NOT_FOUND, "No draft to apply for: " + relativePath);
        }
        if (!force && draftConflicts(relativePath)) {
            throw new TqlException(CONFLICT,
                    "The saved source changed since this draft was started;"
                            + " review the diff and re-apply to overwrite, or discard the draft.");
        }
        PreviewResult preview = preview(relativePath, draft);
        if (!preview.valid()) {
            throw new TqlException(INVALID_DRAFT, "Draft does not compile: " + preview.error());
        }
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, draft);
            Files.deleteIfExists(draftPath(relativePath));
            Files.deleteIfExists(draftMetaPath(relativePath));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        recordAudit(actor, "apply", relativePath);
        return target;
    }

    /**
     * Re-reads the manifest from disk so the explorer and source views reflect applied changes
     * (design ch. 16.8). Returns the refreshed explorer.
     */
    public Explorer reload() {
        this.manifest = new ManifestLoader().load(appHome);
        this.appHome = manifest.appHome();
        return explorer();
    }

    /**
     * Every pending draft under {@code work/studio/drafts} (Studio backlog D5): the app-relative path
     * each one edits, whether it conflicts with a source that changed underneath it, and whether it is
     * a new file (no source yet). Sorted by path; the base sidecars are skipped.
     */
    public List<DraftSummary> drafts() {
        Path draftsDir = appHome.resolve("work/studio/drafts").normalize();
        if (!Files.isDirectory(draftsDir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(draftsDir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString().endsWith(".meta"))
                    .map(file -> draftsDir.relativize(file).toString().replace('\\', '/'))
                    .sorted()
                    .map(path -> new DraftSummary(path, draftConflicts(path),
                            sourceIfExists(path) == null))
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * The audit trail (Studio backlog D6): who applied or scaffolded what, when — newest first, up to
     * {@code limit} entries. The trail is the append-only {@code work/studio/audit/audit.jsonl} log
     * the source-writing operations stamp; an empty list when the log is absent.
     */
    public List<AuditEntry> auditEntries(int limit) {
        Path log = auditLog();
        if (!Files.isRegularFile(log)) {
            return List.of();
        }
        List<AuditEntry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(log)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = jsonMapper.readTree(line);
                entries.add(
                        new AuditEntry(node.path("at").asText(""), node.path("actor").asText(""),
                                node.path("action").asText(""), node.path("target").asText("")));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        java.util.Collections.reverse(entries);
        return entries.size() > limit ? List.copyOf(entries.subList(0, limit)) : entries;
    }

    /** Appends one audit entry for a source-writing action (Studio backlog D6). */
    private void recordAudit(String actor, String action, String target) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("at", Instant.now().toString());
        entry.put("actor", actor == null || actor.isBlank() ? "unknown" : actor);
        entry.put("action", action);
        entry.put("target", target);
        Path log = auditLog();
        try {
            Files.createDirectories(log.getParent());
            Files.writeString(log, jsonMapper.writeValueAsString(entry) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Path auditLog() {
        return appHome.resolve("work/studio/audit/audit.jsonl").normalize();
    }

    /** Reads a previously saved draft, or null if none exists. */
    public String readDraft(String relativePath) {
        Path draft = draftPath(relativePath);
        if (!Files.isRegularFile(draft)) {
            return null;
        }
        try {
            return Files.readString(draft);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Path resolve(String relativePath) {
        Path resolved = appHome.resolve(relativePath).normalize();
        if (!resolved.startsWith(appHome)) {
            throw new TqlException(TRAVERSAL,
                    "Path escapes app home (design ch. 20.2): " + relativePath);
        }
        return resolved;
    }

    private Path draftPath(String relativePath) {
        Path drafts = appHome.resolve("work/studio/drafts").normalize();
        Path resolved = drafts.resolve(relativePath).normalize();
        if (!resolved.startsWith(drafts)) {
            throw new TqlException(TRAVERSAL, "Draft path escapes drafts dir: " + relativePath);
        }
        return resolved;
    }

    /** The sidecar recording the source a draft is based on (Studio backlog D5). */
    private Path draftMetaPath(String relativePath) {
        Path draft = draftPath(relativePath);
        return draft.resolveSibling(draft.getFileName().toString() + ".meta");
    }

    /** Records the source content a draft is based on ({@code null} when the source did not exist). */
    private void writeBaseMeta(String relativePath, String base) {
        Path meta = draftMetaPath(relativePath);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("base", base);
        try {
            Files.createDirectories(meta.getParent());
            Files.writeString(meta, jsonMapper.writeValueAsString(data));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Reads the recorded base for a draft, or {@code null} when none was recorded. */
    private BaseMeta readBaseMeta(String relativePath) {
        Path meta = draftMetaPath(relativePath);
        if (!Files.isRegularFile(meta)) {
            return null;
        }
        try {
            JsonNode node = jsonMapper.readTree(Files.readString(meta));
            JsonNode base = node.get("base");
            return new BaseMeta(base == null || base.isNull() ? null : base.asText());
        } catch (IOException ex) {
            return null;
        }
    }

    /** The source a draft was based on ({@code base} is null when the source did not exist). */
    private record BaseMeta(String base) {
    }

    private RouteSummary routeSummary(RouteFile route) {
        return new RouteSummary(route.definition().id(), route.httpMethod(), route.urlPath(),
                route.definition().recipe(), relative(route.source()));
    }

    private JobSummary jobSummary(JobFile job) {
        return new JobSummary(job.definition().id(), job.definition().recipe(),
                relative(job.source()));
    }

    private String relative(Path source) {
        return appHome.relativize(source).toString().replace('\\', '/');
    }

    /** The explorer model: the app and its routes and jobs. */
    public record Explorer(String appName, boolean readOnly,
            List<RouteSummary> routes, List<JobSummary> jobs) {
    }

    /** A route entry in the explorer. */
    public record RouteSummary(String id, String method, String path, String recipe,
            String source) {
    }

    /** A job entry in the explorer. */
    public record JobSummary(String id, String recipe, String source) {
    }

    /**
     * A pending draft in the draft overview (Studio backlog D5): the app-relative path it edits,
     * whether it conflicts with a source that changed underneath it, and whether it is a new file.
     */
    public record DraftSummary(String path, boolean conflict, boolean isNew) {
    }

    /**
     * One audit-trail entry (Studio backlog D6): when a source-writing action happened ({@code at},
     * an ISO-8601 instant), who did it ({@code actor}), the {@code action} ({@code apply}/{@code
     * scaffold}), and the {@code target} (the applied path or the scaffolded table).
     */
    public record AuditEntry(String at, String actor, String action, String target) {
    }

    /**
     * The preview of a table's scaffolded CRUD slice (Studio backlog B3): every generated file with
     * its content and apply disposition, plus the counts a confirmation step shows — how many files
     * an apply would write and how many it would skip as conflicts.
     */
    public record ScaffoldPreview(String table, List<ScaffoldFile> files, int total,
            int writeCount, int conflictCount) {

        public ScaffoldPreview {
            files = List.copyOf(files);
        }
    }

    /**
     * One previewed scaffold file: its app-home-relative path, generated content (before checksum
     * stamping), and the {@code status} an apply would give it ({@code new}/{@code unchanged}/
     * {@code regenerate}/{@code conflict}).
     */
    public record ScaffoldFile(String path, String content, String status) {
    }

    /**
     * The outcome of a scaffold apply (Studio backlog B3): the files written, left unchanged, and
     * skipped (edited/owned), the subset of written files that are newly added routes needing a
     * restart to be served, and whether any file was held back ({@code blocked}).
     */
    public record ScaffoldResult(String table, List<String> written, List<String> unchanged,
            List<String> skipped, List<String> newRoutes, boolean blocked) {

        public ScaffoldResult {
            written = List.copyOf(written);
            unchanged = List.copyOf(unchanged);
            skipped = List.copyOf(skipped);
            newRoutes = List.copyOf(newRoutes);
        }
    }

    /** The outcome of a preview/validation: whether it compiled, and the result or error detail. */
    public record PreviewResult(boolean valid, String kind, String result, String error) {

        static PreviewResult valid(String kind, String result) {
            return new PreviewResult(true, kind, result, null);
        }

        static PreviewResult invalid(String kind, String error) {
            return new PreviewResult(false, kind, null, error);
        }
    }

    /**
     * The outcome of a rendered preview (Studio backlog A1): whether the template rendered against
     * the sample model, the {@code kind} of output ({@code html}/{@code text}, or {@code sample}
     * when the sample data itself was malformed), and either the rendered {@code output} or the
     * {@code error} detail.
     */
    public record RenderResult(boolean ok, String kind, String output, String error) {

        static RenderResult ok(String kind, String output) {
            return new RenderResult(true, kind, output, null);
        }

        static RenderResult invalid(String kind, String error) {
            return new RenderResult(false, kind, null, error);
        }
    }
}
