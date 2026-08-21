package io.tesseraql.compiler.pipeline;

import java.util.ArrayList;
import java.util.List;
import org.apache.camel.Processor;

/**
 * Collects one pipeline's steps (docs/camel-removal.md structural decision 1).
 *
 * <p>The method names are the ones the compiler was already calling — {@code process}, {@code to}
 * — so what changed at the several dozen call sites is the type of the variable being chained, not
 * the shape of the code. That was the point of keeping them: a rewrite of the compiler's *text*
 * would have made every one of those sites a place to introduce a difference, and the differences
 * would have been invisible against a 1,900-line diff.
 */
public final class PipelineBuilder {

    private final String id;
    private final List<Pipeline.Step> steps = new ArrayList<>();
    private final List<Pipeline.Handler> handlers = new ArrayList<>();
    private int handoffAt = -1;
    private String laneExecutor;

    PipelineBuilder(String id, List<Pipeline.Handler> inherited) {
        this.id = id;
        this.handlers.addAll(inherited);
    }

    /** The pipeline's id, for the call sites that used to read it back off the route. */
    public String id() {
        return id;
    }

    /** Appends a processor. */
    public PipelineBuilder process(Processor processor) {
        steps.add(new Pipeline.Step.Run(processor));
        return this;
    }

    /** Appends an endpoint the runtime resolves to a producer. */
    public PipelineBuilder to(String uri) {
        steps.add(new Pipeline.Step.Send(uri));
        return this;
    }

    /**
     * Declares that everything after this point runs on a named execution lane.
     *
     * <p>One per pipeline: {@code admission.lane} is a single handoff, and a second one would mean
     * two answers to the question of which pool a step belongs to.
     */
    public PipelineBuilder lane(String executorRef) {
        if (handoffAt >= 0) {
            throw new IllegalStateException(
                    "Pipeline " + id + " already hands off to lane '" + laneExecutor + "'");
        }
        handoffAt = steps.size();
        laneExecutor = executorRef;
        return this;
    }

    /** Adds an error clause ahead of the inherited ones, so the more specific match wins. */
    public PipelineBuilder onException(List<String> caught, Processor renderer) {
        handlers.add(0, new Pipeline.Handler(caught, renderer));
        return this;
    }

    public Pipeline build() {
        return new Pipeline(id, steps, handlers, handoffAt, laneExecutor);
    }
}
