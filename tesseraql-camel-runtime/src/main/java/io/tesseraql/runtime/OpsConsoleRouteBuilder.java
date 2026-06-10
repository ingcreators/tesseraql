package io.tesseraql.runtime;

import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.opsui.OpsConsole;
import io.tesseraql.opsui.OpsDashboard;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;

/**
 * Serves the read-only Operations Console UI page under {@code /_tesseraql/ops/console} (design
 * ch. 26.11, 16). It renders the live {@link OpsDashboard} overview as a self-contained HTML page,
 * gated by the same bearer principal and {@code ops.batch.view} policy as the Operations JSON API,
 * and returned under a strict content security policy.
 */
final class OpsConsoleRouteBuilder extends RouteBuilder {

    private static final String VIEW = "tesseraql-auth:authenticate?auth=bearer";
    private static final String CSP = "default-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "frame-ancestors 'none'";

    private final OpsDashboard dashboard;

    OpsConsoleRouteBuilder(OpsDashboard dashboard) {
        this.dashboard = dashboard;
    }

    @Override
    public void configure() {
        onException(TqlException.class).handled(true).process(new ErrorResponseRenderer());
        onException(Exception.class).handled(true).process(new ErrorResponseRenderer());

        rest().get("/_tesseraql/ops/console").to("direct:ops.console");

        from("direct:ops.console").routeId("ops.console")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(renderConsole());
    }

    private Processor renderConsole() {
        return exchange -> {
            String html = OpsConsole.render(dashboard.overview(20));
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/html; charset=utf-8");
            exchange.getMessage().setHeader("Content-Security-Policy", CSP);
            exchange.getMessage().setHeader("X-Content-Type-Options", "nosniff");
            exchange.getMessage().setHeader("X-Frame-Options", "DENY");
            exchange.getMessage().setHeader("Referrer-Policy", "no-referrer");
            exchange.getMessage().setBody(html);
        };
    }
}
