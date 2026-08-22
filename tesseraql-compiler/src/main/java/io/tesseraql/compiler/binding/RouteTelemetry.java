package io.tesseraql.compiler.binding;

import io.tesseraql.core.telemetry.Meter;
import io.tesseraql.core.telemetry.NoopMeter;
import io.tesseraql.core.telemetry.NoopTracer;
import io.tesseraql.core.telemetry.Span;
import io.tesseraql.core.telemetry.Tracer;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.util.Map;

/**
 * Pipeline step that opens a {@code tesseraql.route} span and counts route invocations
 * (design ch. 25.4, 25.6). The span ends and the response status is recorded when the exchange
 * completes; the tracer and meter are resolved from the registry (no-op when absent).
 */
public final class RouteTelemetry implements Step {

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
            // Trace-id correlation for structured logs (roadmap Phase 45). The exchange
            // properties are what travels: the MDC service the runtime installs copies them
            // into the MDC around every processor, so a step that runs on an execution lane
            // logs with the ids of the request that started it.
            exchange.setProperty(TesseraqlProperties.TRACE_ID, spanContext.traceId());
            exchange.setProperty(TesseraqlProperties.SPAN_ID, spanContext.spanId());
            // The MDC service sets the context *before* a processor runs, so it cannot know
            // about ids this processor is only now creating. Putting them here covers the rest
            // of this processor; every later one is covered by the service.
            org.slf4j.MDC.put(TesseraqlProperties.TRACE_ID, spanContext.traceId());
            org.slf4j.MDC.put(TesseraqlProperties.SPAN_ID, spanContext.spanId());
        }
        exchange.addOnCompletion(done -> finish(done, span, startedNanos));
    }

    private void finish(Exchange exchange, Span span, long startedNanos) {
        // The route's failure, read where the envelope put it. It used to be read off
        // getException() in a failure-only branch of the completion — which never ran, because
        // the pipeline moves the exception to this property before draining, so no span this
        // framework emitted ever carried its error (docs/vertx-native.md decision 5).
        Throwable failure = failure(exchange);
        if (failure != null) {
            span.recordError(failure);
        }
        Object status = exchange.response().status();
        if (status != null) {
            span.attribute("status", status);
        }
        span.end();
        // Per-route latency and error signals a pull-based stack can consume (roadmap
        // Phase 45): duration histogram plus an outcome-classed counter. The status class
        // (2xx..5xx) keeps label cardinality bounded; an unset status after a failure
        // counts as 5xx, matching what the error renderer will have sent.
        long durationMillis = (System.nanoTime() - startedNanos) / 1_000_000;
        String outcome = outcomeClass(status, failure != null);
        Map<String, String> labels = Map.of("routeId", routeId, "method", method,
                "outcome", outcome);
        meter(exchange).histogram("tesseraql.route.duration").record(durationMillis, labels);
        if (outcome.equals("5xx") || outcome.equals("4xx")) {
            meter(exchange).counter("tesseraql.route.errors").increment(labels);
        }
        if (accessLog) {
            // The opt-in HTTP access log (roadmap Phase 45): one line per request on the
            // completion thread, correlated by the same ids as every other log line. A
            // completion synchronization is not a processor, so the MDC service does not wrap
            // it — this line sets its own context and clears it below.
            Object context = exchange.getProperty(TesseraqlProperties.TRACE_CONTEXT);
            if (context instanceof io.tesseraql.core.telemetry.SpanContext ids) {
                org.slf4j.MDC.put(TesseraqlProperties.TRACE_ID, ids.traceId());
                org.slf4j.MDC.put(TesseraqlProperties.SPAN_ID, ids.spanId());
            }
            ACCESS.info(accessLine(exchange, status, durationMillis));
        }
        org.slf4j.MDC.remove(TesseraqlProperties.TRACE_ID);
        org.slf4j.MDC.remove(TesseraqlProperties.SPAN_ID);
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

    /** The failure the envelope answered for, or one nothing answered for, or null. */
    static Throwable failure(Exchange exchange) {
        Throwable caught = exchange.getProperty(TesseraqlProperties.EXCEPTION_CAUGHT,
                Throwable.class);
        return caught != null ? caught : exchange.getException();
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
        Tracer tracer = exchange.beans().lookup(TesseraqlProperties.TRACER_BEAN, Tracer.class);
        return tracer != null ? tracer : NoopTracer.INSTANCE;
    }

    private static Meter meter(Exchange exchange) {
        Meter meter = exchange.beans().lookup(TesseraqlProperties.METER_BEAN, Meter.class);
        return meter != null ? meter : NoopMeter.INSTANCE;
    }
}
