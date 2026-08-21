package io.tesseraql.compiler;

import io.tesseraql.compiler.pipeline.Pipeline;
import io.tesseraql.compiler.pipeline.Pipelines;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.CamelContext;

/**
 * Reads what the compiler emitted, for the tests that assert on the chain rather than on
 * behaviour (docs/camel-removal.md structural decision 1).
 *
 * <p>These assertions used to walk Camel's route model, recursing through output definitions to
 * find the processors inside them. A pipeline is the list, so the walk is a loop — and the
 * questions the tests ask (how many times is this processor mounted, in what order, with which
 * endpoint URI) are asked of the artifact instead of of an encoding of it.
 */
final class CompiledPipelines {

    private CompiledPipelines() {
    }

    /** Each pipeline's steps, by id: a processor's simple class name, or an endpoint's URI. */
    static Map<String, List<String>> stepsById(CamelContext context) {
        Map<String, List<String>> byId = new LinkedHashMap<>();
        Pipelines.of(context).all().forEach((id, pipeline) -> byId.put(id, steps(pipeline)));
        return byId;
    }

    /** Every endpoint URI any pipeline names, in compilation order, filtered by prefix. */
    static List<String> endpoints(CamelContext context, String prefix) {
        List<String> uris = new ArrayList<>();
        for (Pipeline pipeline : Pipelines.of(context).all().values()) {
            for (Pipeline.Step step : pipeline.steps()) {
                if (step instanceof Pipeline.Step.Send send && send.uri().startsWith(prefix)) {
                    uris.add(send.uri());
                }
            }
        }
        return uris;
    }

    private static List<String> steps(Pipeline pipeline) {
        List<String> names = new ArrayList<>();
        for (Pipeline.Step step : pipeline.steps()) {
            names.add(switch (step) {
                case Pipeline.Step.Run run -> run.processor().getClass().getSimpleName();
                case Pipeline.Step.Send send -> send.uri();
            });
        }
        return names;
    }
}
