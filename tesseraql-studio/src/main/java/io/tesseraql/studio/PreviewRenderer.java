package io.tesseraql.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.studio.StudioService.FieldMask;
import io.tesseraql.studio.StudioService.PdfRender;
import io.tesseraql.studio.StudioService.PreviewResult;
import io.tesseraql.studio.StudioService.RenderResult;
import io.tesseraql.studio.StudioService.RowSource;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.model.ResponseSpec;
import io.tesseraql.yaml.model.RouteDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Does this draft parse, and what does it look like when it runs?
 *
 * <p>Two questions the Studio editor asks of every document it shows: {@code preview} answers the
 * first by handing the text to the parser that owns the path, and {@code render} answers the
 * second by putting a template or route through the same three template resolvers a real response
 * renders through. They are one collaborator because they share all of it — the resolver stack,
 * the sample-model fixture, and the temp-file technique the file-reading parsers need.
 *
 * <p>Extracted from {@code StudioService}, whose public {@code preview}/{@code render} methods
 * now delegate here; the callbacks the runtime supplies ({@link RowSource}, {@link FieldMask},
 * {@link PdfRender}) stay declared on the service, which is what callers import.
 */
final class PreviewRenderer {

    /** TQL-STUDIO-4222: a preview or render could not be produced. */
    private static final TqlErrorCode RENDER = new TqlErrorCode(TqlDomain.STUDIO, 4222);

    private final SimpleYamlParser parser = new SimpleYamlParser();
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final Supplier<Path> appHome;
    private final Supplier<AppManifest> manifest;
    private final Function<String, String> source;
    private final Function<String, String> sourceIfExists;
    private final Function<String, Path> resolve;

    PreviewRenderer(Supplier<Path> appHome, Supplier<AppManifest> manifest,
            Function<String, String> source, Function<String, String> sourceIfExists,
            Function<String, Path> resolve) {
        this.appHome = appHome;
        this.manifest = manifest;
        this.source = source;
        this.sourceIfExists = sourceIfExists;
        this.resolve = resolve;
    }

    /**
     * Compiles a draft (or current source) without applying it, so edits can be validated before
     * they touch the source of truth (design ch. 16.6): every document kind is parsed by the same
     * parser the manifest load uses, SQL is rendered, templates are processed.
     *
     * <p>This used to recognize only {@code .sql}, {@code web/**.yml}, {@code .html} and
     * {@code .tpl}, and answer <em>valid</em> for everything else — so the compile-before-write
     * gate in {@link #applyDraft} was a no-op for shared definitions, jobs, workflows, scopes,
     * attachments, suites, and config, and a broken document was promoted to the source of truth
     * with the screen saying it compiled. Worse, the route branch matched any {@code web/**.yml},
     * so a {@code *.view.yml} was parsed as a route: the check that ran was the wrong one.
     *
     * <p>The parsers read files rather than text, so the document kinds land in a temp file whose
     * name matches the real one and whose path is scrubbed from the message — the same technique
     * {@link #validateDecisionDraft} already used, now shared.
     */
    PreviewResult preview(String relativePath, String content) {
        String text = content != null ? content : source.apply(relativePath);
        if (relativePath.endsWith(".sql")) {
            try {
                BoundSql bound = SqlRenderer.render(Sql2WayParser.parse(text), Map.of());
                return PreviewResult.valid("sql", bound.sql());
            } catch (RuntimeException ex) {
                return PreviewResult.invalid("sql", ex.getMessage());
            }
        }
        // Before the route check: a view document also lives under web/ and is not a route.
        if (relativePath.endsWith(".view.yml")) {
            return previewDocument("view", relativePath, text, file -> {
                io.tesseraql.yaml.view.ViewSpec spec = io.tesseraql.yaml.view.ViewSpec.parse(file);
                return "id=" + spec.id() + ", recipe=" + spec.view();
            });
        }
        // A consumer is a route document too — same parser, different mount.
        if (StudioService.isRouteYaml(relativePath) || isUnder(relativePath, "consume")) {
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
        PreviewResult document = previewDeclaration(relativePath, text);
        return document != null ? document : PreviewResult.valid("text", text);
    }

    /**
     * The parsed sample model — the Studio mail test-send renders the body through
     * {@link #render} and needs the same {@code payload}/{@code event} maps for the
     * subject line's inline template.
     */
    Map<String, Object> sampleModel(String relativePath, String sampleModel) {
        return parseSample(relativePath, sampleModel);
    }

    /**
     * The colocated sample-data fixture for a renderable file — {@code <base>.sample.yml} next to it
     * (e.g. {@code .../table.html} → {@code .../table.sample.yml}, {@code .../get.yml} →
     * {@code .../get.sample.yml}), or null when the file is not renderable or no fixture exists. The
     * fixture is a YAML map: the template's variables for a template, or the execution context
     * ({@code params}, {@code main.rows}, …) for a route. The manifest loader ignores it (only
     * HTTP-method {@code *.yml} files under {@code web/} are routes), so it lives beside its file.
     */
    String sampleFixture(String relativePath) {
        if (!StudioService.isTemplate(relativePath) && !StudioService.isRouteYaml(relativePath)) {
            return null;
        }
        int dot = relativePath.lastIndexOf('.');
        String fixture = relativePath.substring(0, dot) + ".sample.yml";
        return sourceIfExists.apply(fixture);
    }

    /**
     * The declaration kinds the manifest load parses per directory (docs/app-layout.md): each is
     * validated by its own parser, so a draft that cannot load is refused where it is written.
     * Returns null when the path is not a declaration — the caller falls back to plain text.
     */
    private PreviewResult previewDeclaration(String relativePath, String text) {
        if (!relativePath.endsWith(".yml") && !relativePath.endsWith(".yaml")) {
            return null;
        }
        if (isUnder(relativePath, "domains")) {
            return previewDocument("domains", relativePath, text, file -> named("domain",
                    parser.parseDomains(file).domains().keySet()));
        }
        if (isUnder(relativePath, "rules")) {
            return previewDocument("rules", relativePath, text, file -> named("rule set",
                    parser.parseRuleSets(file).rules().keySet()));
        }
        if (isUnder(relativePath, "decisions")) {
            return previewDocument("decisions", relativePath, text, file -> {
                io.tesseraql.yaml.model.DecisionsDocument decisions = parser.parseDecisions(file);
                // Parsing is not enough: a decision compiles its rows, and a bad row is exactly
                // what the author needs told here rather than at the next app load.
                decisions.decisions().forEach(io.tesseraql.yaml.decision.DecisionSets::compile);
                return named("decision", decisions.decisions().keySet());
            });
        }
        if (isUnder(relativePath, "calendars")) {
            return previewDocument("calendars", relativePath, text, file -> named("calendar",
                    parser.parseCalendars(file).calendars().keySet()));
        }
        if (isUnder(relativePath, "batch")) {
            return previewDocument("job", relativePath, text,
                    file -> "id=" + parser.parseJob(file).id());
        }
        if (isUnder(relativePath, "workflow")) {
            return previewDocument("workflow", relativePath, text,
                    file -> "id=" + parser.parseWorkflow(file).id());
        }
        if (isUnder(relativePath, "scope")) {
            return previewDocument("scope", relativePath, text,
                    file -> "id=" + parser.parseScope(file).id());
        }
        if (isUnder(relativePath, "attachments")) {
            return previewDocument("attachment", relativePath, text,
                    file -> "id=" + parser.parseAttachment(file).id());
        }
        // Suites and config get structural validation only: the suite loader lives in
        // tesseraql-test-core, whose GreenMail/Testcontainers dependency tree Studio does not
        // carry, and config is merged from several documents so a single file has no schema of
        // its own. Well-formed YAML is still a real gate — it is what "valid" claimed before.
        if (isUnder(relativePath, "tests") || isUnder(relativePath, "config")) {
            String kind = isUnder(relativePath, "tests") ? "suite" : "config";
            try {
                parser.parseTree(text);
                return PreviewResult.valid(kind, kind + " document is well-formed YAML");
            } catch (RuntimeException ex) {
                return PreviewResult.invalid(kind, StudioService.rootMessage(ex));
            }
        }
        return null;
    }

    /** Whether the path sits under a top-level declaration directory. */
    private static boolean isUnder(String relativePath, String directory) {
        return relativePath.startsWith(directory + "/");
    }

    private static String named(String noun, java.util.Collection<String> names) {
        return names.size() + " " + noun + (names.size() == 1 ? "" : "s")
                + (names.isEmpty() ? "" : ": " + String.join(", ", names));
    }

    /**
     * Parses draft text with a parser that reads files. The temp file keeps the real document's
     * name so a message that quotes it still reads right, and its directory is scrubbed out of
     * the message so the author sees their own path, not a temp one.
     */
    private PreviewResult previewDocument(String kind, String relativePath, String text,
            java.util.function.Function<Path, String> parse) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("tql-preview-");
            String name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
            Path file = directory.resolve(name);
            Files.writeString(file, text);
            try {
                return PreviewResult.valid(kind, parse.apply(file));
            } catch (RuntimeException ex) {
                return PreviewResult.invalid(kind,
                        StudioService.rootMessage(ex).replace(file.toString(), relativePath));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            deleteTemp(directory);
        }
    }

    private static void deleteTemp(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> entries = Files.walk(directory)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
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
                        "template parses; full render needs route data ("
                                + StudioService.rootMessage(ex) + ")");
            }
            return PreviewResult.invalid("template", StudioService.rootMessage(ex));
        }
    }

    /**
     * The rendered output of a template or web route against its sample model — the same three
     * resolvers a real response renders through, so what the author sees is what the route serves.
     *
     * <p>A non-null {@code liveRows} supplies the query results from the dev datasource, a
     * non-null {@code fieldMask} applies a {@code query-json} route's output masking, and a
     * non-null {@code pdfRender} renders a pdf export. Each is supplied by the runtime, which is
     * how Studio stays free of the security, database and PDF stacks.
     */
    RenderResult render(String relativePath, String content, String sampleModel,
            StudioService.RowSource liveRows, StudioService.FieldMask fieldMask,
            StudioService.PdfRender pdfRender) {
        if (StudioService.isTemplate(relativePath)) {
            return renderTemplateFile(relativePath, content, sampleModel);
        }
        if (StudioService.isRouteYaml(relativePath)) {
            return renderRoute(relativePath, content, sampleModel, liveRows, fieldMask, pdfRender);
        }
        return RenderResult.invalid("text",
                "Rendered preview is only available for templates and web routes");
    }

    private RenderResult renderTemplateFile(String relativePath, String content,
            String sampleModel) {
        String text = content != null ? content : source.apply(relativePath);
        Map<String, Object> model;
        try {
            model = parseSample(relativePath, sampleModel);
        } catch (RuntimeException ex) {
            return RenderResult.invalid("sample", "Sample data: " + StudioService.rootMessage(ex));
        }
        return renderTemplateContent(relativePath, text, model);
    }

    private RenderResult renderRoute(String relativePath, String content, String sampleModel,
            RowSource liveRows, FieldMask fieldMask, PdfRender pdfRender) {
        String text = content != null ? content : source.apply(relativePath);
        RouteDefinition definition;
        try {
            definition = parser.parseRoute(text, relativePath);
        } catch (RuntimeException ex) {
            return RenderResult.invalid("route", StudioService.rootMessage(ex));
        }
        Map<String, Object> context;
        try {
            // A mutable copy: live rows are injected as the `sql` key before model resolution.
            context = new LinkedHashMap<>(parseSample(relativePath, sampleModel));
        } catch (RuntimeException ex) {
            return RenderResult.invalid("sample", "Sample data: " + StudioService.rootMessage(ex));
        }
        if (liveRows != null) {
            try {
                Map<String, Object> live = liveRows.rowsFor(definition,
                        resolve.apply(relativePath).getParent(), context);
                if (live != null) {
                    // Each entry is a model key: the main `sql` plus every named query by its name.
                    live.forEach(context::put);
                }
            } catch (RuntimeException ex) {
                return RenderResult.invalid("route", "Live data: " + StudioService.rootMessage(ex));
            }
        }
        io.tesseraql.yaml.model.ExportSpec export = definition.fileExport();
        if (export != null && "pdf".equalsIgnoreCase(export.format())) {
            return renderPdfRoute(export, resolve.apply(relativePath).getParent(), context,
                    pdfRender);
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
     * backlog A1 follow-up): the sample's {@code main.rows} feed the route's PDF, produced by the
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
            return RenderResult.invalid("pdf", StudioService.rootMessage(ex));
        }
        if (pdf == null) {
            return RenderResult.invalid("pdf",
                    "PDF preview needs the tesseraql-pdf module on the classpath.");
        }
        return RenderResult.ok("pdf", "data:application/pdf;base64,"
                + java.util.Base64.getEncoder().encodeToString(pdf));
    }

    /** The sample's {@code main.rows} as the export route's query rows, or empty. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sampleRows(Map<String, Object> context) {
        if (context.get(io.tesseraql.yaml.model.RouteDefinition.MAIN) instanceof Map<?, ?> main
                && main.get("rows") instanceof List<?> rows) {
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
            return RenderResult.invalid("html", StudioService.rootMessage(ex));
        }
        String templateContent = sourceIfExists.apply(templateRel);
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
                return RenderResult.invalid("json",
                        "Field masking: " + StudioService.rootMessage(ex));
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
                : sampleFixture(relativePath);
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
            return RenderResult.invalid(kind, StudioService.rootMessage(ex));
        }
    }

    /**
     * Resolves a route's template like the compiler's {@code TemplateResolution}: colocated next to
     * the route first, then the shared {@code templates/} root; confined to the app home. Returns the
     * app-home-relative path.
     */
    private String resolveRouteTemplate(String routePath, String template) {
        Path home = appHome.get();
        Path routeDir = resolve.apply(routePath).getParent();
        Path colocated = routeDir.resolve(template).normalize();
        Path file = Files.isRegularFile(colocated)
                ? colocated
                : home.resolve("templates").resolve(template).normalize();
        if (!file.startsWith(home)) {
            throw new TqlException(RENDER, "Template escapes app home: " + template);
        }
        return home.relativize(file).toString().replace('\\', '/');
    }

    /**
     * Builds a Thymeleaf engine matching the production stack (design ch. 12) for previewing or
     * rendering a draft string: framework {@code tql/*} fragments resolve from the classpath, sibling
     * {@code *.html} app templates from the app home (so cross-references resolve), and the draft
     * itself from the in-memory string — in HTML mode for {@code .html}, TEXT mode otherwise.
     */
    private org.thymeleaf.TemplateEngine templateEngine(String relativePath) {
        org.thymeleaf.TemplateEngine engine = new org.thymeleaf.TemplateEngine();
        // Shared framework templates use @{/x}; Thymeleaf's own builder refuses a
        // context-relative link outside a web context, so every engine needs this one
        // (docs/base-path.md).
        engine.setLinkBuilder(new io.tesseraql.yaml.template.BasePathLinkBuilder());

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
}
