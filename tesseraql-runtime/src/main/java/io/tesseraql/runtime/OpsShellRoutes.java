package io.tesseraql.runtime;

import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.compiler.pipeline.Pipeline;
import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.HttpMounts;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.pipeline.auth.AuthStep;
import io.tesseraql.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one ops-shell surface that cannot be a service provider: a member's transfer-file
 * download, which streams bytes rather than rendering a view model
 * (docs/stack-shells.md structural decision 2). The shell address is
 * {@code /_tesseraql/ops/console/{member}/transfers/{id}/file}; on the stack surface runtime it
 * proxies the member's own browser-face download with the caller's cookie, and on the unhosted
 * boot — where the one member is this runtime itself — it forwards to the local handler.
 */
final class OpsShellRoutes {

    private final OpsShellProviders.Targets targets;

    OpsShellRoutes(OpsShellProviders.Targets targets) {
        this.targets = targets;
    }

    void install(RuntimeContext context) {
        Pipelines.Compilation pipelines = Pipelines.of(context)
                .compiling(java.util.List.of(
                        Pipeline.Handler.catching(TqlException.class, new ErrorResponseRenderer()),
                        Pipeline.Handler.catching(Exception.class, new ErrorResponseRenderer())));

        HttpMounts.of(context).mount("GET",
                "/_tesseraql/ops/console/{member}/transfers/{id}/file",
                "ops.shell.transferFile");
        pipelines.pipeline("ops.shell.transferFile")
                .process(new AuthStep("authenticate", "browser", null, null))
                .process(this::download);
    }

    private void download(Exchange exchange) throws java.io.IOException {
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL, Principal.class);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("member", exchange.request().param("member"));
        params.put("slot", exchange.request().param("slot"));
        params.put("permissions", principal == null ? null : principal.permissions());
        OpsShellProviders.Selected selected = OpsShellProviders.select(params, targets,
                io.tesseraql.opsui.OpsScope.VIEW_PREFIX);
        String id = exchange.request().param("id");
        String url = targets.downloadUrl(selected.member(), selected.canary(), id);
        if (url == null) {
            // The unhosted boot: the member is this runtime, so the local handler answers. Run
            // rather than send: what this ever wanted was the pipeline behind that address
            // (docs/camel-removal.md decision 1), and a template was the only way to ask for it.
            exchange.beans().lookup(RoutePipelines.BEAN, RoutePipelines.class)
                    .run("ops.console.transferFile", target -> {
                        target.request().becomeCopyOf(exchange.request());
                        target.setBody(exchange.getBody());
                    })
                    .ifPresent(answered -> {
                        exchange.response().becomeCopyOf(answered.response());
                        exchange.setBody(answered.getBody());
                        if (answered.getException() != null) {
                            exchange.setException(answered.getException());
                        }
                    });
            return;
        }
        // Streamed, not buffered: this surface exists because it streams bytes
        // (docs/stack-shells.md structural decision 2), and the proxied branch was the one
        // place that materialized the whole transfer file on the heap. The edge reads the
        // stream to the wire and closes it; the member's Content-Length is recomputed by
        // the transport like every response header the edge frames itself.
        LoopbackCall.Streaming response;
        try {
            response = LoopbackCall.to("GET", url, java.time.Duration.ofSeconds(30))
                    .cookie(exchange.request().header("Cookie"))
                    .stream();
        } catch (LoopbackCall.Unreachable ex) {
            throw new java.io.IOException(ex.getMessage(), ex);
        }
        exchange.response().status(response.status());
        response.header("Content-Type")
                .ifPresent(value -> exchange.response().header(Headers.CONTENT_TYPE, value));
        response.header("Content-Disposition").ifPresent(
                value -> exchange.response().header("Content-Disposition", value));
        java.io.InputStream body = response.body();
        // The edge closes the stream after writing it; the completion is the net for the
        // path that never streams — a failure between here and the wire would otherwise
        // hold the pooled connection until GC. Closing twice is harmless.
        exchange.addOnCompletion(done -> {
            try {
                body.close();
            } catch (java.io.IOException ignored) {
                // the edge already closed it
            }
        });
        exchange.setBody(body);
    }
}
