package io.tesseraql.runtime;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * The one ops-shell surface that cannot be a service provider: a member's transfer-file
 * download, which streams bytes rather than rendering a view model
 * (docs/stack-shells.md structural decision 2). The shell address is
 * {@code /_tesseraql/ops/console/{member}/transfers/{id}/file}; on the stack surface runtime it
 * proxies the member's own browser-face download with the caller's cookie, and on the unhosted
 * boot — where the one member is this runtime itself — it forwards to the local handler.
 */
final class OpsShellRouteBuilder extends RouteBuilder {

    private final OpsShellProviders.Targets targets;

    OpsShellRouteBuilder(OpsShellProviders.Targets targets) {
        this.targets = targets;
    }

    @Override
    public void configure() {
        onException(TqlException.class).handled(true).process(new ErrorResponseRenderer());
        onException(Exception.class).handled(true).process(new ErrorResponseRenderer());

        rest().get("/_tesseraql/ops/console/{member}/transfers/{id}/file")
                .to("direct:ops.shell.transferFile");
        from("direct:ops.shell.transferFile").routeId("ops.shell.transferFile")
                .to("tesseraql-auth:authenticate?auth=browser")
                .process(this::download);
    }

    private void download(Exchange exchange) throws java.io.IOException {
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL, Principal.class);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("member", exchange.getMessage().getHeader("member", String.class));
        params.put("slot", exchange.getMessage().getHeader("slot", String.class));
        params.put("permissions", principal == null ? null : principal.permissions());
        OpsShellProviders.Selected selected = OpsShellProviders.select(params, targets,
                io.tesseraql.opsui.OpsScope.VIEW_PREFIX);
        String id = exchange.getMessage().getHeader("id", String.class);
        String url = targets.downloadUrl(selected.member(), selected.canary(), id);
        if (url == null) {
            // The unhosted boot: the member is this runtime, so the local handler answers.
            exchange.getContext().createProducerTemplate()
                    .send("direct:ops.console.transferFile", exchange);
            return;
        }
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest
                .newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(30));
        String cookie = exchange.getMessage().getHeader("Cookie", String.class);
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        java.net.http.HttpResponse<byte[]> response;
        try {
            response = java.net.http.HttpClient.newHttpClient().send(request.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("interrupted", ex);
        }
        exchange.getMessage().removeHeaders("*");
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, response.statusCode());
        response.headers().firstValue("Content-Type").ifPresent(value -> exchange.getMessage()
                .setHeader(Exchange.CONTENT_TYPE, value));
        response.headers().firstValue("Content-Disposition").ifPresent(value -> exchange
                .getMessage().setHeader("Content-Disposition", value));
        exchange.getMessage().setBody(response.body());
    }
}
