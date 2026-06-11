package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.telemetry.Meter;
import io.tesseraql.core.telemetry.NoopMeter;
import io.tesseraql.core.telemetry.NoopTracer;
import io.tesseraql.core.telemetry.Span;
import io.tesseraql.core.telemetry.Tracer;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.spi.Synchronization;

/**
 * Camel processor that opens a {@code tesseraql.route} span and counts route invocations
 * (design ch. 25.4, 25.6). The span ends and the response status is recorded when the exchange
 * completes; the tracer and meter are resolved from the registry (no-op when absent).
 */
public final class RouteTelemetry implements Processor {

    private final String routeId;
    private final String method;
    private final String path;
    private final String appName;

    public RouteTelemetry(String routeId, String method, String path, String appName) {
        this.routeId = routeId;
        this.method = method;
        this.path = path;
        this.appName = appName;
    }

    @Override
    public void process(Exchange exchange) {
        meter(exchange).counter("tesseraql.route.invocations")
                .increment(Map.of("routeId", routeId, "method", method));

        // The app attribute drives the ops console's per-app trace scope (design ch. 26.11).
        Span span = tracer(exchange).start("tesseraql.route")
                .attribute("routeId", routeId)
                .attribute("method", method)
                .attribute("path", path);
        if (appName != null) {
            span.attribute("app", appName);
        }
        exchange.setProperty(TesseraqlProperties.ROUTE_SPAN, span);
        io.tesseraql.core.telemetry.SpanContext spanContext = span.context();
        if (spanContext != null) {
            exchange.setProperty(TesseraqlProperties.TRACE_CONTEXT, spanContext);
        }
        exchange.getExchangeExtension().addOnCompletion(new Synchronization() {
            @Override
            public void onComplete(Exchange completed) {
                finish(completed, span);
            }

            @Override
            public void onFailure(Exchange failed) {
                if (failed.getException() != null) {
                    span.recordError(failed.getException());
                }
                finish(failed, span);
            }
        });
    }

    private static void finish(Exchange exchange, Span span) {
        Object status = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE);
        if (status != null) {
            span.attribute("status", status);
        }
        span.end();
    }

    private static Tracer tracer(Exchange exchange) {
        Tracer tracer = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.TRACER_BEAN, Tracer.class);
        return tracer != null ? tracer : NoopTracer.INSTANCE;
    }

    private static Meter meter(Exchange exchange) {
        Meter meter = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.METER_BEAN, Meter.class);
        return meter != null ? meter : NoopMeter.INSTANCE;
    }
}
