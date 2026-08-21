package io.tesseraql.runtime;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.pipeline.Pipeline;
import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.pipeline.Completion;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A compiled route as a list of processors, run without a route (docs/http-edge.md decision 2).
 *
 * <p>What a compiled route contains is two {@code onException} handlers, a run of {@code process}
 * steps and a {@code to} — the {@code tesseraql-sql:} producer. Every one of those is a
 * {@link Step} or resolves to one, so running them in order is a loop, and the thread that
 * runs the loop is a choice rather than something {@code camel-platform-http-vertx} makes for the
 * framework. Measured on a real runtime: <strong>138 HTTP routes, none declined</strong>.
 *
 * <p>The route model stays the source of truth. This reads it rather than replacing it, so the
 * compiler keeps emitting exactly what it emits and a route that is not a plain chain is declined
 * rather than half-run.
 */
final class RoutePipeline {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory
            .getLogger(RoutePipeline.class);

    private final List<Step> steps;
    private final List<Handler> handlers;
    /**
     * Where the declared execution lane takes over, and the executor it takes over on.
     *
     * <p>{@code admission.lane} compiles to a {@code threads()} handoff: everything after it runs
     * on the lane's executor, which is the bound an application asked for by name. The edge runs
     * a request on a virtual thread of its own, but that is not the same promise — a lane is a
     * <em>named, sized</em> pool, and honouring the handoff is the difference between running the
     * declaration and ignoring it.
     */
    private final int handoffAt;
    private final String laneExecutor;

    /** One {@code onException} clause: what it catches, and what it does about it. */
    private record Handler(List<String> caught, Step renderer) {
    }

    private RoutePipeline(List<Step> steps, List<Handler> handlers, int handoffAt,
            String laneExecutor) {
        this.steps = steps;
        this.handlers = handlers;
        this.handoffAt = handoffAt;
        this.laneExecutor = laneExecutor;
    }

    /** The pipeline compiled under {@code routeId}, resolved for running. */
    static Optional<RoutePipeline> of(RuntimeContext camelContext, String routeId) {
        return Pipelines.of(camelContext).find(routeId)
                .flatMap(compiled -> of(camelContext, compiled));
    }

    /**
     * A compiled pipeline, which needs no reading back (docs/camel-removal.md decision 1).
     *
     * <p>The chain is the artifact, so there is nothing to resolve and no shape to decline: what
     * the compiler emitted is a list of steps and a list of clauses. Resolving used to mean
     * turning an endpoint URI into a producer, which is what the framework's own components
     * stopped being (docs/camel-removal.md decision 2).
     */
    static Optional<RoutePipeline> of(RuntimeContext camelContext, Pipeline compiled) {
        List<Step> steps = new ArrayList<>();
        steps.addAll(compiled.steps());
        List<Handler> handlers = new ArrayList<>();
        for (Pipeline.Handler handler : compiled.handlers()) {
            handlers.add(new Handler(handler.caught(), handler.renderer()));
        }
        return Optional.of(new RoutePipeline(steps, handlers, compiled.handoffAt(),
                compiled.laneExecutor()));
    }

    /**
     * Nothing to start.
     *
     * <p>A pipeline used to own the producers its {@code to} steps resolved to, which were
     * services with a lifecycle. Its steps are plain objects the compiler constructed now, so the
     * method stays only because its callers are about a pipeline becoming live, not about Camel.
     */
    void start() {
    }

    /** Nothing to stop, for the same reason nothing has to be started. */
    void stop() {
    }

    /**
     * Runs the chain, and renders a failure the way the route's own {@code onException} would.
     *
     * <p>The envelope is the route's — the same {@code ErrorResponseRenderer} instance the
     * compiler put in the model — so a refusal produced here is the refusal the framework
     * produces, not an imitation of it.
     */
    void run(Exchange exchange) {
        try {
            for (int at = 0; at < steps.size(); at++) {
                if (at == handoffAt) {
                    // The rest of the route belongs to the declared lane. The virtual thread that
                    // got this far waits for it, which is what a lane is: a bound on how many of
                    // these run at once, not a way to answer sooner.
                    onLane(exchange, at);
                    return;
                }
                Step step = steps.get(at);
                step.process(exchange);
                if (exchange.getException() != null) {
                    throw exchange.getException();
                }
                if (exchange.isRouteStop()) {
                    // A step that has already answered — the role-activation redirect is the one
                    // that does this — ends the route here. Running the renderer behind it would
                    // overwrite a 302 with the page the caller was being redirected away from,
                    // which is exactly what happened before this line existed.
                    break;
                }
            }
        } catch (Exception failure) {
            // What `handled(true)` means: the exception stops being the exchange's and becomes a
            // property, so the completions below run as completions rather than as failures —
            // which is what the audit row and the released permit are recorded against.
            exchange.setException(null);
            exchange.setProperty(TesseraqlProperties.EXCEPTION_CAUGHT, failure);
            Step renderer = rendererFor(failure);
            if (renderer == null) {
                throw new IllegalStateException(failure);
            }
            try {
                renderer.process(exchange);
            } catch (Exception unrenderable) {
                throw new IllegalStateException(unrenderable);
            }
        } finally {
            done(exchange);
        }
    }

    /** Runs the steps from {@code from} on the lane's executor, and waits for them. */
    private void onLane(Exchange exchange, int from) throws Exception {
        java.util.concurrent.ExecutorService lane = exchange.beans().lookup(laneExecutor,
                java.util.concurrent.ExecutorService.class);
        if (lane == null) {
            throw new IllegalStateException("Execution lane '" + laneExecutor + "' is not bound");
        }
        // The request's correlation ids follow the step onto the lane's thread
        // (docs/camel-removal.md decision 5): Camel's MDC service used to carry them by wrapping
        // every processor a route reified, and a pipeline reifies nothing.
        java.util.concurrent.Future<?> ran = lane.submit(
                io.tesseraql.camel.Correlation.carry(() -> {
                    for (int at = from; at < steps.size(); at++) {
                        steps.get(at).process(exchange);
                        if (exchange.getException() != null) {
                            throw exchange.getException();
                        }
                        if (exchange.isRouteStop()) {
                            return null;
                        }
                    }
                    return null;
                }));
        try {
            ran.get();
        } catch (java.util.concurrent.ExecutionException failed) {
            throw failed.getCause() instanceof Exception cause
                    ? cause
                    : new IllegalStateException(failed.getCause());
        }
    }

    /**
     * The completion guarantee: what Camel's unit of work runs whether the exchange succeeded or
     * failed, and the thing docs/http-edge.md named as most likely to be got wrong.
     *
     * <p>Five places in this framework register one — the route audit row, the per-route
     * concurrency permit, the lane permit, the telemetry span, and the SQL producer's streamed
     * body — and every one of them leaks or goes missing on the error path if nobody runs it. Not
     * one of them asks the unit of work for anything else, which is why draining the
     * registrations is the whole of what has to be reproduced rather than a first approximation
     * of it.
     *
     * <p>Drained with Camel's own helper, so a completion runs here exactly as it runs on a
     * route: {@code onFailure} when the exchange is still failed, {@code onComplete} when the
     * envelope above has already answered for it.
     */
    private static void done(Exchange exchange) {
        boolean failed = exchange.getException() != null;
        for (Completion completion : exchange.handoverCompletions()) {
            try {
                if (failed) {
                    completion.onFailure(exchange);
                } else {
                    completion.onComplete(exchange);
                }
            } catch (RuntimeException ignored) {
                // One completion failing must not strand the ones after it: an audit row that
                // cannot be written is not a reason to leak a permit. Camel's helper made the
                // same choice, and it is the reason this loop is a loop rather than a stream.
                LOG.warn("A completion of route {} failed", exchange.getFromRouteId(), ignored);
            }
        }
    }

    /**
     * The clause that catches this failure, most specific first.
     *
     * <p>The model carries class names rather than classes, so the match is by name up the
     * failure's own hierarchy — which is also how a reader checks it, and close enough for a
     * prototype whose routes declare exactly two clauses.
     */
    private Step rendererFor(Exception failure) {
        for (Class<?> type = failure.getClass(); type != null; type = type.getSuperclass()) {
            for (Handler handler : handlers) {
                if (handler.caught().contains(type.getName())) {
                    return handler.renderer();
                }
            }
        }
        return null;
    }
}
