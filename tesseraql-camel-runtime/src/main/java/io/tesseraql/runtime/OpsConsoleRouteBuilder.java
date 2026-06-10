package io.tesseraql.runtime;

import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.opsui.OpsConsole;
import io.tesseraql.opsui.OpsDashboard;
import java.util.function.Function;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;

/**
 * Serves the read-only Operations Console UI under {@code /_tesseraql/ops/console} (design ch.
 * 26.11, 16): an overview page plus batch-execution detail and trace-tree drilldowns. The pages
 * render the live {@link OpsDashboard} and {@link JobRepository}, are gated by the same bearer
 * principal and {@code ops.batch.view} policy as the Operations JSON API, and are returned under a
 * strict content security policy.
 */
final class OpsConsoleRouteBuilder extends RouteBuilder {

    private static final String VIEW = "tesseraql-auth:authenticate?auth=bearer";
    private static final String CSP = "default-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "frame-ancestors 'none'";

    private final OpsDashboard dashboard;
    private final JobRepository repository;

    OpsConsoleRouteBuilder(OpsDashboard dashboard, JobRepository repository) {
        this.dashboard = dashboard;
        this.repository = repository;
    }

    @Override
    public void configure() {
        onException(TqlException.class).handled(true).process(new ErrorResponseRenderer());
        onException(Exception.class).handled(true).process(new ErrorResponseRenderer());

        rest().get("/_tesseraql/ops/console").to("direct:ops.console");
        rest().get("/_tesseraql/ops/console/traces").to("direct:ops.console.traces");
        rest().get("/_tesseraql/ops/console/executions/{id}").to("direct:ops.console.execution");

        from("direct:ops.console").routeId("ops.console")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(html(exchange -> OpsConsole.render(dashboard.overview(20))));

        from("direct:ops.console.traces").routeId("ops.console.traces")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(html(exchange -> OpsConsole.renderTraces(dashboard.traceTree())));

        from("direct:ops.console.execution").routeId("ops.console.execution")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(executionPage());
    }

    private Processor executionPage() {
        return exchange -> {
            String id = exchange.getMessage().getHeader("id", String.class);
            JobExecution execution = repository.findExecution(id).orElse(null);
            if (execution == null) {
                writeHtml(exchange, 404,
                        "<!DOCTYPE html><html><body><p>Execution not found.</p></body></html>");
                return;
            }
            writeHtml(exchange, 200,
                    OpsConsole.renderExecution(execution, repository.findSteps(id)));
        };
    }

    private Processor html(Function<Exchange, String> handler) {
        return exchange -> writeHtml(exchange, 200, handler.apply(exchange));
    }

    private static void writeHtml(Exchange exchange, int status, String body) {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/html; charset=utf-8");
        exchange.getMessage().setHeader("Content-Security-Policy", CSP);
        exchange.getMessage().setHeader("X-Content-Type-Options", "nosniff");
        exchange.getMessage().setHeader("X-Frame-Options", "DENY");
        exchange.getMessage().setHeader("Referrer-Policy", "no-referrer");
        exchange.getMessage().setBody(body);
    }
}
