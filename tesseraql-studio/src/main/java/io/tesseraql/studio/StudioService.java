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
        Path file = resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            throw new TqlException(NOT_FOUND, "No such file: " + relativePath);
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
        org.thymeleaf.TemplateEngine engine = new org.thymeleaf.TemplateEngine();

        org.thymeleaf.templateresolver.ClassLoaderTemplateResolver shared =
                new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver(
                        StudioService.class.getClassLoader());
        shared.setPrefix("tesseraql/templates/");
        shared.setSuffix(".html");
        shared.setTemplateMode(org.thymeleaf.templatemode.TemplateMode.HTML);
        shared.setResolvablePatterns(java.util.Set.of("tql/*"));
        shared.setOrder(1);
        engine.addTemplateResolver(shared);

        org.thymeleaf.templateresolver.FileTemplateResolver files =
                new org.thymeleaf.templateresolver.FileTemplateResolver();
        files.setPrefix(appHome.toString() + java.io.File.separator);
        files.setTemplateMode(org.thymeleaf.templatemode.TemplateMode.HTML);
        files.setResolvablePatterns(java.util.Set.of("*.html"));
        files.setCheckExistence(true);
        files.setOrder(2);
        engine.addTemplateResolver(files);

        org.thymeleaf.templateresolver.StringTemplateResolver draft =
                new org.thymeleaf.templateresolver.StringTemplateResolver();
        draft.setTemplateMode(relativePath.endsWith(".html")
                ? org.thymeleaf.templatemode.TemplateMode.HTML
                : org.thymeleaf.templatemode.TemplateMode.TEXT);
        draft.setOrder(3);
        engine.addTemplateResolver(draft);

        try {
            engine.process(content, new org.thymeleaf.context.Context());
            return PreviewResult.valid("template", "template parses and renders with an empty model");
        } catch (RuntimeException ex) {
            if (isDataDependent(ex)) {
                return PreviewResult.valid("template",
                        "template parses; full render needs route data (" + rootMessage(ex) + ")");
            }
            return PreviewResult.invalid("template", rootMessage(ex));
        }
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
            throw new TqlException(TRAVERSAL, "Path escapes app home (design ch. 20.2): " + relativePath);
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
        return new JobSummary(job.definition().id(), job.definition().recipe(), relative(job.source()));
    }

    private String relative(Path source) {
        return appHome.relativize(source).toString().replace('\\', '/');
    }

    /** The explorer model: the app and its routes and jobs. */
    public record Explorer(String appName, boolean readOnly,
            List<RouteSummary> routes, List<JobSummary> jobs) {
    }

    /** A route entry in the explorer. */
    public record RouteSummary(String id, String method, String path, String recipe, String source) {
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
}
