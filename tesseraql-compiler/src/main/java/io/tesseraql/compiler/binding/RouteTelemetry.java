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
    private final boolean accessLog;
    private static final org.slf4j.Logger ACCESS = org.slf4j.LoggerFactory
            .getLogger("tesseraql.access");

    public RouteTelemetry(String routeId, String method, String path, String appName) {
        this(routeId, method, path, appName, false);
    }

    public RouteTelemetry(String routeId, String method, String path, String appName,
            boolean accessLog) {
        this.routeId = routeId;
        this.method = method;
        this.path = path;
        this.appName = appName;
        this.accessLog = accessLog;
    }

    @Override
    public void process(Exchange exchange) {
        long startedNanos = System.nanoTime();
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
            // Trace-id correlation for structured logs (roadmap Phase 45): every line the
            // request produces on this thread carries the ids; Camel's MDC bridging (enabled
            // by the runtime) carries them across async steps.
            org.slf4j.MDC.put("traceId", spanContext.traceId());
            org.slf4j.MDC.put("spanId", spanContext.spanId());
        }
        exchange.getExchangeExtension().addOnCompletion(new Synchronization() {
            @Override
            public void onComplete(Exchange completed) {
                finish(completed, span, startedNanos);
            }

            @Override
            public void onFailure(Exchange failed) {
                if (failed.getException() != null) {
                    span.recordError(failed.getException());
                }
                finish(failed, span, startedNanos);
            }
        });
    }

    private void finish(Exchange exchange, Span span, long startedNanos) {
        Object status = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE);
        if (status != null) {
            span.attribute("status", status);
        }
        span.end();
        // Per-route latency and error signals a pull-based stack can consume (roadmap
        // Phase 45): duration histogram plus an outcome-classed counter. The status class
        // (2xx..5xx) keeps label cardinality bounded; an unset status after a failure
        // counts as 5xx, matching what the error renderer will have sent.
        long durationMillis = (System.nanoTime() - startedNanos) / 1_000_000;
        String outcome = outcomeClass(status, exchange.getException() != null);
        Map<String, String> labels = Map.of("routeId", routeId, "method", method,
                "outcome", outcome);
        meter(exchange).histogram("tesseraql.route.duration").record(durationMillis, labels);
        if (outcome.equals("5xx") || outcome.equals("4xx")) {
            meter(exchange).counter("tesseraql.route.errors").increment(labels);
        }
        if (accessLog) {
            // The opt-in HTTP access log (roadmap Phase 45): one line per request on the
            // completion thread, correlated by the same ids as every other log line.
            Object context = exchange.getProperty(TesseraqlProperties.TRACE_CONTEXT);
            if (context instanceof io.tesseraql.core.telemetry.SpanContext ids) {
                org.slf4j.MDC.put("traceId", ids.traceId());
                org.slf4j.MDC.put("spanId", ids.spanId());
            }
            ACCESS.info(accessLine(exchange, status, durationMillis));
        }
        org.slf4j.MDC.remove("traceId");
        org.slf4j.MDC.remove("spanId");
    }

    /** {@code GET /api/users 200 12ms route=users.search user=alice} — the access-log line. */
    String accessLine(Exchange exchange, Object status, long durationMillis) {
        StringBuilder line = new StringBuilder();
        line.append(method).append(' ').append(path).append(' ')
                .append(status == null ? "-" : status).append(' ')
                .append(durationMillis).append("ms route=").append(routeId);
        Object principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL);
        if (principal instanceof io.tesseraql.security.Principal who) {
            String user = who.loginId() != null ? who.loginId() : who.subject();
            if (user != null) {
                line.append(" user=").append(user);
            }
        }
        return line.toString();
    }

    private static String outcomeClass(Object status, boolean failed) {
        if (status instanceof Number number) {
            int code = number.intValue();
            if (code >= 100 && code <= 599) {
                return (code / 100) + "xx";
            }
        }
        return failed ? "5xx" : "2xx";
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
