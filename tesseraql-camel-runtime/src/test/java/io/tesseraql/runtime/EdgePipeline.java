package io.tesseraql.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.OnExceptionDefinition;
import org.apache.camel.model.ProcessDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ToDefinition;

/**
 * A compiled route as a list of processors, run without a route (docs/http-edge.md slice 1).
 *
 * <p>This is a prototype and it lives in test sources on purpose. Slice 1 exists to produce a
 * number and to be abandoned cheaply if the number does not hold, so nothing in the shipped
 * runtime installs it and deleting it costs one file.
 *
 * <p><strong>The point it proves is that the pipeline is not the problem.</strong> What a
 * compiled route contains is two {@code onException} handlers, a run of {@code process} steps and
 * a {@code to} — the {@code tesseraql-sql:} producer. Every one of those is a {@link Processor}
 * or resolves to one, so running them in order is a loop, and the thread that runs the loop is a
 * choice rather than something {@code camel-platform-http-vertx} makes for the framework.
 */
final class EdgePipeline {

    private final List<Processor> steps;
    private final List<Handler> handlers;

    /** One {@code onException} clause: what it catches, and what it does about it. */
    private record Handler(List<String> caught, Processor renderer) {
    }

    private EdgePipeline(List<Processor> steps, List<Handler> handlers) {
        this.steps = steps;
        this.handlers = handlers;
    }

    /**
     * Reads the route's model, or declines.
     *
     * <p>Declining is the important half: a route with a {@code choice}, a {@code split} or a
     * {@code threads} is not something this prototype pretends to run, and slice 1 is not the
     * place to find out that it half-ran one.
     */
    static Optional<EdgePipeline> of(CamelContext camelContext, String routeId) {
        RouteDefinition definition = ((ModelCamelContext) camelContext).getRouteDefinition(routeId);
        if (definition == null) {
            return Optional.empty();
        }
        List<Processor> steps = new ArrayList<>();
        List<Handler> handlers = new ArrayList<>();
        for (ProcessorDefinition<?> output : definition.getOutputs()) {
            switch (output) {
                case OnExceptionDefinition onException -> {
                    Processor renderer = single(onException.getOutputs());
                    if (renderer == null) {
                        return Optional.empty();
                    }
                    handlers.add(new Handler(List.copyOf(onException.getExceptions()), renderer));
                }
                case ProcessDefinition process -> steps.add(process.getProcessor());
                case ToDefinition to -> {
                    try {
                        steps.add(camelContext.getEndpoint(to.getUri()).createProducer());
                    } catch (Exception unusable) {
                        return Optional.empty();
                    }
                }
                default -> {
                    return Optional.empty();
                }
            }
        }
        return steps.stream().anyMatch(java.util.Objects::isNull)
                ? Optional.empty()
                : Optional.of(new EdgePipeline(steps, handlers));
    }

    private static Processor single(List<ProcessorDefinition<?>> outputs) {
        return outputs.size() == 1 && outputs.get(0) instanceof ProcessDefinition process
                ? process.getProcessor()
                : null;
    }

    /** Starts the producers the {@code to} steps resolved to; they are services like any other. */
    void start() throws Exception {
        for (Processor step : steps) {
            if (step instanceof org.apache.camel.Service service) {
                service.start();
            }
        }
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
            for (Processor step : steps) {
                step.process(exchange);
                if (exchange.getException() != null) {
                    throw exchange.getException();
                }
            }
        } catch (Exception failure) {
            exchange.setException(null);
            exchange.setProperty(Exchange.EXCEPTION_CAUGHT, failure);
            Processor renderer = rendererFor(failure);
            if (renderer == null) {
                throw new IllegalStateException(failure);
            }
            try {
                renderer.process(exchange);
            } catch (Exception unrenderable) {
                throw new IllegalStateException(unrenderable);
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
    private Processor rendererFor(Exception failure) {
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
