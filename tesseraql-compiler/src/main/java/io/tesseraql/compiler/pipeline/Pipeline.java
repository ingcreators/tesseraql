package io.tesseraql.compiler.pipeline;

import java.util.List;
import org.apache.camel.Processor;

/**
 * A compiled route: the steps it runs, in order, and what answers when one of them throws
 * (docs/camel-removal.md structural decision 1).
 *
 * <p>This is what the compiler always produced. It used to say so by building a Camel route and
 * letting the edge read the chain back out of the route model — an encode and a decode of the same
 * list, with a decline path in the middle for shapes the encoder could express and this framework
 * never emits. The chain is now the artifact.
 *
 * <p>It is deliberately not an execution engine: running a pipeline is the runtime's job, and the
 * runtime already had that code. This carries what a run needs and nothing about how.
 *
 * @param id           the route id, which is also how everything else addresses this pipeline
 * @param steps        the chain, in authored order
 * @param handlers     the error clauses, most specific first
 * @param handoffAt    the index where a declared execution lane takes over, or -1
 * @param laneExecutor the registry name of that lane's executor, or null
 */
public record Pipeline(String id, List<Step> steps, List<Handler> handlers, int handoffAt,
        String laneExecutor) {

    public Pipeline {
        steps = List.copyOf(steps);
        handlers = List.copyOf(handlers);
    }

    /**
     * One step: a processor the compiler built.
     *
     * <p>It used to have a second kind — an endpoint the compiler <em>named</em>, as
     * {@code tesseraql-sql:file:...?mode=query&maxRows=200}, resolved to a producer wherever the
     * pipeline ran. The framework's own components stopped being components
     * (docs/camel-removal.md decision 2), so a step that was a URI built from typed values and
     * parsed back into typed fields is now the object those values describe.
     */
    public record Step(Processor processor) {
    }

    /**
     * One error clause: the exception class names it catches, and the processor that answers.
     *
     * <p>Class names rather than classes, matched up the failure's own hierarchy, because that is
     * how the route model spelled it and how a reader checks it.
     */
    public record Handler(List<String> caught, Processor renderer) {

        public Handler {
            caught = List.copyOf(caught);
        }

        /** One clause, spelled the way a route builder spells it. */
        public static Handler catching(Class<? extends Throwable> type, Processor renderer) {
            return new Handler(List.of(type.getName()), renderer);
        }
    }
}
