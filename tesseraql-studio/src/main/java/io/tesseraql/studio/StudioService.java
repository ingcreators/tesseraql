package io.tesseraql.studio;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.RouteDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

    private final SimpleYamlParser parser = new SimpleYamlParser();
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
        String appName = manifest.config().getString("tesseraql.app.name").orElse("app");
        List<RouteSummary> routes = manifest.routes().stream()
                .map(this::routeSummary)
                .sorted(java.util.Comparator.comparing(RouteSummary::id))
                .toList();
        List<JobSummary> jobs = manifest.jobs().stream()
                .map(this::jobSummary)
                .sorted(java.util.Comparator.comparing(JobSummary::id))
                .toList();
        return new Explorer(appName, readOnly, routes, jobs);
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
        try {
            Files.createDirectories(draft.getParent());
            Files.writeString(draft, content);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
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
            return Files.deleteIfExists(draftPath(relativePath));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
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
     * Renders a draft (or current source) template against a sample-data model and returns the
     * actual output (design ch. 16.6, Studio backlog A1) — the next step past {@link #preview},
     * which only proves a data-dependent template parses. {@code sampleModel} is a YAML/JSON map of
     * top-level template variables; when blank it falls back to the colocated {@code *.sample.yml}
     * fixture, and failing that an empty model. Only template files ({@code .html}/{@code .tpl})
     * render; the same three-resolver engine as {@link #preview} resolves {@code tql/*} fragments
     * and sibling app templates, so the output matches a real page/fragment/file response.
     */
    public RenderResult render(String relativePath, String content, String sampleModel) {
        if (!isTemplate(relativePath)) {
            return RenderResult.invalid("text",
                    "Rendered preview is only available for .html/.tpl templates");
        }
        String text = content != null ? content : source(relativePath);
        String effectiveSample = sampleModel != null && !sampleModel.isBlank()
                ? sampleModel
                : sampleModel(relativePath);
        Map<String, Object> model;
        try {
            model = parser.parseTree(effectiveSample);
        } catch (RuntimeException ex) {
            return RenderResult.invalid("sample", "Sample data: " + rootMessage(ex));
        }
        String kind = relativePath.endsWith(".html") ? "html" : "text";
        try {
            String output = templateEngine(relativePath)
                    .process(text, new org.thymeleaf.context.Context(java.util.Locale.ENGLISH,
                            model));
            return RenderResult.ok(kind, output);
        } catch (RuntimeException ex) {
            return RenderResult.invalid(kind, rootMessage(ex));
        }
    }

    /**
     * The colocated sample-data fixture for a template — {@code <base>.sample.yml} next to the
     * template (e.g. {@code web/users/.../table.html} → {@code .../table.sample.yml}), or null when
     * the file is not a template or no fixture exists. The fixture is a YAML map of the template's
     * top-level variables; it is ignored by the manifest loader (only HTTP-method {@code *.yml}
     * files under {@code web/} are routes), so it can live beside the template it documents.
     */
    public String sampleModel(String relativePath) {
        if (!isTemplate(relativePath)) {
            return null;
        }
        int dot = relativePath.lastIndexOf('.');
        String fixture = relativePath.substring(0, dot) + ".sample.yml";
        return sourceIfExists(fixture);
    }

    private static boolean isTemplate(String relativePath) {
        return relativePath.endsWith(".html") || relativePath.endsWith(".tpl");
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
        if (readOnly) {
            throw new TqlException(READ_ONLY, "Studio is read-only; apply is disabled");
        }
        String draft = readDraft(relativePath);
        if (draft == null) {
            throw new TqlException(NOT_FOUND, "No draft to apply for: " + relativePath);
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
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
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
