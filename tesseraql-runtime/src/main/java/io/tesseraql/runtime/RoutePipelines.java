package io.tesseraql.runtime;

import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.RuntimeContext;
import java.util.Optional;

/**
 * Runs a compiled pipeline by id, for the callers that are not the HTTP edge
 * (docs/camel-removal.md structural decision 1).
 *
 * <p>The MCP server and the queue consumer each held a {@code ProducerTemplate} in order to
 * address a route as {@code direct:<id>} and run it once. Neither wanted message routing; both
 * wanted this lookup. What the template did that has to be kept is the shape of the answer — an
 * exchange whose body and status the caller reads, with a failure arriving as an exception on the
 * exchange rather than thrown — and {@link PipelineRunner#run} already produces exactly that,
 * error envelope and completions included.
 *
 * <p>This used to be a service with a cache in front of the registry, because resolving a
 * pipeline created producers and a producer per MCP call was a leak with a slow fuse. Nothing
 * creates a producer any more (docs/vertx-native.md decision 4), so it is a lookup and a run —
 * and a reload has one place to invalidate, which is the registry it always had to invalidate.
 */
final class RoutePipelines {

    /** Registry name, so a caller with a context can find it. */
    static final String BEAN = "tesseraqlRoutePipelines";

    private final RuntimeContext context;

    private RoutePipelines(RuntimeContext context) {
        this.context = context;
    }

    /** The runtime's runner, registered on first use. */
    static RoutePipelines of(RuntimeContext context) {
        RoutePipelines existing = context.lookup(BEAN, RoutePipelines.class);
        if (existing != null) {
            return existing;
        }
        RoutePipelines created = new RoutePipelines(context);
        context.bind(BEAN, created);
        return created;
    }

    /**
     * Runs {@code routeId}'s pipeline with a fresh exchange the caller prepares.
     *
     * <p>Empty when nothing is compiled under that id — which is the caller's problem to report,
     * because what a missing tool means to MCP is not what a missing consumer means to a queue.
     */
    Optional<Exchange> run(String routeId, java.util.function.Consumer<Exchange> prepare) {
        return Pipelines.of(context).find(routeId).map(pipeline -> {
            Exchange exchange = new Exchange(context.beans());
            prepare.accept(exchange);
            try {
                PipelineRunner.run(pipeline, exchange);
            } catch (RuntimeException unrendered) {
                // The envelope answers for everything a route declares a clause for; anything
                // left is handed back on the exchange, which is where a template caller always
                // read it.
                exchange.setException(unrendered);
            }
            return exchange;
        });
    }
}
