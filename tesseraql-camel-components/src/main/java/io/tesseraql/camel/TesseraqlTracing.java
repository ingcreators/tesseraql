package io.tesseraql.camel;

import io.tesseraql.core.telemetry.NoopTracer;
import io.tesseraql.core.telemetry.SpanContext;
import io.tesseraql.core.telemetry.Tracer;
import org.apache.camel.Exchange;

/**
 * Tracing helpers shared by route processors and components (design ch. 25.4). Resolves the tracer
 * bound by the runtime and the current parent span context propagated on the exchange, so each
 * step can open a child span under the route's span.
 */
public final class TesseraqlTracing {

    private TesseraqlTracing() {
    }

    /** The registry-bound tracer, or a no-op when none is configured. */
    public static Tracer tracer(Exchange exchange) {
        Tracer tracer = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.TRACER_BEAN, Tracer.class);
        return tracer != null ? tracer : NoopTracer.INSTANCE;
    }

    /** The current parent span context on the exchange (the route span), or null at the root. */
    public static SpanContext parent(Exchange exchange) {
        return exchange.getProperty(TesseraqlProperties.TRACE_CONTEXT, SpanContext.class);
    }
}
