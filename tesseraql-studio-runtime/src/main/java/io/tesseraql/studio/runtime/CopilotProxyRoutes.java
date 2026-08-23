package io.tesseraql.studio.runtime;

import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.compiler.pipeline.Pipeline;
import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.HttpMounts;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.auth.AuthStep;
import io.tesseraql.runtime.HostContext;
import java.time.Duration;

/**
 * The surface's copilot send proxy (docs/studio-shell.md structural decision 2): the shell's
 * copilot page posts to the member-shaped address, and the member's own transport answers with
 * the caller's forwarded credentials. Only the send hop is proxied — the fragment it returns
 * names the member's prefixed stream address, which the browser reaches through the gateway
 * directly, so the SSE stream needs no second hop.
 */
final class CopilotProxyRoutes {

    private static final AuthStep BROWSER = new AuthStep("authenticate", "browser", null, null);
    private static final AuthStep CSRF = new AuthStep("csrf");

    private final HostContext.MemberOrigins origins;

    CopilotProxyRoutes(HostContext.MemberOrigins origins) {
        this.origins = origins;
    }

    void install(RuntimeContext context) {
        Pipelines.Compilation pipelines = Pipelines.of(context)
                .compiling(java.util.List.of(
                        Pipeline.Handler.catching(TqlException.class, new ErrorResponseRenderer()),
                        Pipeline.Handler.catching(Exception.class, new ErrorResponseRenderer())));

        HttpMounts.of(context).mount("POST", "/_tesseraql/studio/{member}/ui/copilot/send",
                "studio.shell.copilot.send");

        pipelines.pipeline("studio.shell.copilot.send")
                .process(BROWSER).process(CSRF).process(this::forward);
    }

    private void forward(Exchange exchange) throws Exception {
        String member = exchange.request().param("member");
        int port;
        try {
            port = origins.port(member, false);
        } catch (TqlException ex) {
            throw WorkshopTargets.notFound(member);
        }
        String body = exchange.getBody(String.class);
        io.tesseraql.runtime.LoopbackCall.Response response = io.tesseraql.runtime.LoopbackCall
                .to("POST", "http://localhost:" + port + "/" + member + "/_tesseraql/studio/"
                        + member + "/ui/copilot/send", Duration.ofSeconds(60))
                .body(body == null ? "" : body, exchange.request().header("Content-Type"))
                .cookie(exchange.request().header("Cookie"))
                .csrf(exchange.request().header("X-CSRF-Token"))
                .header("HX-Request", exchange.request().header("HX-Request"))
                .send();
        exchange.response().status(response.status());
        response.header("Content-Type")
                .ifPresent(value -> exchange.response().header(Headers.CONTENT_TYPE, value));
        response.header("Location")
                .ifPresent(value -> exchange.response().header("Location", value));
        exchange.setBody(response.body());
    }
}
