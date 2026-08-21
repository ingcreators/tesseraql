package io.tesseraql.compiler.pipeline;

import io.tesseraql.pipeline.RuntimeContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every compiled pipeline, by id (docs/camel-removal.md structural decision 1).
 *
 * <p>The registry is what replaces addressing a route as {@code direct:<id>}: the HTTP edge mounts
 * from it, the MCP server and the queue consumer run from it, and a hot reload swaps an entry in
 * it. All four wanted a lookup by id and were given a message-routing endpoint, which is why three
 * of them held a {@code ProducerTemplate} in order to make one call.
 *
 * <p>One per runtime, found through the context so that whoever compiles and whoever runs do not
 * have to be handed to each other — which matters because a reload compiles one route at a time
 * with a fresh compiler, and every other pipeline has to survive that.
 */
public final class Pipelines {

    /** Registry name, for the runtime that has a context and not this object. */
    public static final String BEAN = "tesseraqlPipelines";

    private final Map<String, PipelineBuilder> byId = new ConcurrentHashMap<>();

    /** The runtime's registry, created on first use. */
    public static Pipelines of(RuntimeContext context) {
        Pipelines existing = context.lookup(BEAN, Pipelines.class);
        if (existing != null) {
            return existing;
        }
        Pipelines created = new Pipelines();
        context.bind(BEAN, created);
        return created;
    }

    /**
     * Begins a compilation whose pipelines all inherit {@code handlers}.
     *
     * <p>The route DSL did this by side effect — an {@code onException} in a builder's
     * {@code configure()} was copied into each route the builder went on to create, a rule you
     * have to know rather than read. Here the inheritance is an argument, which also keeps a
     * reload from accumulating a second copy of the clauses on every recompile.
     */
    public Compilation compiling(List<Pipeline.Handler> handlers) {
        return new Compilation(List.copyOf(handlers));
    }

    /** The pipeline under {@code id}, as it stands now. */
    public Optional<Pipeline> find(String id) {
        return Optional.ofNullable(byId.get(id)).map(PipelineBuilder::build);
    }

    /** Every pipeline, by id, for the boot-time mount and a reload's reconciliation. */
    public Map<String, Pipeline> all() {
        Map<String, Pipeline> built = new LinkedHashMap<>();
        byId.forEach((id, builder) -> built.put(id, builder.build()));
        return built;
    }

    /** Whether anything is compiled under {@code id}. */
    public boolean contains(String id) {
        return byId.containsKey(id);
    }

    /** Forgets one pipeline, so a deleted route stops being addressable. */
    public void remove(String id) {
        byId.remove(id);
    }

    /** One compiler run: the pipelines it builds, and the clauses they inherit. */
    public final class Compilation {

        private final List<Pipeline.Handler> handlers;

        private Compilation(List<Pipeline.Handler> handlers) {
            this.handlers = handlers;
        }

        /**
         * Starts a pipeline under {@code id}, replacing whatever stood there.
         *
         * <p>Registered as it is built rather than when it is finished: a compiler that had to
         * remember to hand each one back would lose a route silently the first time somebody
         * added a build method and forgot.
         */
        public PipelineBuilder pipeline(String id) {
            PipelineBuilder builder = new PipelineBuilder(id, handlers);
            byId.put(id, builder);
            return builder;
        }
    }
}
