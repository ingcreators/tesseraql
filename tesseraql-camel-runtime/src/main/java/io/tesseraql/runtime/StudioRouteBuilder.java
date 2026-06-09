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
 * Serves the TesseraQL Studio backend under {@code /_tesseraql/studio} (design ch. 16). Endpoints
 * require a bearer principal; draft writes additionally require Studio not to be in read-only mode.
 */
final class StudioRouteBuilder extends RouteBuilder {

    private static final String AUTH = "tesseraql-auth:authenticate?auth=bearer";

    private final ObjectMapper mapper = new ObjectMapper();
    private final StudioService studio;
    private final RouteReloader reloader;

    StudioRouteBuilder(StudioService studio, RouteReloader reloader) {
        this.studio = studio;
        this.reloader = reloader;
    }

    @Override
    public void configure() {
        onException(TqlException.class).handled(true).process(new ErrorResponseRenderer());
        onException(Exception.class).handled(true).process(new ErrorResponseRenderer());

        rest().get("/_tesseraql/studio/explorer").to("direct:studio.explorer");
        rest().get("/_tesseraql/studio/source").to("direct:studio.source");
        rest().post("/_tesseraql/studio/drafts").to("direct:studio.draft");
        rest().post("/_tesseraql/studio/preview").to("direct:studio.preview");
        rest().post("/_tesseraql/studio/apply").to("direct:studio.apply");
        rest().post("/_tesseraql/studio/reload").to("direct:studio.reload");

        from("direct:studio.explorer").routeId("studio.explorer")
                .to(AUTH).process(json(exchange -> studio.explorer()));

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
        String path = exchange.getMessage().getHeader("path", String.class);
        if (path == null || path.isBlank()) {
            throw new io.tesseraql.core.error.TqlException(
                    new io.tesseraql.core.error.TqlErrorCode(
                            io.tesseraql.core.error.TqlDomain.STUDIO, 4002),
                    "Missing 'path' query parameter");
        }
        return path;
    }

    private Processor json(Function<Exchange, Object> handler) {
        return exchange -> {
            Object result = handler.apply(exchange);
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json; charset=utf-8");
            exchange.getMessage().setBody(mapper.writeValueAsString(result));
        };
    }
}
