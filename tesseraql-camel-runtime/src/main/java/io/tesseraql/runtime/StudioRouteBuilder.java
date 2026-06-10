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

        // The explorer/source/save/apply UI is served by the bundled studio app (design ch. 32);
        // this builder keeps the JSON API and the setup wizards.
        rest().get("/_tesseraql/studio/ui/wizard").to("direct:studio.ui.wizards");
        rest().get("/_tesseraql/studio/ui/wizard/{kind}").to("direct:studio.ui.wizardForm");
        rest().post("/_tesseraql/studio/ui/wizard/{kind}").to("direct:studio.ui.wizardSubmit");
        rest().get("/_tesseraql/studio/explorer").to("direct:studio.explorer");
        rest().get("/_tesseraql/studio/source").to("direct:studio.source");
        rest().post("/_tesseraql/studio/drafts").to("direct:studio.draft");
        rest().post("/_tesseraql/studio/preview").to("direct:studio.preview");
        rest().post("/_tesseraql/studio/apply").to("direct:studio.apply");
        rest().post("/_tesseraql/studio/reload").to("direct:studio.reload");

        from("direct:studio.ui.wizards").routeId("studio.ui.wizards")
                .to(AUTH).process(html(exchange ->
                        io.tesseraql.studio.StudioConsole.renderWizardIndex(
                                io.tesseraql.studio.StudioWizards.all())));

        from("direct:studio.ui.wizardForm").routeId("studio.ui.wizardForm")
                .to(AUTH).process(html(exchange ->
                        io.tesseraql.studio.StudioConsole.renderWizardForm(
                                io.tesseraql.studio.StudioWizards.byKind(
                                        exchange.getMessage().getHeader("kind", String.class)))));

        from("direct:studio.ui.wizardSubmit").routeId("studio.ui.wizardSubmit")
                .to(AUTH).process(html(this::wizardSubmit));

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
            throw missingPath();
        }
        return path;
    }

    /** Reads a wizard's form fields, generates its config YAML and renders the result page. */
    private String wizardSubmit(Exchange exchange) {
        String kind = exchange.getMessage().getHeader("kind", String.class);
        io.tesseraql.studio.StudioWizards.Wizard wizard =
                io.tesseraql.studio.StudioWizards.byKind(kind);
        Map<String, String> inputs = new java.util.LinkedHashMap<>();
        for (io.tesseraql.studio.StudioWizards.WizardField field : wizard.fields()) {
            inputs.put(field.name(), formField(exchange, field.name()));
            // Strip the inbound form field so a multi-line value is not echoed as a response header.
            exchange.getMessage().removeHeader(field.name());
        }
        String yaml = io.tesseraql.studio.StudioWizards.generate(kind, inputs);
        return io.tesseraql.studio.StudioConsole.renderWizardResult(wizard, yaml);
    }

    private static io.tesseraql.core.error.TqlException missingPath() {
        return new io.tesseraql.core.error.TqlException(
                new io.tesseraql.core.error.TqlErrorCode(
                        io.tesseraql.core.error.TqlDomain.STUDIO, 4002),
                "Missing 'path' parameter");
    }

    /**
     * Reads a form field from a urlencoded POST. platform-http exposes form fields as exchange
     * headers (which, unlike HTTP wire headers, may contain newlines); otherwise the urlencoded
     * body is parsed.
     */
    private static String formField(Exchange exchange, String name) {
        String header = exchange.getMessage().getHeader(name, String.class);
        if (header != null) {
            return header;
        }
        String body = exchange.getMessage().getBody(String.class);
        if (body == null || body.isBlank()) {
            return null;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = java.net.URLDecoder.decode(
                    pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
            if (name.equals(key)) {
                return java.net.URLDecoder.decode(
                        pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private Processor json(Function<Exchange, Object> handler) {
        return exchange -> {
            Object result = handler.apply(exchange);
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json; charset=utf-8");
            exchange.getMessage().setBody(mapper.writeValueAsString(result));
        };
    }

    private Processor html(Function<Exchange, String> handler) {
        return exchange -> {
            String body = handler.apply(exchange);
            // platform-http exposes form fields as message headers; the multi-line 'content' field
            // would otherwise be echoed back as an (illegal) HTTP response header.
            exchange.getMessage().removeHeader("content");
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/html; charset=utf-8");
            exchange.getMessage().setHeader("Content-Security-Policy",
                    "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'");
            exchange.getMessage().setHeader("X-Content-Type-Options", "nosniff");
            exchange.getMessage().setHeader("X-Frame-Options", "DENY");
            exchange.getMessage().setHeader("Referrer-Policy", "no-referrer");
            exchange.getMessage().setBody(body);
        };
    }
}
