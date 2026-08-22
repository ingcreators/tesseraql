package io.tesseraql.compiler.pipeline;

import io.tesseraql.pipeline.Step;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects one pipeline's steps (docs/camel-removal.md structural decision 1).
 *
 * <p>The method names are the ones the compiler was already calling — {@code process}, {@code to}
 * — so what changed at the several dozen call sites is the type of the variable being chained, not
 * the shape of the code. That was the point of keeping them: a rewrite of the compiler's *text*
 * would have made every one of those sites a place to introduce a difference, and the differences
 * would have been invisible against a 1,900-line diff.
 *
 * <p>Mutation and {@link #build()} are synchronized: a hot reload fills a builder on the watcher
 * thread while {@code Pipelines.find} builds it on request threads, and an unsynchronized list
 * copied mid-append threw on the request thread. The lock makes every build a consistent
 * snapshot; the registry's version check keeps a mid-compile snapshot from being cached.
 */
public final class PipelineBuilder {

    private final String id;
    private final List<Step> steps = new ArrayList<>();
    private final List<Pipeline.Handler> handlers = new ArrayList<>();
    private int handoffAt = -1;
    private String laneExecutor;
    private int version;

    PipelineBuilder(String id, List<Pipeline.Handler> inherited) {
        this.id = id;
        this.handlers.addAll(inherited);
    }

    /** The pipeline's id, for the call sites that used to read it back off the route. */
    public String id() {
        return id;
    }

    /** Appends a processor. */
    public synchronized PipelineBuilder process(Step processor) {
        steps.add(processor);
        version++;
        return this;
    }

    /**
     * Declares that everything after this point runs on a named execution lane.
     *
     * <p>One per pipeline: {@code admission.lane} is a single handoff, and a second one would mean
     * two answers to the question of which pool a step belongs to.
     */
    public synchronized PipelineBuilder lane(String executorRef) {
        if (handoffAt >= 0) {
            throw new IllegalStateException(
                    "Pipeline " + id + " already hands off to lane '" + laneExecutor + "'");
        }
        handoffAt = steps.size();
        laneExecutor = executorRef;
        version++;
        return this;
    }

    /** Adds an error clause ahead of the inherited ones, so the more specific match wins. */
    public synchronized PipelineBuilder onException(Class<? extends Throwable> caught,
            Step renderer) {
        handlers.add(0, Pipeline.Handler.catching(caught, renderer));
        version++;
        return this;
    }

    public synchronized Pipeline build() {
        return new Pipeline(id, steps, handlers, handoffAt, laneExecutor);
    }

    /**
     * How many times this builder has been appended to, so a registry can tell a chain that is
     * still being compiled from one it already built (docs/vertx-native.md decision 4). The
     * compiler registers a pipeline before filling it — deliberately, so a forgotten hand-back
     * cannot lose a route — which means "registered" and "finished" are different moments, and a
     * cache keyed on registration alone would freeze the half-built chain a racing lookup saw.
     */
    synchronized int version() {
        return version;
    }
}
