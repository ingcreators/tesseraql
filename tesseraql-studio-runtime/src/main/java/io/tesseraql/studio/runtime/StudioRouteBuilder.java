package io.tesseraql.studio.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.HttpMounts;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.camel.auth.AuthStep;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.compiler.pipeline.Pipeline;
import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.Step;
import io.tesseraql.runtime.RouteReloader;
import io.tesseraql.security.Principal;
import io.tesseraql.studio.StudioService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.camel.CamelContext;

/**
 * Serves the TesseraQL Studio JSON API under {@code /_tesseraql/studio} (design ch. 16). Endpoints
 * require a bearer principal; mutating endpoints additionally require the caller's
 * {@code tql.studio.edit.<name>} atom (docs/studio-shell.md structural decision 4).
 * The browser UI (explorer, editor, setup wizards) is served by the bundled studio app (ch. 32).
 */
final class StudioRouteBuilder {

    private static final AuthStep AUTH = new AuthStep("authenticate", "bearer", null, null);

    private final ObjectMapper mapper = new ObjectMapper();
    private final StudioService studio;
    private final RouteReloader reloader;
    private final StudioTestService studioTests;
    private final StudioScaffoldService studioScaffold;
    private final StudioEdit studioEdit;
    private final StudioService.FieldMask studioMask;
    private final StudioService.PdfRender studioPdf;

    StudioRouteBuilder(StudioService studio, RouteReloader reloader,
            StudioTestService studioTests, StudioScaffoldService studioScaffold,
            StudioEdit studioEdit, StudioService.FieldMask studioMask,
            StudioService.PdfRender studioPdf) {
        this.studio = studio;
        this.reloader = reloader;
        this.studioTests = studioTests;
        this.studioScaffold = studioScaffold;
        this.studioEdit = studioEdit;
        this.studioMask = studioMask;
        this.studioPdf = studioPdf;
    }

    void install(CamelContext context) {
        Pipelines.Compilation pipelines = Pipelines.of(context)
                .compiling(java.util.List.of(
                        Pipeline.Handler.catching(TqlException.class, new ErrorResponseRenderer()),
                        Pipeline.Handler.catching(Exception.class, new ErrorResponseRenderer())));

        HttpMounts.mount(context, "GET", "/_tesseraql/studio/explorer",
                "studio.explorer");
        HttpMounts.mount(context, "GET", "/_tesseraql/studio/source", "studio.source");
        HttpMounts.mount(context, "GET", "/_tesseraql/studio/drafts", "studio.drafts");
        HttpMounts.mount(context, "POST", "/_tesseraql/studio/drafts", "studio.draft");
        HttpMounts.mount(context, "POST", "/_tesseraql/studio/preview",
                "studio.preview");
        HttpMounts.mount(context, "POST", "/_tesseraql/studio/render", "studio.render");
        HttpMounts.mount(context, "POST", "/_tesseraql/studio/runTests",
                "studio.runTests");
        HttpMounts.mount(context, "GET", "/_tesseraql/studio/scaffold/tables",
                "studio.scaffold.tables");
        HttpMounts.mount(context, "POST", "/_tesseraql/studio/scaffold/preview",
                "studio.scaffold.preview");
        HttpMounts.mount(context, "POST", "/_tesseraql/studio/scaffold/apply",
                "studio.scaffold.apply");
        HttpMounts.mount(context, "GET", "/_tesseraql/studio/audit", "studio.audit");
        HttpMounts.mount(context, "POST", "/_tesseraql/studio/apply", "studio.apply");
        HttpMounts.mount(context, "POST", "/_tesseraql/studio/reload", "studio.reload");

        pipelines.pipeline("studio.explorer")
                .process(AUTH).process(json(exchange -> studio
                        .explorer(exchange.getMessage().getHeader("q", String.class))));

        pipelines.pipeline("studio.source")
                .process(AUTH).process(json(exchange -> {
                    String path = requirePath(exchange);
                    return Map.of("path", path, "content", studio.source(path));
                }));

        // Lists every pending draft with its conflict status (backlog D5 draft overview).
        pipelines.pipeline("studio.drafts")
                .process(AUTH).process(json(exchange -> studio.drafts()));

        pipelines.pipeline("studio.draft")
                .process(AUTH).process(json(exchange -> {
                    studioEdit.requireEdit(permissions(exchange));
                    String path = requirePath(exchange);
                    String content = exchange.getMessage().getBody(String.class);
                    studio.saveDraft(path, content == null ? "" : content);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("saved", path);
                    return result;
                }));

        pipelines.pipeline("studio.preview")
                .process(AUTH).process(json(exchange -> {
                    String path = requirePath(exchange);
                    return studio.preview(path, exchange.getMessage().getBody(String.class));
                }));

        // The render endpoint takes two text inputs (the draft content and the sample model), so it
        // carries a JSON body {content, sampleModel, live} rather than the raw content the others
        // use. live:true runs the route's query through the A2 sandbox for real rows.
        pipelines.pipeline("studio.render")
                .process(AUTH).process(json(exchange -> {
                    String path = requirePath(exchange);
                    com.fasterxml.jackson.databind.JsonNode body = readBody(exchange);
                    boolean live = "true".equals(text(body, "live")) && studioTests.isEnabled();
                    StudioService.RowSource rows = live ? studioTests::liveRows : null;
                    return studio.render(path, text(body, "content"), text(body, "sampleModel"),
                            rows, studioMask, studioPdf);
                }));

        // Runs the route's read-only sql test cases against the dev datasource (backlog A2);
        // returns ran:false with a note when disabled, unknown, or lacking SQL cases.
        pipelines.pipeline("studio.runTests")
                .process(AUTH)
                .process(json(exchange -> studioTests.runForPath(requirePath(exchange))));

        // Lists the dev datasource's tables for the scaffold picker, and previews one table's
        // generated CRUD slice (backlog B3); both return ran/enabled:false notes when disabled.
        pipelines.pipeline("studio.scaffold.tables")
                .process(AUTH)
                .process(json(exchange -> io.tesseraql.studio.StudioViews.scaffoldTables(
                        studioScaffold.tables(), studioScaffold.isEnabled())));

        pipelines.pipeline("studio.scaffold.preview")
                .process(AUTH)
                .process(json(exchange -> io.tesseraql.studio.StudioViews.scaffoldPreview(
                        studioScaffold.preview(requireTable(exchange)))));

        // Writes a table's CRUD slice into the app home (backlog B3), honoring edit detection unless
        // force=true; new route files need a restart, surfaced in the result.
        pipelines.pipeline("studio.scaffold.apply")
                .process(AUTH).process(json(exchange -> {
                    studioEdit.requireEdit(permissions(exchange));
                    Map<String, Object> result = io.tesseraql.studio.StudioViews.scaffoldResult(
                            studioScaffold.apply(requireTable(exchange), flag(exchange, "force"),
                                    actor(exchange)));
                    // The instant loop (roadmap Phase 42): the scaffolded routes mount right away.
                    reloader.reload();
                    return result;
                }));

        // The audit trail (backlog D6): who applied or scaffolded what, when (newest first).
        pipelines.pipeline("studio.audit")
                .process(AUTH).process(json(exchange -> studio.auditEntries(200)));

        pipelines.pipeline("studio.apply")
                .process(AUTH).process(json(exchange -> {
                    studioEdit.requireEdit(permissions(exchange));
                    String path = requirePath(exchange);
                    // The confirm-before-apply gate applies to every apply surface, not only the
                    // editor: an automation that promotes drafts under confirmApply passes
                    // confirm=true (force counts, as it does in the UI). The API used to be
                    // exempt, which made the policy a suggestion.
                    studioEdit.requireConfirm(
                            flag(exchange, "confirm") || flag(exchange, "force"));
                    // force=true overwrites a source that changed under the draft (backlog D5); the
                    // caller is recorded to the audit trail (backlog D6).
                    studio.applyDraft(path, flag(exchange, "force"), actor(exchange));
                    // The instant loop (roadmap Phase 42): applying serves immediately — the
                    // reload mounts new routes and rebuilds kept ones, so no restart follows.
                    RouteReloader.Result reload = reloader.reload();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("applied", path);
                    result.put("added", reload.added());
                    result.put("reloaded", reload.reloaded());
                    result.put("removed", reload.removed());
                    result.put("failed", reload.failed());
                    return result;
                }));

        // The manual reload is the recovery hammer: force rebuilds every kept route even
        // when its sources look unchanged (the automatic apply-path reloads content-diff).
        // It mutates the served route table, so it takes the same edit gate its siblings do —
        // without it, any authenticated caller without the edit atom could still rebuild
        // every route, repeatedly.
        pipelines.pipeline("studio.reload")
                .process(AUTH).process(json(exchange -> {
                    studioEdit.requireEdit(permissions(exchange));
                    RouteReloader.Result reload = reloader.reload(true);
                    // The response keeps the pre-extraction shape: the reload delta plus the
                    // refreshed explorer (the reload listeners have already re-read it).
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("reloaded", reload.reloaded());
                    result.put("added", reload.added());
                    result.put("removed", reload.removed());
                    result.put("failed", reload.failed());
                    result.put("explorer", studio.explorer());
                    return result;
                }));
    }

    private static String requirePath(Exchange exchange) {
        return require(exchange, "path");
    }

    private static String requireTable(Exchange exchange) {
        return require(exchange, "table");
    }

    /** An optional boolean query flag: true only when the header is exactly {@code "true"}. */
    private static boolean flag(Exchange exchange, String name) {
        return "true".equals(exchange.getMessage().getHeader(name, String.class));
    }

    /** The audit actor: the authenticated caller's login id (or subject), or null (backlog D6). */
    private static String actor(Exchange exchange) {
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL, Principal.class);
        if (principal == null) {
            return null;
        }
        return principal.loginId() != null ? principal.loginId() : principal.subject();
    }

    /** The authenticated caller's permission codes, for the edit-atom gate. */
    private static List<String> permissions(Exchange exchange) {
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL, Principal.class);
        return principal == null ? List.of() : principal.permissions();
    }

    private static String require(Exchange exchange, String name) {
        String value = exchange.getMessage().getHeader(name, String.class);
        if (value == null || value.isBlank()) {
            throw new io.tesseraql.core.error.TqlException(
                    new io.tesseraql.core.error.TqlErrorCode(
                            io.tesseraql.core.error.TqlDomain.STUDIO, 4002),
                    "Missing '" + name + "' parameter");
        }
        return value;
    }

    /** Parses the request body as a JSON object, or null when the body is blank or not JSON. */
    private com.fasterxml.jackson.databind.JsonNode readBody(Exchange exchange) {
        String body = exchange.getMessage().getBody(String.class);
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(body);
            return node.isObject() ? node : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
    }

    /** A text field of a JSON object body, or null when absent. */
    private static String text(com.fasterxml.jackson.databind.JsonNode body, String field) {
        if (body == null) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode value = body.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Step json(Function<Exchange, Object> handler) {
        return exchange -> {
            Object result = handler.apply(exchange);
            exchange.getMessage().setHeader(Headers.CONTENT_TYPE,
                    "application/json; charset=utf-8");
            exchange.getMessage().setBody(mapper.writeValueAsString(result));
        };
    }
}
