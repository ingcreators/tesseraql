package io.tesseraql.compiler.pipeline;

import io.tesseraql.pipeline.Step;
import java.util.List;

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
     * One error clause: the exception classes it catches, and the processor that answers.
     *
     * <p>Classes rather than class names (docs/vertx-native.md decision 4): the route model that
     * could only spell a name is gone, and every declaration site has the class in its hand. The
     * match walks the failure's own hierarchy, so the most specific clause wins regardless of
     * declaration order — which is the behaviour the name-match always had.
     */
    public record Handler(List<Class<? extends Throwable>> caught, Step renderer) {

        public Handler {
            caught = List.copyOf(caught);
        }

        /** One clause, spelled the way the framework's own routes spell it. */
        public static Handler catching(Class<? extends Throwable> type, Step renderer) {
            return new Handler(List.of(type), renderer);
        }
    }
}
