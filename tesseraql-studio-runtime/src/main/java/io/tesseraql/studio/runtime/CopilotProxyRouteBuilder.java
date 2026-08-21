package io.tesseraql.studio.runtime;

import io.tesseraql.camel.HttpMounts;
import io.tesseraql.camel.auth.AuthStep;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.compiler.pipeline.Pipeline;
import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.runtime.HostContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.apache.camel.builder.RouteBuilder;

/**
 * The surface's copilot send proxy (docs/studio-shell.md structural decision 2): the shell's
 * copilot page posts to the member-shaped address, and the member's own transport answers with
 * the caller's forwarded credentials. Only the send hop is proxied — the fragment it returns
 * names the member's prefixed stream address, which the browser reaches through the gateway
 * directly, so the SSE stream needs no second hop.
 */
final class CopilotProxyRouteBuilder extends RouteBuilder {

    private static final AuthStep BROWSER = new AuthStep("authenticate", "browser", null, null);
    private static final AuthStep CSRF = new AuthStep("csrf");

    private final HostContext.MemberOrigins origins;
    private final HttpClient client = HttpClient.newHttpClient();

    CopilotProxyRouteBuilder(HostContext.MemberOrigins origins) {
        this.origins = origins;
    }

    @Override
    public void configure() {
        Pipelines.Compilation pipelines = Pipelines.of(getContext())
                .compiling(java.util.List.of(
                        Pipeline.Handler.catching(TqlException.class, new ErrorResponseRenderer()),
                        Pipeline.Handler.catching(Exception.class, new ErrorResponseRenderer())));

        HttpMounts.mount(getContext(), "POST", "/_tesseraql/studio/{member}/ui/copilot/send",
                "studio.shell.copilot.send");

        pipelines.pipeline("studio.shell.copilot.send")
                .process(BROWSER).process(CSRF).process(this::forward);
    }

    private void forward(Exchange exchange) throws Exception {
        String member = exchange.getMessage().getHeader("member", String.class);
        int port;
        try {
            port = origins.port(member, false);
        } catch (TqlException ex) {
            throw WorkshopTargets.notFound(member);
        }
        String body = exchange.getMessage().getBody(String.class);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/" + member + "/_tesseraql/studio/" + member
                        + "/ui/copilot/send"))
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        copyHeader(exchange, request, "Cookie");
        copyHeader(exchange, request, "X-CSRF-Token");
        copyHeader(exchange, request, "Content-Type");
        copyHeader(exchange, request, "HX-Request");
        HttpResponse<String> response = client.send(request.build(),
                HttpResponse.BodyHandlers.ofString());
        exchange.getMessage().setHeader(Headers.HTTP_RESPONSE_CODE, response.statusCode());
        response.headers().firstValue("Content-Type").ifPresent(value -> exchange.getMessage()
                .setHeader(Headers.CONTENT_TYPE, value));
        response.headers().firstValue("Location").ifPresent(value -> exchange.getMessage()
                .setHeader("Location", value));
        exchange.getMessage().setBody(response.body());
    }

    private static void copyHeader(Exchange exchange, HttpRequest.Builder request, String name) {
        String value = exchange.getMessage().getHeader(name, String.class);
        if (value != null) {
            request.header(name, value);
        }
    }
}
