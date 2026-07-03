package io.tesseraql.compiler.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.audit.RouteAuditSink;
import io.tesseraql.security.Principal;
import io.tesseraql.yaml.model.InputField;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.spi.Synchronization;

/**
 * The opt-in business-route audit log (roadmap Phase 45): one durable row per invocation —
 * who called what, when, with the declared decision-relevant params. Only DECLARED input
 * fields are recorded, and fields carrying a {@code mask:} or {@code classification:} are
 * excluded wholesale, so a sensitive value can never leak into the trail. Rides the same
 * on-completion seam as {@link RouteTelemetry} and the per-app ops scoping via the app name.
 */
public final class RouteAudit implements Processor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String routeId;
    private final String method;
    private final String path;
    private final String appName;
    private final Set<String> auditableFields;

    public RouteAudit(String routeId, String method, String path, String appName,
            Map<String, InputField> input) {
        this.routeId = routeId;
        this.method = method;
        this.path = path;
        this.appName = appName;
        Set<String> fields = new java.util.LinkedHashSet<>();
        if (input != null) {
            input.forEach((name, field) -> {
                if (field.mask() == null && field.classification() == null) {
                    fields.add(name);
                }
            });
        }
        this.auditableFields = Set.copyOf(fields);
    }

    @Override
    public void process(Exchange exchange) {
        long startedNanos = System.nanoTime();
        exchange.getExchangeExtension().addOnCompletion(new Synchronization() {
            @Override
            public void onComplete(Exchange completed) {
                record(completed, startedNanos);
            }

            @Override
            public void onFailure(Exchange failed) {
                record(failed, startedNanos);
            }
        });
    }

    private void record(Exchange exchange, long startedNanos) {
        RouteAuditSink sink = exchange.getContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.ROUTE_AUDIT_SINK_BEAN, RouteAuditSink.class);
        if (sink == null) {
            return;
        }
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL,
                Principal.class);
        String actor = principal == null
                ? null
                : principal.loginId() != null ? principal.loginId() : principal.subject();
        Object status = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE);
        Object traceContext = exchange.getProperty(TesseraqlProperties.TRACE_CONTEXT);
        String traceId = traceContext instanceof io.tesseraql.core.telemetry.SpanContext ids
                ? ids.traceId()
                : null;
        sink.record(new RouteAuditSink.RouteAuditEvent(appName, routeId, method, path, actor,
                principal == null ? null : principal.tenantId(),
                status instanceof Number number ? number.intValue() : null,
                (System.nanoTime() - startedNanos) / 1_000_000,
                declaredParamsJson(exchange), traceId, Instant.now()));
    }

    /** The declared, non-masked params as stable JSON — never raw body content. */
    String declaredParamsJson(Exchange exchange) {
        if (auditableFields.isEmpty()) {
            return null;
        }
        Object context = exchange.getProperty(TesseraqlProperties.CONTEXT);
        if (!(context instanceof Map<?, ?> ctx)) {
            return null;
        }
        Map<String, Object> recorded = new TreeMap<>();
        for (String namespace : new String[]{"params", "query", "path"}) {
            if (ctx.get(namespace) instanceof Map<?, ?> values) {
                values.forEach((key, value) -> {
                    if (auditableFields.contains(String.valueOf(key)) && value != null) {
                        recorded.putIfAbsent(String.valueOf(key), value);
                    }
                });
            }
        }
        if (recorded.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(new LinkedHashMap<>(recorded));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
    }
}
