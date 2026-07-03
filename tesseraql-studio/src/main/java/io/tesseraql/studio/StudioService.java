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
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.flags.FlagsSpec;
import io.tesseraql.yaml.i18n.MessageCatalog;
import io.tesseraql.yaml.lint.AppLinter;
import io.tesseraql.yaml.lint.LintFinding;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.MigrationFile;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.menu.MenuSpec;
import io.tesseraql.yaml.menu.MenuSpec.MenuItem;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final TqlErrorCode MENU = new TqlErrorCode(TqlDomain.STUDIO, 4225);
    private static final TqlErrorCode POLICY = new TqlErrorCode(TqlDomain.STUDIO, 4226);
    private static final TqlErrorCode MESSAGE = new TqlErrorCode(TqlDomain.STUDIO, 4227);
    private static final TqlErrorCode CONFIG = new TqlErrorCode(TqlDomain.STUDIO, 4228);
    private static final TqlErrorCode FLAG = new TqlErrorCode(TqlDomain.STUDIO, 4229);
    private static final TqlErrorCode CONFLICT = new TqlErrorCode(TqlDomain.STUDIO, 4090);
    private static final Pattern LEADING_DIGITS = Pattern.compile("^\\d+");
    private static final Pattern POLICY_ID = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern LOCALE_TAG = Pattern.compile("[A-Za-z0-9-]+");
    private static final Pattern MESSAGE_KEY = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern MIGRATION_PATH = Pattern
            .compile("db/(?:[^/]+/)?migration(?:-[^/]+)?/[^/]+\\.sql");

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
        // Pending drafts, filtered by the same query over their path so filtering prunes the draft
        // markers with the tree. A draft whose path is a served route/job source marks that leaf as
        // edited; a new (not-yet-served) draft becomes its own pending node (StudioViews.tree).
        List<DraftSummary> drafts = drafts().stream()
                .filter(draft -> q.isEmpty() || contains(draft.path(), q))
                .toList();
        return new Explorer(appName, readOnly, routes, jobs, drafts);
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
        // A browser normalizes a <textarea>'s newlines to CRLF on submit, so a draft saved from the
        // editor arrives with \r\n even when nothing was edited. Store LF so a no-op save matches the
        // (LF) source — otherwise every line reads as changed in the diff, and applying the draft
        // would silently rewrite the source's line endings.
        content = content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n');
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
     * matches a real response. A {@code query-json} route's output-field masking
     * ({@code response.json.fields}) is applied when a {@link FieldMask} is supplied.
     */
    public RenderResult render(String relativePath, String content, String sampleModel) {
        return render(relativePath, content, sampleModel, null, null);
    }

    public RenderResult render(String relativePath, String content, String sampleModel,
            RowSource liveRows) {
        return render(relativePath, content, sampleModel, liveRows, null);
    }

    /**
     * As {@link #render(String, String, String)}, but for a web route a non-null {@code liveRows}
     * supplies the {@code sql} rows by executing the route's query against the dev datasource (the
     * Studio backlog A1 "real bound params" end, powered by the A2 sandbox), and a non-null
     * {@code fieldMask} applies a {@code query-json} route's {@code response.json.fields} output
     * masking to the resolved body (Studio backlog A1 follow-up). Studio itself stays free of the
     * security/compiler stack: the caller (the runtime) provides the sandboxed row source and the
     * mask over the canonical field-policy applier.
     */
    public RenderResult render(String relativePath, String content, String sampleModel,
            RowSource liveRows, FieldMask fieldMask) {
        return render(relativePath, content, sampleModel, liveRows, fieldMask, null);
    }

    /**
     * As {@link #render(String, String, String, RowSource, FieldMask)}, but a non-null
     * {@code pdfRender} renders a {@code query-export} {@code format: pdf} route's PDF from its query
     * rows (Studio backlog A1 follow-up — PDF preview), returned as a {@code data:} URL. Studio stays
     * free of the heavy (optional) PDF stack: the runtime provides the renderer over the canonical PDF
     * codec when the {@code tesseraql-pdf} module is on the classpath.
     */
    public RenderResult render(String relativePath, String content, String sampleModel,
            RowSource liveRows, FieldMask fieldMask, PdfRender pdfRender) {
        if (isTemplate(relativePath)) {
            return renderTemplateFile(relativePath, content, sampleModel);
        }
        if (isRouteYaml(relativePath)) {
            return renderRoute(relativePath, content, sampleModel, liveRows, fieldMask, pdfRender);
        }
        return RenderResult.invalid("text",
                "Rendered preview is only available for templates and web routes");
    }

    /**
     * Applies a {@code query-json} route's {@code response.json.fields} output masking to the
     * resolved body (Studio backlog A1 follow-up): hide/redact fields per their policy, evaluated for
     * the sample principal in {@code context.get("principal")}. Implemented by the runtime over the
     * canonical {@code FieldPolicyApplier} so Studio stays free of the security/compiler stack.
     */
    @FunctionalInterface
    public interface FieldMask {
        /** The masked body for {@code fields}, evaluated against the sample principal in context. */
        Object mask(Map<String, ResponseSpec.FieldPolicy> fields, Object body,
                Map<String, Object> context);
    }

    /**
     * Renders a {@code query-export} {@code format: pdf} route to PDF bytes from its query
     * {@code rows} (Studio backlog A1 follow-up — PDF preview). Implemented by the runtime over the
     * canonical PDF codec so Studio stays free of the optional {@code tesseraql-pdf} stack; returns
     * {@code null} when no PDF codec is on the classpath.
     */
    @FunctionalInterface
    public interface PdfRender {
        /** PDF bytes for the export route, or null when the {@code tesseraql-pdf} module is absent. */
        byte[] render(io.tesseraql.yaml.model.ExportSpec export, Path routeDir,
                List<Map<String, Object>> rows);
    }

    /**
     * Supplies live read results for a route render by executing its queries against the dev
     * datasource (Studio backlog A1/A2; multi-binding): the main {@code sql} <em>and</em> every
     * named {@code query}, each keyed by its model name. Implemented by the runtime over the
     * sandboxed datasource so Studio stays database-free.
     */
    @FunctionalInterface
    public interface RowSource {
        /**
         * The live results keyed by model name — the main query under {@code sql} and each named
         * {@code query} under its own name, each a {@code {rows, rowCount}} map — with bind params
         * resolved from {@code context} in authored order (so a later query may read an earlier
         * one's result). Null or empty keeps the hand-authored sample. May throw to report a failure.
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
            RowSource liveRows, FieldMask fieldMask, PdfRender pdfRender) {
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
                Map<String, Object> live = liveRows.rowsFor(definition,
                        resolve(relativePath).getParent(), context);
                if (live != null) {
                    // Each entry is a model key: the main `sql` plus every named query by its name.
                    live.forEach(context::put);
                }
            } catch (RuntimeException ex) {
                return RenderResult.invalid("route", "Live data: " + rootMessage(ex));
            }
        }
        io.tesseraql.yaml.model.ExportSpec export = definition.fileExport();
        if (export != null && "pdf".equalsIgnoreCase(export.format())) {
            return renderPdfRoute(export, resolve(relativePath).getParent(), context, pdfRender);
        }
        EvaluationContext evaluation = new EvaluationContext(context);
        ResponseSpec response = definition.response();
        if (response != null && response.html() != null) {
            return renderHtmlRoute(relativePath, response.html(), evaluation);
        }
        if (response != null && response.json() != null) {
            return renderJsonRoute(response.json(), evaluation, context, fieldMask);
        }
        return RenderResult.invalid("route", "Rendered preview supports query-html/page,"
                + " query-json, and query-export (pdf) routes only");
    }

    /**
     * Renders a {@code query-export} {@code format: pdf} route to a {@code data:} URL preview (Studio
     * backlog A1 follow-up): the sample's {@code sql.rows} feed the route's PDF, produced by the
     * runtime-provided {@link PdfRender} over the canonical PDF codec. Degrades to a clear message
     * when no PDF renderer/codec is available (the optional {@code tesseraql-pdf} module is absent).
     */
    private RenderResult renderPdfRoute(io.tesseraql.yaml.model.ExportSpec export, Path routeDir,
            Map<String, Object> context, PdfRender pdfRender) {
        if (pdfRender == null) {
            return RenderResult.invalid("pdf",
                    "PDF preview is unavailable (the tesseraql-pdf module is not loaded).");
        }
        byte[] pdf;
        try {
            pdf = pdfRender.render(export, routeDir, sampleRows(context));
        } catch (RuntimeException ex) {
            return RenderResult.invalid("pdf", rootMessage(ex));
        }
        if (pdf == null) {
            return RenderResult.invalid("pdf",
                    "PDF preview needs the tesseraql-pdf module on the classpath.");
        }
        return RenderResult.ok("pdf", "data:application/pdf;base64,"
                + java.util.Base64.getEncoder().encodeToString(pdf));
    }

    /** The sample's {@code sql.rows} as the export route's query rows, or empty. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sampleRows(Map<String, Object> context) {
        if (context.get("sql") instanceof Map<?, ?> sql
                && sql.get("rows") instanceof List<?> rows) {
            return (List<Map<String, Object>>) (List<?>) rows;
        }
        return List.of();
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
            EvaluationContext evaluation, Map<String, Object> context, FieldMask fieldMask) {
        Object body = resolveJson(json.body(), evaluation);
        // Output-field masking (Studio backlog A1 follow-up): the runtime supplies the mask over the
        // canonical FieldPolicyApplier, evaluated for the sample principal, so the preview shows what
        // a caller would actually see — hidden/redacted fields included.
        if (fieldMask != null && !json.fields().isEmpty()) {
            try {
                body = fieldMask.mask(json.fields(), body, context);
            } catch (RuntimeException ex) {
                return RenderResult.invalid("json", "Field masking: " + rootMessage(ex));
            }
        }
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
     * separately: since Phase 42 the hot reloader also mounts new routes, so applying serves immediately
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
     * creating it (applying serves it immediately — routes hot-reload since Phase 42). Rejected
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

    /** The next versioned-migration number for a datasource/vendor (the Flyway {@code V<n>} prefix). */
    public String nextMigrationVersion(String datasource, String vendor) {
        return nextVersion(migrationDir(identifier(datasource, "main"), normalVendor(vendor)));
    }

    /**
     * Creates a new Flyway migration under {@code db/…/migration[-vendor]} (Studio backlog: migration
     * authoring). A versioned migration ({@code repeatable == false}) is auto-numbered {@code V<n>}
     * from the existing files (plain sequential, no zero-padding — the framework orders versions
     * numerically); a repeatable one is {@code R__<slug>}. The DDL is written verbatim (a placeholder
     * when blank). Gated by the read-only master switch and recorded to the audit trail; apply it with
     * the migration page's Migrate now (roadmap Phase 42) or the next start's migrate. Refuses to
     * overwrite an existing file unless {@code force}.
     */
    public MigrationResult createMigration(String datasource, String vendor, boolean repeatable,
            String description, String ddl, boolean force, String actor) {
        if (readOnly) {
            throw new TqlException(READ_ONLY,
                    "Studio is read-only; creating migrations is disabled");
        }
        String dir = migrationDir(identifier(datasource, "main"), normalVendor(vendor));
        String version = repeatable ? null : nextVersion(dir);
        String slug = slug(description);
        String filename = repeatable ? "R__" + slug + ".sql" : "V" + version + "__" + slug + ".sql";
        String relativePath = dir + "/" + filename;
        Path target = resolve(relativePath);
        if (Files.isRegularFile(target) && !force) {
            throw new TqlException(CONFLICT, "A migration already exists at " + relativePath
                    + (repeatable
                            ? "; open it in the editor, or use a different description."
                            : "; refresh the page and retry."));
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, migrationBody(ddl, repeatable));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        recordAudit(actor, "migration", relativePath);
        return new MigrationResult(relativePath, filename, version, repeatable);
    }

    /** The app-relative migration directory for a datasource and optional vendor overlay. */
    private static String migrationDir(String datasource, String vendor) {
        String base = "main".equals(datasource) ? "db" : "db/" + datasource;
        return base + "/" + (vendor == null ? "migration" : "migration-" + vendor);
    }

    /** The next {@code V<n>} for a migration directory: one past the highest existing version. */
    private String nextVersion(String dirRelative) {
        Path dir = appHome.resolve(dirRelative).normalize();
        int max = 0;
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    MigrationFile parsed = MigrationFile.parse("main", null, file);
                    if (parsed != null && parsed.version() != null) {
                        Matcher matcher = LEADING_DIGITS.matcher(parsed.version());
                        if (matcher.find()) {
                            max = Math.max(max, Integer.parseInt(matcher.group()));
                        }
                    }
                }
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return String.valueOf(max + 1);
    }

    /** Validates a datasource/vendor identifier, defaulting a blank one to {@code fallback}. */
    private static String identifier(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        if (!IDENTIFIER.matcher(trimmed).matches()) {
            throw new TqlException(NEW_ROUTE, "Invalid datasource/vendor name: " + value);
        }
        return trimmed;
    }

    private static String normalVendor(String vendor) {
        return vendor == null || vendor.isBlank() ? null : identifier(vendor, null);
    }

    /** Slugs a description into the Flyway {@code __<description>} segment (underscore-separated). */
    private static String slug(String description) {
        String slug = description == null
                ? ""
                : description.strip().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) {
            throw new TqlException(NEW_ROUTE, "A migration needs a description");
        }
        return slug;
    }

    /** The migration file body: the supplied DDL, or a kind-appropriate placeholder when blank. */
    private static String migrationBody(String ddl, boolean repeatable) {
        String body = ddl == null ? "" : ddl.strip();
        if (body.isEmpty()) {
            body = repeatable
                    ? "-- Repeatable migration: redefine idempotently, e.g. CREATE OR REPLACE VIEW ..."
                    : "-- TODO: write the migration DDL.";
        }
        return body + "\n";
    }

    /**
     * The outcome of creating a migration: its app-relative path, filename, the assigned version
     * ({@code null} for a repeatable migration), and whether it is repeatable.
     */
    public record MigrationResult(String path, String filename, String version,
            boolean repeatable) {
    }

    /** Whether {@code path} is a Flyway migration under a {@code db/…/migration[-vendor]} location. */
    public static boolean isMigrationPath(String path) {
        return path != null && MIGRATION_PATH.matcher(path).matches();
    }

    /**
     * Dry-runs a migration's DDL against the dev datasource without persisting it (Studio backlog:
     * migration authoring). Studio stays database-free — the runtime supplies the execution over the
     * sandboxed (auto-rollback) datasource via a {@link DdlDryRun} callback.
     */
    @FunctionalInterface
    public interface DdlDryRun {
        /** Executes {@code ddl} in an auto-rollback sandbox and reports whether it applied cleanly. */
        DryRunResult run(String ddl);
    }

    /**
     * The outcome of a migration dry-run: whether it actually ran ({@code false} when declined — e.g.
     * a non-Postgres dialect whose DDL can't be rolled back), whether the DDL applied cleanly, and a
     * human-readable message.
     */
    public record DryRunResult(boolean ran, boolean ok, String message) {

        /** The DDL applied cleanly in the sandbox and was rolled back. */
        public static DryRunResult applied() {
            return new DryRunResult(true, true,
                    "Applies cleanly (rolled back — nothing persisted).");
        }

        /** The DDL ran but failed with {@code message}. */
        public static DryRunResult failed(String message) {
            return new DryRunResult(true, false, message);
        }

        /** The dry-run was declined (e.g. an unsupported dialect or no DDL), with the reason. */
        public static DryRunResult declined(String message) {
            return new DryRunResult(false, false, message);
        }
    }

    /**
     * Dry-runs the DDL of the migration at {@code relativePath} — the supplied {@code content} when
     * present (the live editor buffer), otherwise the saved file — against the sandbox via
     * {@code dryRun}, returning the outcome. Declines a non-migration path. It never persists, so it
     * is safe regardless of the read-only switch; the caller gates it like the test runner.
     */
    public DryRunResult dryRunMigration(String relativePath, String content, DdlDryRun dryRun) {
        if (!isMigrationPath(relativePath)) {
            return DryRunResult
                    .declined("Dry-run applies to migration files (db/…/migration/*.sql).");
        }
        String ddl = content != null ? content : sourceIfExists(relativePath);
        return dryRun.run(ddl);
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
        // Tolerant of unparseable route documents (they surface through the hot reloader's
        // failure report; the explorer keeps showing everything that still parses).
        this.manifest = new ManifestLoader().load(appHome, new java.util.ArrayList<>());
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

    /** The outcome of applying every clean pending draft at once (Studio Drafts bulk actions). */
    public record BulkApplyResult(int applied, int skipped, boolean needsRestart) {
    }

    /**
     * Applies every pending draft that does not conflict, recording each to the audit trail as
     * {@code actor} (Studio Drafts bulk actions). Conflicting drafts are left untouched (skipped) —
     * they need a manual diff review in the editor — and counted. {@code needsRestart} is true when
     * any applied draft created a not-yet-served route file. Callers reload routes afterwards.
     */
    public BulkApplyResult applyAllDrafts(String actor) {
        int applied = 0;
        int skipped = 0;
        boolean needsRestart = false;
        for (DraftSummary draft : drafts()) {
            if (draft.conflict()) {
                skipped++;
                continue;
            }
            boolean isNew = draft.isNew();
            try {
                applyDraft(draft.path(), false, actor);
                applied++;
                needsRestart = needsRestart || isNew;
            } catch (RuntimeException ex) {
                // Best-effort: a draft that will not apply cleanly (e.g. a conflict that appeared
                // between the snapshot and here) is left for manual review, not fatal to the batch.
                skipped++;
            }
        }
        return new BulkApplyResult(applied, skipped, needsRestart);
    }

    /** Discards every pending draft, returning how many were removed (Studio Drafts bulk actions). */
    public int discardAllDrafts() {
        int discarded = 0;
        for (DraftSummary draft : drafts()) {
            if (deleteDraft(draft.path())) {
                discarded++;
            }
        }
        return discarded;
    }

    /**
     * The audit trail (Studio backlog D6): who applied or scaffolded what, when — newest first, up to
     * {@code limit} entries. The trail is the append-only {@code work/studio/audit/audit.jsonl} log
     * the source-writing operations stamp; an empty list when the log is absent.
     */
    public List<AuditEntry> auditEntries(int limit) {
        return auditEntries(limit, null);
    }

    /**
     * Records a Studio-triggered migrate run to the audit trail (roadmap Phase 42): the runtime
     * owns the datasources and runs the migration, Studio owns the trail.
     */
    public void recordMigrationRun(String actor, String target) {
        recordAudit(actor, "migrate", target);
    }

    /**
     * The newest {@code limit} audit entries matching {@code query} (Studio platform-UX H5). The
     * filter runs over the <em>whole</em> log before the limit applies, so a search reaches older
     * actions, not just the newest window; an empty query returns the newest {@code limit} entries.
     */
    public List<AuditEntry> auditEntries(int limit, String query) {
        List<AuditEntry> entries = filteredAuditNewestFirst(query);
        return entries.size() > limit ? List.copyOf(entries.subList(0, limit)) : entries;
    }

    /** The sortable columns of the audit trail (Studio platform-UX I2). */
    public static final Set<String> AUDIT_SORT_COLS = Set.of("at", "actor", "action", "target");

    public AuditPage auditPage(String query, int page, int size) {
        return auditPage(query, null, null, page, size);
    }

    /**
     * One page of the audit trail matching {@code query} (Studio platform-UX I3 + I2 sort): the whole
     * log is filtered, then sorted by {@code sort} (one of {@link #AUDIT_SORT_COLS}; default {@code at}
     * newest-first), then sliced to the {@code page}-th 1-based page of {@code size}. The filtered
     * {@code total} comes back so the view can render pagination.
     */
    public AuditPage auditPage(String query, String sort, String dir, int page, int size) {
        int p = Math.max(1, page);
        List<AuditEntry> all = new ArrayList<>(filteredAuditNewestFirst(query));
        boolean explicit = sort != null && AUDIT_SORT_COLS.contains(sort);
        String key = explicit ? sort : "at";
        // No explicit sort means the default newest-first (at desc); an explicit column defaults asc.
        boolean desc = explicit ? "desc".equalsIgnoreCase(dir) : true;
        Comparator<AuditEntry> cmp = auditComparator(key);
        all.sort(desc ? cmp.reversed() : cmp);
        int total = all.size();
        int from = Math.min((p - 1) * size, total);
        int to = Math.min(from + size, total);
        return new AuditPage(List.copyOf(all.subList(from, to)), p, size, total);
    }

    private static Comparator<AuditEntry> auditComparator(String key) {
        return switch (key) {
            case "actor" -> Comparator.comparing(e -> e.actor().toLowerCase(java.util.Locale.ROOT));
            case "action" ->
                Comparator.comparing(e -> e.action().toLowerCase(java.util.Locale.ROOT));
            case "target" ->
                Comparator.comparing(e -> e.target().toLowerCase(java.util.Locale.ROOT));
            default -> Comparator.comparing(AuditEntry::at); // "at": ISO timestamps sort lexically
        };
    }

    /** Every audit entry matching {@code query} (whole log), newest first. */
    private List<AuditEntry> filteredAuditNewestFirst(String query) {
        Path log = auditLog();
        if (!Files.isRegularFile(log)) {
            return List.of();
        }
        String q = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        List<AuditEntry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(log)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = jsonMapper.readTree(line);
                AuditEntry entry = new AuditEntry(node.path("at").asText(""),
                        node.path("actor").asText(""),
                        node.path("action").asText(""), node.path("target").asText(""));
                if (q.isEmpty() || matchesAudit(entry, q)) {
                    entries.add(entry);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        java.util.Collections.reverse(entries);
        return entries;
    }

    private static boolean matchesAudit(AuditEntry entry, String lowerQuery) {
        return entry.actor().toLowerCase(java.util.Locale.ROOT).contains(lowerQuery)
                || entry.action().toLowerCase(java.util.Locale.ROOT).contains(lowerQuery)
                || entry.target().toLowerCase(java.util.Locale.ROOT).contains(lowerQuery)
                || entry.at().toLowerCase(java.util.Locale.ROOT).contains(lowerQuery);
    }

    /** The app's current declarative sidebar menu items ({@code config/menu.yml}); empty if none. */
    public List<MenuItem> menuItems() {
        return MenuSpec.load(appHome).items();
    }

    /** Lints the app home for the Studio health dashboard (the same engine as the CLI/Maven lint). */
    public List<LintFinding> health() {
        return new AppLinter().lint(appHome);
    }

    /** Distinct roles named across the app's {@code tesseraql.security.policies} (menu autocomplete). */
    public List<String> knownRoles() {
        return policyValues("role");
    }

    /** Distinct permissions named across the app's security policies (menu autocomplete). */
    public List<String> knownPermissions() {
        return policyValues("permission");
    }

    /** Distinct {@code role}/{@code permission} values across every policy's {@code anyOf} rules. */
    private List<String> policyValues(String key) {
        Object policies = manifest.config().navigate("tesseraql.security.policies");
        if (!(policies instanceof Map<?, ?> byId)) {
            return List.of();
        }
        java.util.TreeSet<String> values = new java.util.TreeSet<>();
        for (Object policy : byId.values()) {
            if (policy instanceof Map<?, ?> spec && spec.get("anyOf") instanceof List<?> rules) {
                for (Object rule : rules) {
                    if (rule instanceof Map<?, ?> r && r.get(key) != null) {
                        String value = String.valueOf(r.get(key)).strip();
                        if (!value.isEmpty()) {
                            values.add(value);
                        }
                    }
                }
            }
        }
        return List.copyOf(values);
    }

    /** The distinct HTTP paths the app serves — href autocomplete + the dangling-href check. */
    public List<String> routePaths() {
        java.util.TreeSet<String> paths = new java.util.TreeSet<>();
        for (RouteFile route : manifest.routes()) {
            if (route.urlPath() != null && !route.urlPath().isBlank()) {
                paths.add(route.urlPath());
            }
        }
        return List.copyOf(paths);
    }

    /**
     * Prefill values for the API console when it is deep-linked with {@code ?path=&method=} (e.g. from
     * a route's docs page): the resolved method + path, plus a skeleton built from the matched route's
     * declared inputs — a JSON body for a body method, or a query string for a read method.
     */
    public Map<String, Object> tryPrefill(String method, String path) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (path == null || path.isBlank()) {
            return out;
        }
        out.put("path", path);
        String wanted = method == null ? null : method.strip();
        RouteFile match = null;
        for (RouteFile route : manifest.routes()) {
            if (path.equals(route.urlPath())
                    && (wanted == null || wanted.equalsIgnoreCase(route.httpMethod()))) {
                match = route;
                break;
            }
        }
        if (match == null) {
            if (wanted != null) {
                out.put("method", wanted.toUpperCase(java.util.Locale.ROOT));
            }
            return out;
        }
        String httpMethod = match.httpMethod().toUpperCase(java.util.Locale.ROOT);
        out.put("method", httpMethod);
        java.util.LinkedHashMap<String, Object> writable = new java.util.LinkedHashMap<>();
        match.definition().input().forEach((name, field) -> {
            if (field.isWritable()) {
                writable.put(name, inputExample(field.type()));
            }
        });
        if (writable.isEmpty()) {
            return out;
        }
        boolean bodyMethod = !("GET".equals(httpMethod) || "HEAD".equals(httpMethod)
                || "DELETE".equals(httpMethod) || "OPTIONS".equals(httpMethod));
        if (bodyMethod) {
            try {
                out.put("body", jsonMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(writable));
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // Skip the body skeleton if it can't be serialized (never expected for scalars).
            }
        } else {
            out.put("query", writable.keySet().stream()
                    .map(name -> name + "=").collect(java.util.stream.Collectors.joining("&")));
        }
        return out;
    }

    /** A placeholder example value for an input type, used in the API-console body skeleton. */
    private static Object inputExample(String type) {
        return switch (type == null ? "string" : type) {
            case "integer", "number" -> 0;
            case "boolean" -> false;
            default -> "";
        };
    }

    /** The app's message-catalog locale tags ({@code messages/<tag>.yml}), tag-sorted. */
    public List<String> messageLocales() {
        return new ArrayList<>(MessageCatalog.load(appHome.resolve("messages")).tags());
    }

    /** Each locale's flat key→value message entries (dotted keys), for the i18n editor table. */
    public Map<String, Map<String, String>> messageCatalogs() {
        MessageCatalog catalog = MessageCatalog.load(appHome.resolve("messages"));
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (String tag : catalog.tags()) {
            out.put(tag, catalog.entries(tag));
        }
        return out;
    }

    /**
     * Upserts a translation into {@code messages/<locale>.yml} — the dotted {@code key} is written
     * into the nested map, other keys preserved, creating the file/locale if new. Edit-gated and
     * audited; the message resolver reads the catalog live, so the change is served immediately.
     */
    public void setMessage(String locale, String key, String value, String actor) {
        String tag = requireLocaleTag(locale);
        String messageKey = requireMessageKey(key);
        if (readOnly) {
            throw new TqlException(READ_ONLY, "Studio is read-only; editing messages is disabled");
        }
        Path file = resolve("messages/" + tag + ".yml");
        Map<String, Object> tree = Files.isRegularFile(file)
                ? mutableCopy(parser.parseTree(file))
                : new LinkedHashMap<>();
        String[] segments = messageKey.split("\\.");
        Map<String, Object> node = tree;
        for (int i = 0; i < segments.length - 1; i++) {
            node = childMap(node, segments[i]);
        }
        node.put(segments[segments.length - 1], value == null ? "" : value);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, parser.write(tree));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        recordAudit(actor, "message", "messages/" + tag + ".yml");
    }

    /**
     * The app's effective (merged) configuration flattened to dotted-key rows for the Studio config
     * viewer, sorted by key. Values are shown unresolved (so {@code ${ENV}} references stay visible,
     * not their secret values); a value whose key names a secret is redacted unless it is such a
     * reference.
     */
    public List<Map<String, Object>> effectiveConfig() {
        Map<String, Object> root = new ManifestLoader().load(appHome).config().root();
        java.util.TreeMap<String, Object> flat = new java.util.TreeMap<>();
        flattenConfig("", root, flat);
        List<Map<String, Object>> rows = new ArrayList<>();
        flat.forEach((key, value) -> {
            String rendered = value == null ? "" : String.valueOf(value);
            boolean secret = isSecretKey(key) && !rendered.startsWith("${");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("value", secret ? "••••••" : rendered);
            row.put("secret", secret);
            rows.add(row);
        });
        return rows;
    }

    private static void flattenConfig(String prefix, Object node, Map<String, Object> out) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> flattenConfig(
                    prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k, v, out));
        } else if (node instanceof List<?> list) {
            out.put(prefix, list.toString());
        } else {
            out.put(prefix, node);
        }
    }

    /** One editable configuration setting: its dotted key, label, input type, and help text. */
    public record ConfigSetting(String key, String label, String type, String help) {
    }

    /**
     * The curated set of settings the Studio config editor may change. Deliberately limited to safe,
     * scalar, restart-to-apply keys — engine-critical sections (datasources, camel, security auth)
     * are never editable here.
     */
    private static final List<ConfigSetting> EDITABLE_SETTINGS = List.of(
            new ConfigSetting("tesseraql.app.name", "App name", "string",
                    "Shown in the app chrome."),
            new ConfigSetting("tesseraql.i18n.defaultLocale", "Default locale", "string",
                    "BCP-47 tag, e.g. en."),
            new ConfigSetting("tesseraql.outbox.dispatch.fixedDelay", "Outbox dispatch delay",
                    "string", "e.g. 5s; empty disables the dispatcher."),
            new ConfigSetting("tesseraql.outbox.dispatch.maxAttempts", "Outbox max attempts",
                    "integer", "Delivery attempts before an outbox row is parked."),
            new ConfigSetting("tesseraql.retention.sweep", "Retention sweep interval", "string",
                    "e.g. 1h; empty disables retention."),
            new ConfigSetting("tesseraql.retention.outbox", "Outbox retention", "string",
                    "e.g. 30d."),
            new ConfigSetting("tesseraql.retention.jobs", "Job retention", "string", "e.g. 90d."));

    /** The curated editable settings with their current effective values, for the config editor. */
    public List<Map<String, Object>> editableSettings() {
        AppConfig config = new ManifestLoader().load(appHome).config();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConfigSetting setting : EDITABLE_SETTINGS) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", setting.key());
            row.put("label", setting.label());
            row.put("type", setting.type());
            row.put("help", setting.help());
            row.put("value", config.getString(setting.key()).orElse(""));
            out.add(row);
        }
        return out;
    }

    /**
     * Overrides a curated setting in {@code config/overlay.yml} (the base config untouched), or, when
     * {@code value} is blank, removes the override. Only whitelisted keys are accepted. Edit-gated and
     * audited; applied on the next restart (the setting is read at startup).
     */
    public void setConfigValue(String key, String value, String actor) {
        ConfigSetting setting = EDITABLE_SETTINGS.stream().filter(s -> s.key().equals(key))
                .findFirst().orElseThrow(() -> new TqlException(CONFIG,
                        "Not an editable setting: " + key));
        if (readOnly) {
            throw new TqlException(READ_ONLY, "Studio is read-only; editing config is disabled");
        }
        String trimmed = value == null ? "" : value.strip();
        if ("integer".equals(setting.type()) && !trimmed.isEmpty()) {
            try {
                Long.parseLong(trimmed);
            } catch (NumberFormatException ex) {
                throw new TqlException(CONFIG, setting.label() + " must be a whole number");
            }
        }
        Path overlay = resolve("config/overlay.yml");
        Map<String, Object> tree = Files.isRegularFile(overlay)
                ? mutableCopy(parser.parseTree(overlay))
                : new LinkedHashMap<>();
        String[] segments = key.split("\\.");
        if (trimmed.isEmpty()) {
            removePath(tree, segments, 0);
        } else {
            Map<String, Object> node = tree;
            for (int i = 0; i < segments.length - 1; i++) {
                node = childMap(node, segments[i]);
            }
            node.put(segments[segments.length - 1], trimmed);
        }
        try {
            Files.createDirectories(overlay.getParent());
            Files.writeString(overlay, parser.write(tree));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        recordAudit(actor, "config", "config/overlay.yml");
    }

    /** Removes the leaf at the dotted path from {@code node}, pruning any emptied ancestor maps. */
    @SuppressWarnings("unchecked")
    private static void removePath(Map<String, Object> node, String[] segments, int index) {
        if (index == segments.length - 1) {
            node.remove(segments[index]);
            return;
        }
        if (node.get(segments[index]) instanceof Map<?, ?> child) {
            Map<String, Object> childMap = (Map<String, Object>) child;
            removePath(childMap, segments, index + 1);
            if (childMap.isEmpty()) {
                node.remove(segments[index]);
            }
        }
    }

    /** Whether a dotted config key names a secret whose literal value should be redacted. */
    private static boolean isSecretKey(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("password") || lower.contains("passphrase")
                || lower.contains("secret") || lower.contains("token")
                || lower.contains("credential") || lower.contains("apikey")
                || lower.contains("privatekey");
    }

    /** The app's live feature flags ({@code config/flags.yml}) — name to (typed) value. */
    public Map<String, Object> flags() {
        return FlagsSpec.load(appHome).values();
    }

    /**
     * Sets (or adds) a feature flag in {@code config/flags.yml}, coercing the value by {@code type}
     * ({@code boolean}/{@code number}/{@code string}). Edit-gated and audited; served live (the
     * request binder reads flags live), so the change takes effect on the next request.
     */
    public void setFlag(String name, String value, String type, String actor) {
        String key = requireFlagName(name);
        Object typed = coerceFlag(type, value);
        Map<String, Object> values = new LinkedHashMap<>(FlagsSpec.load(appHome).values());
        values.put(key, typed);
        writeFlags(values, actor);
    }

    /** Removes a feature flag; a no-op when it is not set. */
    public void removeFlag(String name, String actor) {
        Map<String, Object> values = new LinkedHashMap<>(FlagsSpec.load(appHome).values());
        if (values.remove(name) != null) {
            writeFlags(values, actor);
        }
    }

    private void writeFlags(Map<String, Object> values, String actor) {
        if (readOnly) {
            throw new TqlException(READ_ONLY, "Studio is read-only; editing flags is disabled");
        }
        Path file = resolve("config/flags.yml");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, FlagsSpec.toYaml(values));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        recordAudit(actor, "flag", "config/flags.yml");
    }

    private static String requireFlagName(String name) {
        String trimmed = trimToNull(name);
        if (trimmed == null || !POLICY_ID.matcher(trimmed).matches()) {
            throw new TqlException(FLAG, "Invalid flag name: " + name);
        }
        return trimmed;
    }

    private static Object coerceFlag(String type, String value) {
        String raw = value == null ? "" : value.strip();
        return switch (type == null ? "string" : type) {
            case "boolean" -> Boolean.parseBoolean(raw);
            case "number" -> {
                try {
                    yield raw.contains(".") ? Double.parseDouble(raw) : Long.parseLong(raw);
                } catch (NumberFormatException ex) {
                    throw new TqlException(FLAG, "Flag value must be a number: " + value);
                }
            }
            default -> raw;
        };
    }

    private static String requireLocaleTag(String locale) {
        String trimmed = trimToNull(locale);
        if (trimmed == null || !LOCALE_TAG.matcher(trimmed).matches()) {
            throw new TqlException(MESSAGE, "Invalid locale tag: " + locale);
        }
        return trimmed;
    }

    private static String requireMessageKey(String key) {
        String trimmed = trimToNull(key);
        if (trimmed == null || !MESSAGE_KEY.matcher(trimmed).matches()
                || trimmed.startsWith(".") || trimmed.endsWith(".")) {
            throw new TqlException(MESSAGE, "Invalid message key: " + key);
        }
        return trimmed;
    }

    /**
     * Every route's security posture for the Studio security overview: its auth type, policy, source,
     * and the two governance flags — {@code unprotected} (no auth declared) and {@code csrfGap} (a
     * state-changing browser route without CSRF).
     */
    public List<Map<String, Object>> routeSecurity() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RouteFile route : manifest.routes()) {
            io.tesseraql.yaml.model.SecuritySpec security = route.definition().security();
            String auth = security == null ? null : security.auth();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", route.definition().id());
            row.put("method", route.httpMethod());
            row.put("path", route.urlPath());
            row.put("auth", auth);
            row.put("policy", security == null ? null : security.policy());
            row.put("source", relative(route.source()));
            row.put("unprotected", auth == null || auth.isBlank());
            boolean stateChanging = !"GET".equalsIgnoreCase(route.httpMethod())
                    && !"HEAD".equalsIgnoreCase(route.httpMethod())
                    && !"OPTIONS".equalsIgnoreCase(route.httpMethod());
            boolean csrfOn = security != null && Boolean.TRUE.equals(security.csrf());
            row.put("csrfGap", "browser".equals(auth) && stateChanging && !csrfOn);
            out.add(row);
        }
        return out;
    }

    /**
     * The app's authorization policies ({@code tesseraql.security.policies}), each with a readable
     * summary of its {@code anyOf} rules (roles / permissions / claims), sorted by id.
     */
    public List<Map<String, Object>> securityPolicies() {
        Object policies = manifest.config().navigate("tesseraql.security.policies");
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(policies instanceof Map<?, ?> byId)) {
            return out;
        }
        java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>();
        byId.forEach((id, spec) -> sorted.put(String.valueOf(id), spec));
        sorted.forEach((id, spec) -> {
            List<String> tokens = new ArrayList<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            if (spec instanceof Map<?, ?> map && map.get("anyOf") instanceof List<?> rules) {
                for (Object rule : rules) {
                    if (rule instanceof Map<?, ?> r) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        if (r.get("role") != null) {
                            row.put("kind", "role");
                            row.put("value", String.valueOf(r.get("role")));
                        } else if (r.get("permission") != null) {
                            row.put("kind", "permission");
                            row.put("value", String.valueOf(r.get("permission")));
                        } else if (r.get("claimName") != null) {
                            row.put("kind", "claim");
                            row.put("value", r.get("claimName") + "=" + r.get("claimValue"));
                        } else {
                            continue;
                        }
                        row.put("label", row.get("kind") + " " + row.get("value"));
                        // Claims are shown but not individually removable from the editor (only role/
                        // permission rules are edited here).
                        row.put("editable", !"claim".equals(row.get("kind")));
                        tokens.add(String.valueOf(row.get("label")));
                        rows.add(row);
                    }
                }
            }
            Map<String, Object> policy = new LinkedHashMap<>();
            policy.put("id", id);
            policy.put("rules", rows);
            policy.put("summary", tokens.isEmpty() ? "(no rules)" : String.join(" OR ", tokens));
            out.add(policy);
        });
        return out;
    }

    /**
     * Grants a policy an extra {@code role} or {@code permission} rule by writing the policy's full
     * rule set to {@code config/overlay.yml} (the last-merged overlay, so the base config is left
     * intact). A previously undefined policy is created. Edit-gated and audited; the caller reloads
     * the security engine so the change is live.
     */
    public void addPolicyRule(String policyId, String kind, String value, String actor) {
        String id = requirePolicyId(policyId);
        String ruleKind = requireRuleKind(kind);
        String ruleValue = trimToNull(value);
        if (ruleValue == null) {
            throw new TqlException(POLICY, "A policy rule needs a " + ruleKind + " value");
        }
        List<Map<String, Object>> rules = effectivePolicyRules(id);
        boolean present = rules.stream()
                .anyMatch(r -> ruleValue.equals(String.valueOf(r.get(ruleKind))));
        if (!present) {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put(ruleKind, ruleValue);
            rules.add(rule);
            writeOverlayPolicy(id, rules, actor);
        }
    }

    /**
     * Revokes a {@code role}/{@code permission} rule from a policy by writing the reduced rule set to
     * {@code config/overlay.yml} (which overrides the base). Removing the last rule leaves a policy
     * that grants no one (deny-by-default). A base-only policy cannot be deleted via the overlay.
     */
    public void removePolicyRule(String policyId, String kind, String value, String actor) {
        String id = requirePolicyId(policyId);
        String ruleKind = requireRuleKind(kind);
        String ruleValue = trimToNull(value);
        List<Map<String, Object>> rules = effectivePolicyRules(id);
        boolean removed = rules.removeIf(
                r -> ruleValue != null && ruleValue.equals(String.valueOf(r.get(ruleKind))));
        if (removed) {
            writeOverlayPolicy(id, rules, actor);
        }
    }

    /** The effective {@code anyOf} rule maps of a policy from the current merged config. */
    private List<Map<String, Object>> effectivePolicyRules(String policyId) {
        Object policies = new ManifestLoader().load(appHome).config()
                .navigate("tesseraql.security.policies");
        List<Map<String, Object>> out = new ArrayList<>();
        if (policies instanceof Map<?, ?> byId && byId.get(policyId) instanceof Map<?, ?> spec
                && spec.get("anyOf") instanceof List<?> rules) {
            for (Object rule : rules) {
                if (rule instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    out.add(copy);
                }
            }
        }
        return out;
    }

    /** Writes {@code tesseraql.security.policies.<id>.anyOf} into overlay.yml, other keys preserved. */
    private void writeOverlayPolicy(String policyId, List<Map<String, Object>> rules,
            String actor) {
        if (readOnly) {
            throw new TqlException(READ_ONLY, "Studio is read-only; editing policies is disabled");
        }
        Path overlay = resolve("config/overlay.yml");
        Map<String, Object> tree = Files.isRegularFile(overlay)
                ? mutableCopy(parser.parseTree(overlay))
                : new LinkedHashMap<>();
        Map<String, Object> policies = childMap(childMap(childMap(tree, "tesseraql"),
                "security"), "policies");
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("anyOf", rules);
        policies.put(policyId, policy);
        try {
            Files.createDirectories(overlay.getParent());
            Files.writeString(overlay, parser.write(tree));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        recordAudit(actor, "policy", "config/overlay.yml");
    }

    private static String requirePolicyId(String id) {
        String trimmed = trimToNull(id);
        if (trimmed == null || !POLICY_ID.matcher(trimmed).matches()) {
            throw new TqlException(POLICY, "Invalid policy id: " + id);
        }
        return trimmed;
    }

    private static String requireRuleKind(String kind) {
        String trimmed = trimToNull(kind);
        if (!"role".equals(trimmed) && !"permission".equals(trimmed)) {
            throw new TqlException(POLICY, "A policy rule kind must be 'role' or 'permission'");
        }
        return trimmed;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key,
                value instanceof Map ? mutableCopy((Map<String, Object>) value) : value));
        return copy;
    }

    /** Returns {@code parent}'s child map at {@code key}, creating a mutable one when absent. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> childMap(Map<String, Object> parent, String key) {
        Object child = parent.get(key);
        if (child instanceof Map) {
            Map<String, Object> mutable = mutableCopy((Map<String, Object>) child);
            parent.put(key, mutable);
            return mutable;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        parent.put(key, created);
        return created;
    }

    /**
     * Appends a menu item to {@code config/menu.yml} and records it to the audit trail. {@code label}
     * and {@code href} are required; {@code icon} is an optional sprite id; {@code rolesCsv}/
     * {@code permsCsv} are comma-separated visibility lists (empty ⇒ a public item).
     */
    public void addMenuItem(String label, String href, String icon, String rolesCsv,
            String permsCsv, String actor) {
        String cleanLabel = trimToNull(label);
        String cleanHref = trimToNull(href);
        if (cleanLabel == null || cleanHref == null) {
            throw new TqlException(MENU, "A menu item needs a label and an href");
        }
        List<MenuItem> items = new ArrayList<>(menuItems());
        items.add(new MenuItem(cleanLabel, cleanHref, trimToNull(icon), csv(rolesCsv),
                csv(permsCsv)));
        writeMenu(items, actor);
    }

    /** Removes the menu item at {@code index} (no-op when out of range) and records the change. */
    public void removeMenuItem(int index, String actor) {
        List<MenuItem> items = new ArrayList<>(menuItems());
        if (index >= 0 && index < items.size()) {
            items.remove(index);
            writeMenu(items, actor);
        }
    }

    /**
     * Moves the menu item at {@code index} one slot up ({@code delta < 0}) or down
     * ({@code delta > 0}); a move that would leave the list is a no-op.
     */
    public void moveMenuItem(int index, int delta, String actor) {
        List<MenuItem> items = new ArrayList<>(menuItems());
        int target = index + Integer.signum(delta);
        if (index >= 0 && index < items.size() && target >= 0 && target < items.size()) {
            items.add(target, items.remove(index));
            writeMenu(items, actor);
        }
    }

    /** Serializes the menu back to {@code config/menu.yml} (edit-gated) and records the change. */
    private void writeMenu(List<MenuItem> items, String actor) {
        if (readOnly) {
            throw new TqlException(READ_ONLY, "Studio is read-only; editing the menu is disabled");
        }
        Path target = resolve("config/menu.yml");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, MenuSpec.toYaml(items));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        recordAudit(actor, "menu", "config/menu.yml");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Splits a comma-separated field into a trimmed, blank-free list. */
    private static List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip)
                .filter(part -> !part.isEmpty()).toList();
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

    /**
     * The explorer model: the app, its routes and jobs, and the pending drafts (Studio sidebar IA):
     * the tree marks a served entry that has an unsaved draft and surfaces a new (not-yet-served)
     * draft as its own pending node, so "what I am authoring" is visible alongside "what is served".
     */
    public record Explorer(String appName, boolean readOnly,
            List<RouteSummary> routes, List<JobSummary> jobs, List<DraftSummary> drafts) {
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

    /** One page of the audit trail: its entries plus the page coordinates and filtered total (I3). */
    public record AuditPage(List<AuditEntry> entries, int page, int size, int total) {
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
