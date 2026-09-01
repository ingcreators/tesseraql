package io.tesseraql.compiler.binding;

import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;

/**
 * A synthesized transition route's response, both legs (docs/workflow-surface.md decision 3):
 * an API caller keeps the JSON contract untouched, while a browser form post answers
 * Post/Redirect/Get — 303 back to the {@code _return} the transitions region always renders,
 * so the redirected GET re-renders the detail page from current truth. The branch is the
 * request's own encoding: a form-encoded body IS a form post; everything else is an API call.
 * Refusals never reach here — the error renderer already negotiates per caller.
 */
public final class WorkflowTransitionRenderer implements Step {

    private final Step json;

    public WorkflowTransitionRenderer(Step json) {
        this.json = json;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        String contentType = exchange.request().header("Content-Type");
        boolean formPost = contentType != null
                && (contentType.startsWith("application/x-www-form-urlencoded")
                        || contentType.startsWith("multipart/form-data"));
        if (formPost) {
            String declared = exchange.request().param("_return");
            RedirectRenderer.negotiate(exchange, 303,
                    io.tesseraql.core.http.BasePaths.isLocal(declared) ? declared : "/");
            return;
        }
        json.process(exchange);
    }
}
