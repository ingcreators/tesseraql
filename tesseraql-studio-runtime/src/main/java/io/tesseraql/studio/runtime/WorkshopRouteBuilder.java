package io.tesseraql.studio.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.HttpMounts;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.camel.auth.AuthStep;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.compiler.pipeline.Pipeline;
import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.service.ServiceProviders;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.CamelContext;

/**
 * A member's workshop API (docs/studio-shell.md structural decision 2):
 * {@code /_tesseraql/studio/data/{op}}, answering the studio shell's delegated calls. Browser
 * session on every route; CSRF on every action; and every call — reads included — refuses a
 * principal without this member's {@code tql.studio.edit} atom with the 404-shaped
 * TQL-STUDIO-4043, so out-of-scope and unknown read identically. The op must be a row of the
 * export table ({@link WorkshopOps}) on its enumerated verb — there is no invoke-any-provider
 * door. The caller's own identity is what authorizes: {@code permissions} and {@code actor}
 * are stamped from the authenticated principal here, never trusted off the wire.
 */
final class WorkshopRouteBuilder {

    private static final AuthStep BROWSER = new AuthStep("authenticate", "browser", null, null);
    private static final AuthStep CSRF = new AuthStep("csrf");

    private final ObjectMapper mapper = new ObjectMapper();
    private final StudioEdit studioEdit;

    WorkshopRouteBuilder(StudioEdit studioEdit) {
        this.studioEdit = studioEdit;
    }

    void install(CamelContext context) {
        Pipelines.Compilation pipelines = Pipelines.of(context)
                .compiling(java.util.List.of(
                        Pipeline.Handler.catching(TqlException.class, new ErrorResponseRenderer()),
                        Pipeline.Handler.catching(Exception.class, new ErrorResponseRenderer())));

        HttpMounts.mount(context, "GET", "/_tesseraql/studio/data/{op}",
                "studio.workshop.read");
        HttpMounts.mount(context, "POST", "/_tesseraql/studio/data/{op}",
                "studio.workshop.act");
        HttpMounts.mount(context, "GET", "/_tesseraql/studio/data/public/{op}",
                "studio.workshop.public");

        pipelines.pipeline("studio.workshop.read")
                .process(BROWSER).process(exchange -> answer(exchange, "GET"));
        pipelines.pipeline("studio.workshop.act")
                .process(BROWSER).process(CSRF).process(exchange -> answer(exchange, "POST"));
        // The token-authorized share providers: no session, no atom — the provider verifies
        // the signed link itself, and only the PUBLIC rows answer here.
        pipelines.pipeline("studio.workshop.public")
                .process(this::answerPublic);
    }

    private void answerPublic(Exchange exchange) throws Exception {
        String op = exchange.getMessage().getHeader("op", String.class);
        if (op == null || !WorkshopOps.PUBLIC.contains(op)) {
            throw WorkshopTargets.notFound(op);
        }
        Map<String, Object> params = new LinkedHashMap<>(StudioSupport.parseQueryString(
                exchange.getMessage().getHeader(Headers.HTTP_QUERY, String.class)));
        respond(exchange, op, params);
    }

    private void answer(Exchange exchange, String verb) throws Exception {
        String op = exchange.getMessage().getHeader("op", String.class);
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL,
                Principal.class);
        java.util.List<String> permissions = principal == null
                ? java.util.List.of()
                : principal.permissions();
        // The atom check, before the table lookup: an unauthorized caller learns nothing
        // about which ops exist.
        if (!studioEdit.canEdit(permissions)) {
            throw WorkshopTargets.notFound(op);
        }
        if (op == null || !verb.equals(WorkshopOps.OPS.get(op))) {
            throw WorkshopTargets.notFound(op);
        }
        Map<String, Object> params = "GET".equals(verb)
                ? new LinkedHashMap<>(StudioSupport.parseQueryString(
                        exchange.getMessage().getHeader(Headers.HTTP_QUERY, String.class)))
                : formParams(exchange);
        // Identity is the member's own verdict, never the wire's.
        params.put("permissions", permissions);
        params.put("actor", principal == null
                ? null
                : principal.loginId() != null ? principal.loginId() : principal.subject());
        respond(exchange, op, params);
    }

    private void respond(Exchange exchange, String op, Map<String, Object> params)
            throws Exception {
        ServiceProviders providers = exchange.beans().lookup(
                TesseraqlProperties.SERVICE_PROVIDERS_BEAN, ServiceProviders.class);
        Object result = providers.require(op).invoke(params);
        // A scalar result (a CSV string, a generated file's bytes) rides the hop in a value
        // envelope; a map is itself. Byte arrays are base64-marked either way.
        Object body = result instanceof Map<?, ?> map
                ? WorkshopTargets.encodeBytes(map)
                : Map.of("__value__", WorkshopTargets.encodeBytes(result));
        // platform-http mirrors request headers — including parsed form fields — onto the
        // response, and a multi-line field (a route document being saved) is a header value
        // Vert.x rightly refuses. The answer is the JSON body and nothing else.
        exchange.getMessage().removeHeaders("*", Headers.HTTP_RESPONSE_CODE);
        exchange.getMessage().setHeader(Headers.CONTENT_TYPE,
                "application/json; charset=utf-8");
        exchange.getMessage().setBody(mapper.writeValueAsString(body));
    }

    /** The urlencoded form: platform-http pre-parses it to a map body; a raw string is parsed. */
    private static Map<String, Object> formParams(Exchange exchange) {
        Map<String, Object> params = new LinkedHashMap<>();
        Object body = exchange.getMessage().getBody();
        if (body instanceof Map<?, ?> form) {
            form.forEach((key, value) -> params.put(String.valueOf(key), value));
        } else if (body instanceof String raw) {
            params.putAll(StudioSupport.parseQueryString(raw));
        }
        return params;
    }
}
