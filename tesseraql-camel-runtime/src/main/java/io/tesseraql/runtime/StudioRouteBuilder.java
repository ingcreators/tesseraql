package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.studio.StudioService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;

/**
 * Serves the TesseraQL Studio JSON API under {@code /_tesseraql/studio} (design ch. 16). Endpoints
 * require a bearer principal; draft writes additionally require Studio not to be in read-only mode.
 * The browser UI (explorer, editor, setup wizards) is served by the bundled studio app (ch. 32).
 */
final class StudioRouteBuilder extends RouteBuilder {

    private static final String AUTH = "tesseraql-auth:authenticate?auth=bearer";

    private final ObjectMapper mapper = new ObjectMapper();
    private final StudioService studio;
    private final RouteReloader reloader;
    private final StudioTestService studioTests;
    private final StudioScaffoldService studioScaffold;

    StudioRouteBuilder(StudioService studio, RouteReloader reloader,
            StudioTestService studioTests, StudioScaffoldService studioScaffold) {
        this.studio = studio;
        this.reloader = reloader;
        this.studioTests = studioTests;
        this.studioScaffold = studioScaffold;
    }

    @Override
    public void configure() {
        onException(TqlException.class).handled(true).process(new ErrorResponseRenderer());
        onException(Exception.class).handled(true).process(new ErrorResponseRenderer());

        rest().get("/_tesseraql/studio/explorer").to("direct:studio.explorer");
        rest().get("/_tesseraql/studio/source").to("direct:studio.source");
        rest().post("/_tesseraql/studio/drafts").to("direct:studio.draft");
        rest().post("/_tesseraql/studio/preview").to("direct:studio.preview");
        rest().post("/_tesseraql/studio/render").to("direct:studio.render");
        rest().post("/_tesseraql/studio/runTests").to("direct:studio.runTests");
        rest().get("/_tesseraql/studio/scaffold/tables").to("direct:studio.scaffold.tables");
        rest().post("/_tesseraql/studio/scaffold/preview").to("direct:studio.scaffold.preview");
        rest().post("/_tesseraql/studio/scaffold/apply").to("direct:studio.scaffold.apply");
        rest().post("/_tesseraql/studio/apply").to("direct:studio.apply");
        rest().post("/_tesseraql/studio/reload").to("direct:studio.reload");

        from("direct:studio.explorer").routeId("studio.explorer")
                .to(AUTH).process(json(exchange -> studio
                        .explorer(exchange.getMessage().getHeader("q", String.class))));

        from("direct:studio.source").routeId("studio.source")
                .to(AUTH).process(json(exchange -> {
                    String path = requirePath(exchange);
                    return Map.of("path", path, "content", studio.source(path));
                }));

        from("direct:studio.draft").routeId("studio.draft")
                .to(AUTH).process(json(exchange -> {
                    String path = requirePath(exchange);
                    String content = exchange.getMessage().getBody(String.class);
                    studio.saveDraft(path, content == null ? "" : content);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("saved", path);
                    return result;
                }));

        from("direct:studio.preview").routeId("studio.preview")
                .to(AUTH).process(json(exchange -> {
                    String path = requirePath(exchange);
                    return studio.preview(path, exchange.getMessage().getBody(String.class));
                }));

        // The render endpoint takes two text inputs (the draft content and the sample model), so it
        // carries a JSON body {content, sampleModel, live} rather than the raw content the others
        // use. live:true runs the route's query through the A2 sandbox for real rows.
        from("direct:studio.render").routeId("studio.render")
                .to(AUTH).process(json(exchange -> {
                    String path = requirePath(exchange);
                    com.fasterxml.jackson.databind.JsonNode body = readBody(exchange);
                    boolean live = "true".equals(text(body, "live")) && studioTests.isEnabled();
                    StudioService.RowSource rows = live ? studioTests::liveRows : null;
                    return studio.render(path, text(body, "content"), text(body, "sampleModel"),
                            rows);
                }));

        // Runs the route's read-only sql test cases against the dev datasource (backlog A2);
        // returns ran:false with a note when disabled, unknown, or lacking SQL cases.
        from("direct:studio.runTests").routeId("studio.runTests")
                .to(AUTH).process(json(exchange -> studioTests.runForPath(requirePath(exchange))));

        // Lists the dev datasource's tables for the scaffold picker, and previews one table's
        // generated CRUD slice (backlog B3); both return ran/enabled:false notes when disabled.
        from("direct:studio.scaffold.tables").routeId("studio.scaffold.tables")
                .to(AUTH).process(json(exchange -> io.tesseraql.studio.StudioViews.scaffoldTables(
                        studioScaffold.tables(), studioScaffold.isEnabled())));

        from("direct:studio.scaffold.preview").routeId("studio.scaffold.preview")
                .to(AUTH).process(json(exchange -> io.tesseraql.studio.StudioViews.scaffoldPreview(
                        studioScaffold.preview(requireTable(exchange)))));

        // Writes a table's CRUD slice into the app home (backlog B3), honoring edit detection unless
        // force=true; new route files need a restart, surfaced in the result.
        from("direct:studio.scaffold.apply").routeId("studio.scaffold.apply")
                .to(AUTH).process(json(exchange -> io.tesseraql.studio.StudioViews.scaffoldResult(
                        studioScaffold.apply(requireTable(exchange), flag(exchange, "force")))));

        from("direct:studio.apply").routeId("studio.apply")
                .to(AUTH).process(json(exchange -> {
                    String path = requirePath(exchange);
                    studio.applyDraft(path);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("applied", path);
                    return result;
                }));

        from("direct:studio.reload").routeId("studio.reload")
                .to(AUTH).process(json(exchange -> reloader.reload()));
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

    private Processor json(Function<Exchange, Object> handler) {
        return exchange -> {
            Object result = handler.apply(exchange);
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE,
                    "application/json; charset=utf-8");
            exchange.getMessage().setBody(mapper.writeValueAsString(result));
        };
    }
}
