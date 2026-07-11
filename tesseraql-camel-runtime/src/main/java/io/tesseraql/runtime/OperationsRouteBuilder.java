package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.operations.batch.StepExecution;
import io.tesseraql.opsui.OpsScope;
import io.tesseraql.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;

/**
 * Builds the Operations API for batch jobs under {@code /_tesseraql/ops/batch} (design ch. 26.7,
 * 43.7). All endpoints require a bearer principal and a {@code ops.batch.*} policy; data attributed
 * to an app (jobs, executions, traces) additionally narrows to the caller's
 * {@code ops.app.<name>} grants, deny by default (design ch. 26.11). Runtime-wide diagnostics
 * (lanes, slow SQL, pinning, aggregate metrics, alerts) stay behind the entry permission only.
 */
final class OperationsRouteBuilder extends RouteBuilder {

    private static final String VIEW = "tesseraql-auth:authenticate?auth=bearer";
    /**
     * TQL-BATCH-4040: the requested operations resource (job, execution, trace, or event) is
     * unknown — or outside the caller's {@code ops.app.<name>} scope, which reads the same.
     */
    private static final Map<String, Object> NOT_FOUND = Map.of("error",
            Map.of("code", "TQL-BATCH-4040", "message", "Not Found"));

    private final ObjectMapper mapper = new ObjectMapper();
    private final JobRunner runner;
    private final JobRepository repository;
    private final Map<String, String> jobOwners;
    private final io.tesseraql.opsui.OpsDashboard dashboard;
    private final io.tesseraql.operations.outbox.JdbcOutboxStore outbox;
    private final MetricsSettings metrics;
    private final io.tesseraql.operations.audit.JdbcRouteAuditStore routeAudit;

    /** Runs a job by id; decouples the route builder from the runtime instance. */
    @FunctionalInterface
    interface JobRunner {
        JobExecution run(String jobId, Map<String, Object> params);
    }

    /** The Prometheus exposition settings (roadmap Phase 45): opt-in, bearer-gated default. */
    record MetricsSettings(boolean enabled, boolean unauthenticated,
            io.tesseraql.core.telemetry.AggregatingMeter meter) {
    }

    OperationsRouteBuilder(JobRunner runner, JobRepository repository,
            Map<String, String> jobOwners, io.tesseraql.opsui.OpsDashboard dashboard,
            io.tesseraql.operations.outbox.JdbcOutboxStore outbox, MetricsSettings metrics,
            io.tesseraql.operations.audit.JdbcRouteAuditStore routeAudit) {
        this.runner = runner;
        this.repository = repository;
        // Job id -> owning app, insertion-ordered so the job list keeps its declaration order.
        this.jobOwners = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(jobOwners));
        this.dashboard = dashboard;
        this.outbox = outbox;
        this.metrics = metrics;
        this.routeAudit = routeAudit;
    }

    @Override
    public void configure() {
        onException(TqlException.class).handled(true).process(new ErrorResponseRenderer());
        onException(Exception.class).handled(true).process(new ErrorResponseRenderer());

        rest().get("/_tesseraql/ops/batch/jobs").to("direct:ops.batch.jobs");
        rest().get("/_tesseraql/ops/batch/executions").to("direct:ops.batch.executions");
        rest().get("/_tesseraql/ops/batch/executions/{id}").to("direct:ops.batch.executionDetail");
        rest().post("/_tesseraql/ops/batch/jobs/{jobId}/run").to("direct:ops.batch.run");
        rest().get("/_tesseraql/ops/overview").to("direct:ops.overview");
        rest().get("/_tesseraql/ops/lanes").to("direct:ops.lanes");
        rest().get("/_tesseraql/ops/slow-sql").to("direct:ops.slowSql");
        rest().get("/_tesseraql/ops/traces").to("direct:ops.traces");
        rest().get("/_tesseraql/ops/traces/tree").to("direct:ops.traceTree");
        rest().get("/_tesseraql/ops/traces/summary").to("direct:ops.traceSummary");
        rest().get("/_tesseraql/ops/traces/metrics").to("direct:ops.traceMetrics");
        rest().get("/_tesseraql/ops/alerts").to("direct:ops.alerts");
        rest().get("/_tesseraql/ops/pinning").to("direct:ops.pinning");
        // The business-route audit trail read surface (roadmap Phase 45): bearer + policy
        // gated and narrowed to the caller's ops.app.<name> grants like every ops read.
        if (routeAudit != null) {
            rest().get("/_tesseraql/ops/audit").to("direct:ops.audit");
            from("direct:ops.audit").routeId("ops.audit")
                    .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                    .process(jsonProcessor(
                            exchange -> routeAudit.recent(200, scope(exchange))));
        }

        // The outbox delivery log and dead-letter redelivery (roadmap Phase 20).
        rest().get("/_tesseraql/ops/outbox").to("direct:ops.outbox");
        rest().post("/_tesseraql/ops/outbox/{id}/redeliver").to("direct:ops.outbox.redeliver");
        // Health for load balancers and deploy tooling (roadmap Phase 45): unauthenticated by
        // design, exposing only the status word - details stay behind the authorized ops API.
        // /health/live is pure liveness (the process answers; never touches a dependency);
        // /health/ready and the bare /health run the full roll-up incl. the datasource probe
        // and answer 503 on DOWN, so traffic actually sheds when the app cannot serve.
        rest().get("/_tesseraql/health").to("direct:ops.health");
        rest().get("/_tesseraql/health/live").to("direct:ops.health.live");
        // Its own direct: the REST consumers inline their direct bodies into one route each
        // (the Phase 42 hot-reload shape), so two consumers must not share a route id.
        rest().get("/_tesseraql/health/ready").to("direct:ops.health.ready");

        from("direct:ops.health").routeId("ops.health").process(readiness());
        from("direct:ops.health.ready").routeId("ops.health.ready").process(readiness());

        from("direct:ops.health.live").routeId("ops.health.live")
                .process(jsonProcessor(exchange -> java.util.Map.of("status", "UP")));

        // The Prometheus text exposition (roadmap Phase 45, decision point 9): opt-in, and
        // bearer + ops.metrics.view policy by default — metric labels reveal route ids, so
        // the scrape is authorized like the rest of the ops API unless the operator
        // explicitly opts a cluster-internal scraper out of auth.
        if (metrics != null && metrics.enabled()) {
            rest().get("/_tesseraql/metrics").to("direct:ops.metrics");
            var metricsRoute = from("direct:ops.metrics").routeId("ops.metrics");
            if (!metrics.unauthenticated()) {
                metricsRoute = metricsRoute.to(VIEW)
                        .to("tesseraql-auth:authorize?policy=ops.metrics.view");
            }
            metricsRoute.process(exchange -> {
                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
                exchange.getMessage().setHeader(Exchange.CONTENT_TYPE,
                        io.tesseraql.core.telemetry.PrometheusTextFormat.CONTENT_TYPE);
                exchange.getMessage().setBody(io.tesseraql.core.telemetry.PrometheusTextFormat
                        .render(metrics.meter()));
            });
        }

        from("direct:ops.batch.jobs").routeId("ops.batch.jobs")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> {
                    Predicate<String> scope = scope(exchange);
                    return jobOwners.entrySet().stream()
                            .filter(entry -> scope.test(entry.getValue()))
                            .map(Map.Entry::getKey)
                            .toList();
                }));

        from("direct:ops.batch.executions").routeId("ops.batch.executions")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> {
                    Predicate<String> scope = scope(exchange);
                    return repository.listExecutions(50).stream()
                            .filter(execution -> scope.test(execution.appName()))
                            .map(this::executionMap)
                            .toList();
                }));

        from("direct:ops.batch.executionDetail").routeId("ops.batch.executionDetail")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(this::executionDetail));

        from("direct:ops.batch.run").routeId("ops.batch.run")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.run")
                .process(jsonProcessor(this::runJob));

        from("direct:ops.overview").routeId("ops.overview")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> dashboard.overview(20, scope(exchange))));

        from("direct:ops.lanes").routeId("ops.lanes")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> dashboard.overview(0).lanes()));

        from("direct:ops.slowSql").routeId("ops.slowSql")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> dashboard.slowSql()));

        from("direct:ops.traces").routeId("ops.traces")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> dashboard.traces(scope(exchange))));

        from("direct:ops.traceTree").routeId("ops.traceTree")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> dashboard.traceTree(scope(exchange))));

        from("direct:ops.traceSummary").routeId("ops.traceSummary")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> dashboard.traceSummaries(
                        exchange.getMessage().getHeader("filter", String.class),
                        scope(exchange))));

        from("direct:ops.traceMetrics").routeId("ops.traceMetrics")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> dashboard.traceMetrics()));

        from("direct:ops.alerts").routeId("ops.alerts")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> dashboard.alerts()));

        from("direct:ops.pinning").routeId("ops.pinning")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> dashboard.pinning()));

        from("direct:ops.outbox").routeId("ops.outbox")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> {
                    Predicate<String> scope = scope(exchange);
                    return outbox.recent(200).stream()
                            .filter(event -> scope.test(event.appName()))
                            .map(this::outboxEventMap)
                            .toList();
                }));

        from("direct:ops.outbox.redeliver").routeId("ops.outbox.redeliver")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.run")
                .process(jsonProcessor(this::redeliverOutboxEvent));
    }

    /** Requeues a FAILED/DEAD event; outside the caller's scope it reads as unknown. */
    private Object redeliverOutboxEvent(Exchange exchange) {
        String id = exchange.getMessage().getHeader("id", String.class);
        io.tesseraql.core.outbox.OutboxEvent event = outbox.find(id)
                .filter(found -> scope(exchange).test(found.appName()))
                .orElse(null);
        if (event == null) {
            return NOT_FOUND;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("redelivered", outbox.redeliver(id));
        return result;
    }

    private Map<String, Object> outboxEventMap(io.tesseraql.core.outbox.OutboxEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", event.id());
        map.put("type", event.eventType());
        map.put("source", event.aggregateId());
        map.put("app", event.appName());
        map.put("status", event.status());
        map.put("attempts", event.attempts());
        map.put("lastError", event.lastError());
        map.put("createdAt", event.createdAt() == null ? null : event.createdAt().toString());
        map.put("sentAt", event.sentAt() == null ? null : event.sentAt().toString());
        return map;
    }

    /** The caller's per-app scope from the authenticated principal (design ch. 26.11). */
    private static Predicate<String> scope(Exchange exchange) {
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL, Principal.class);
        return OpsScope.allowedApps(principal == null ? null : principal.permissions());
    }

    private Object runJob(Exchange exchange) {
        String jobId = exchange.getMessage().getHeader("jobId", String.class);
        // A job outside the caller's scope is indistinguishable from an unknown one.
        if (!scope(exchange).test(jobOwners.get(jobId))) {
            return NOT_FOUND;
        }
        Map<String, Object> params = parseBody(exchange);
        JobExecution execution = runner.run(jobId, params);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", execution.id());
        result.put("status", execution.status().name());
        return result;
    }

    private Object executionDetail(Exchange exchange) {
        String id = exchange.getMessage().getHeader("id", String.class);
        JobExecution execution = repository.findExecution(id)
                .filter(found -> scope(exchange).test(found.appName()))
                .orElse(null);
        if (execution == null) {
            return NOT_FOUND;
        }
        Map<String, Object> detail = executionMap(execution);
        List<Object> steps = new ArrayList<>();
        for (StepExecution step : repository.findSteps(id)) {
            steps.add(stepMap(step));
        }
        detail.put("steps", steps);
        return detail;
    }

    private Map<String, Object> executionMap(JobExecution execution) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", execution.id());
        map.put("jobId", execution.jobId());
        map.put("app", execution.appName());
        map.put("status", execution.status().name());
        map.put("triggerType", execution.triggerType());
        map.put("startTime",
                execution.startTime() == null ? null : execution.startTime().toString());
        map.put("endTime", execution.endTime() == null ? null : execution.endTime().toString());
        map.put("durationMs", execution.durationMs());
        map.put("exitMessage", execution.exitMessage());
        return map;
    }

    private Map<String, Object> stepMap(StepExecution step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", step.id());
        map.put("stepId", step.stepId());
        map.put("status", step.status().name());
        map.put("affectedRows", step.affectedRows());
        map.put("durationMs", step.durationMs());
        map.put("errorMessage", step.errorMessage());
        return map;
    }

    private Map<String, Object> parseBody(Exchange exchange) {
        String raw = exchange.getMessage().getBody(String.class);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(raw, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return Map.of();
        }
    }

    /** The readiness roll-up: the status word, 503 when DOWN so a balancer sheds traffic. */
    private Processor readiness() {
        return exchange -> {
            String status = dashboard.health().status();
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE,
                    "DOWN".equals(status) ? 503 : 200);
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE,
                    "application/json; charset=utf-8");
            exchange.getMessage().setBody(
                    mapper.writeValueAsString(java.util.Map.of("status", status)));
        };
    }

    private Processor jsonProcessor(java.util.function.Function<Exchange, Object> handler) {
        return exchange -> {
            Object body = handler.apply(exchange);
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE,
                    "application/json; charset=utf-8");
            exchange.getMessage().setBody(mapper.writeValueAsString(body));
        };
    }
}
