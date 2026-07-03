package io.tesseraql.core.audit;

import java.time.Instant;

/**
 * The business-route audit sink (roadmap Phase 45): the compiler's per-route processor
 * records who called what, when, with the declared decision-relevant params — the JDBC
 * implementation lives in tesseraql-operations and rides the per-app ops scoping.
 */
public interface RouteAuditSink {

    void record(RouteAuditEvent event);

    /** One audited invocation; {@code paramsJson} holds only DECLARED, non-masked fields. */
    record RouteAuditEvent(String appName, String routeId, String httpMethod, String urlPath,
            String actor, String tenantId, Integer status, long durationMillis,
            String paramsJson, String traceId, Instant occurredAt) {
    }
}
