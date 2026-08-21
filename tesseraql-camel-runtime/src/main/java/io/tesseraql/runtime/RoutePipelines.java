package io.tesseraql.runtime;

import io.tesseraql.pipeline.Exchange;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.camel.CamelContext;
import org.apache.camel.support.service.ServiceSupport;

/**
 * Runs a compiled pipeline by id, for the callers that are not the HTTP edge
 * (docs/camel-removal.md structural decision 1).
 *
 * <p>The MCP server and the queue consumer each held a {@code ProducerTemplate} in order to address
 * a route as {@code direct:<id>} and run it once. Neither wanted message routing; both wanted this
 * lookup. What the template did that has to be kept is the shape of the answer — an exchange whose
 * body and status the caller reads, with a failure arriving as an exception on the exchange rather
 * than thrown — and {@link RoutePipeline#run} already produces exactly that, error envelope and
 * completions included.
 *
 * <p>Pipelines are cached because resolving one creates producers, and a producer per MCP call is
 * a leak with a slow fuse. A reload evicts the entry it replaces.
 */
final class RoutePipelines extends ServiceSupport {

    /** Registry name, so a caller with a context can find it. */
    static final String BEAN = "tesseraqlRoutePipelines";

    private final CamelContext context;
    private final Map<String, RoutePipeline> resolved = new ConcurrentHashMap<>();

    private RoutePipelines(CamelContext context) {
        this.context = context;
    }

    /** The runtime's runner, created and registered as a service on first use. */
    static RoutePipelines of(CamelContext context) {
        RoutePipelines existing = context.getRegistry()
                .lookupByNameAndType(BEAN, RoutePipelines.class);
        if (existing != null) {
            return existing;
        }
        RoutePipelines created = new RoutePipelines(context);
        context.getRegistry().bind(BEAN, created);
        try {
            // As a service, so the producers it resolves are stopped when the context is.
            context.addService(created);
        } catch (Exception cannotRegister) {
            throw new IllegalStateException("Could not register the pipeline runner",
                    cannotRegister);
        }
        return created;
    }

    /**
     * Runs {@code routeId}'s pipeline with a fresh exchange the caller prepares.
     *
     * <p>Empty when nothing is compiled under that id — which is the caller's problem to report,
     * because what a missing tool means to MCP is not what a missing consumer means to a queue.
     */
    Optional<Exchange> run(String routeId, java.util.function.Consumer<Exchange> prepare) {
        RoutePipeline pipeline = pipeline(routeId);
        if (pipeline == null) {
            return Optional.empty();
        }
        Exchange exchange = new Exchange(io.tesseraql.camel.CamelBeans.of(context));
        prepare.accept(exchange);
        try {
            pipeline.run(exchange);
        } catch (RuntimeException unrendered) {
            // The envelope answers for everything a route declares a clause for; anything left is
            // handed back on the exchange, which is where a ProducerTemplate caller reads it.
            exchange.setException(unrendered);
        }
        return Optional.of(exchange);
    }

    /** Forgets a pipeline, stopping what it owned; the next run resolves the replacement. */
    void evict(String routeId) {
        RoutePipeline gone = resolved.remove(routeId);
        if (gone != null) {
            gone.stop();
        }
    }

    private RoutePipeline pipeline(String routeId) {
        return resolved.computeIfAbsent(routeId, id -> RoutePipeline.of(context, id)
                .map(pipeline -> {
                    try {
                        pipeline.start();
                    } catch (Exception unusable) {
                        throw new IllegalStateException(
                                "Pipeline " + id + " could not be started", unusable);
                    }
                    return pipeline;
                })
                .orElse(null));
    }

    @Override
    protected void doStop() {
        resolved.values().forEach(RoutePipeline::stop);
        resolved.clear();
    }
}
