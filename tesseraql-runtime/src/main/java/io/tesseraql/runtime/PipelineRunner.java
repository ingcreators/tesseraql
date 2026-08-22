package io.tesseraql.runtime;

import io.tesseraql.compiler.pipeline.Pipeline;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.util.List;

/**
 * Runs a compiled {@link Pipeline} against one exchange (docs/vertx-native.md decision 4).
 *
 * <p>This was {@code RoutePipeline}, which copied the record's four fields into fields of its own,
 * redeclared its {@code Handler} shape, and carried empty {@code start()}/{@code stop()} methods —
 * all leftovers from when resolving a pipeline meant turning endpoint URIs into producers with a
 * lifecycle. A record needs no resolving, so the runner is stateless and the registry is the one
 * holder of compiled pipelines: one cache, one place a reload invalidates.
 *
 * <p>What running means has not moved: the steps in order, the route's own error clauses
 * answering a failure, the declared lane honoured, and the completion drain in {@code finally}.
 */
final class PipelineRunner {

    private PipelineRunner() {
    }

    /**
     * Runs the chain, and renders a failure the way the route's own {@code onException} would.
     *
     * <p>The envelope is the route's — the same {@code ErrorResponseRenderer} instance the
     * compiler put in the model — so a refusal produced here is the refusal the framework
     * produces, not an imitation of it.
     */
    static void run(Pipeline pipeline, Exchange exchange) {
        run(pipeline, exchange, true);
    }

    /**
     * Runs the chain; with {@code drainOnDone} false the caller owns the completion drain.
     *
     * <p>Draining here — before the caller has read the body — is right for every caller that
     * consumes the exchange synchronously, and wrong for the one that has not written the wire
     * yet: the HTTP edge streams the body <em>after</em> this returns, and the completions delete
     * the streamed body's backing file, release the route's concurrency permit and end its span.
     * Draining first deleted an export's spool while the download read it (a contract only
     * unlink-while-open filesystems honoured, and a blob store does not), let a bounded route
     * stream on unboundedly many connections, and timed a download as if streaming were free.
     * The edge drains in its own {@code finally}, after the wire write; a caller that defers
     * must guarantee that call the way {@link Exchange#drain()} documents.
     */
    static void run(Pipeline pipeline, Exchange exchange, boolean drainOnDone) {
        List<Step> steps = pipeline.steps();
        try {
            for (int at = 0; at < steps.size(); at++) {
                if (at == pipeline.handoffAt()) {
                    // The rest of the route belongs to the declared lane. The virtual thread that
                    // got this far waits for it, which is what a lane is: a bound on how many of
                    // these run at once, not a way to answer sooner.
                    onLane(pipeline, exchange, at);
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
            // property, so the completions below run against an answered exchange — which is what
            // the audit row and the released permit are recorded against.
            exchange.setException(null);
            exchange.setProperty(TesseraqlProperties.EXCEPTION_CAUGHT, failure);
            Step renderer = rendererFor(pipeline, failure);
            if (renderer == null) {
                throw new IllegalStateException(failure);
            }
            try {
                renderer.process(exchange);
            } catch (Exception unrenderable) {
                throw new IllegalStateException(unrenderable);
            }
        } finally {
            // The completion guarantee (docs/vertx-native.md decision 5): the audit row, the
            // permits, the span and the streamed body all ride on this one call, and the failure
            // mode of missing it is silent and only on the error path.
            if (drainOnDone) {
                exchange.drain();
            }
        }
    }

    /** Runs the steps from {@code from} on the lane's executor, and waits for them. */
    private static void onLane(Pipeline pipeline, Exchange exchange, int from) throws Exception {
        java.util.concurrent.ExecutorService lane = exchange.beans().lookup(
                pipeline.laneExecutor(), java.util.concurrent.ExecutorService.class);
        if (lane == null) {
            throw new IllegalStateException(
                    "Execution lane '" + pipeline.laneExecutor() + "' is not bound");
        }
        List<Step> steps = pipeline.steps();
        // The request's correlation ids follow the step onto the lane's thread
        // (docs/camel-removal.md decision 5): the MDC travels by being carried, because a
        // pipeline reifies no per-processor wrappers to carry it for us.
        java.util.concurrent.Future<?> ran = lane.submit(
                io.tesseraql.pipeline.Correlation.carry(() -> {
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
     * The clause that catches this failure, most specific first: the failure's own hierarchy is
     * walked before the clause list, so a more specific clause wins regardless of declaration
     * order — the behaviour the name-based match always had, now on the classes themselves.
     */
    private static Step rendererFor(Pipeline pipeline, Exception failure) {
        for (Class<?> type = failure.getClass(); type != null; type = type.getSuperclass()) {
            for (Pipeline.Handler handler : pipeline.handlers()) {
                if (handler.caught().contains(type)) {
                    return handler.renderer();
                }
            }
        }
        return null;
    }
}
