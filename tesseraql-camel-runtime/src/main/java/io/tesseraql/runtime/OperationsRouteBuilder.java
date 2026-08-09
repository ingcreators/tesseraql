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
     * Thrown, so the standard error path answers 404 with the framework envelope (the shape
     * {@code ErrorResponseRenderer.httpStatus} always promised for this code).
     */
    private static final io.tesseraql.core.error.TqlErrorCode UNKNOWN = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.BATCH, 4040);

    /** The 404 refusal for an unknown — or out-of-scope, which reads the same — resource. */
    private static TqlException notFound(String what) {
        return TqlException.builder(UNKNOWN)
                .message(what + " is unknown or outside the caller's ops.app scope")
                .build();
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final JobRunner runner;
    private final JobRepository repository;
    private final Map<String, String> jobOwners;
    private final Map<String, io.tesseraql.yaml.model.JobDefinition> definitions;
    private final io.tesseraql.opsui.OpsDashboard dashboard;
    private final io.tesseraql.operations.outbox.JdbcOutboxStore outbox;
    private final io.tesseraql.core.messaging.EventChannelStore events;
    private final MetricsSettings metrics;
    private final io.tesseraql.operations.audit.JdbcRouteAuditStore routeAudit;
    private final io.tesseraql.core.files.FileTransferService transfers;

    /**
     * Runs a job by id; decouples the route builder from the runtime instance. The trigger
     * facts ride along so the execution row records how - and for a manual run, by whom -
     * it started (docs/ops-console-actions.md).
     */
    @FunctionalInterface
    interface JobRunner {
        JobExecution run(String jobId, Map<String, Object> params, String triggerType,
                String triggeredBy);
    }

    /**
     * The Prometheus exposition settings (roadmap Phase 45): opt-in, bearer-gated default.
     * {@code pollSources} joins the scrape as gauge families rendered from the registry at
     * scrape time (docs/poll-source-metrics.md).
     */
    record MetricsSettings(boolean enabled, boolean unauthenticated,
            io.tesseraql.core.telemetry.AggregatingMeter meter,
            io.tesseraql.opsui.PollSourceStatus pollSources) {
    }

    OperationsRouteBuilder(JobRunner runner, JobRepository repository,
            Map<String, String> jobOwners,
            Map<String, io.tesseraql.yaml.model.JobDefinition> definitions,
            io.tesseraql.opsui.OpsDashboard dashboard,
            io.tesseraql.operations.outbox.JdbcOutboxStore outbox,
            io.tesseraql.core.messaging.EventChannelStore events, MetricsSettings metrics,
            io.tesseraql.operations.audit.JdbcRouteAuditStore routeAudit,
            io.tesseraql.core.files.FileTransferService transfers) {
        this.runner = runner;
        this.repository = repository;
        this.transfers = transfers;
        // Job id -> owning app, insertion-ordered so the job list keeps its declaration order.
        this.jobOwners = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(jobOwners));
        this.definitions = java.util.Collections
                .unmodifiableMap(new LinkedHashMap<>(definitions));
        this.dashboard = dashboard;
        this.outbox = outbox;
        this.events = events;
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
        rest().post("/_tesseraql/ops/batch/executions/{id}/cancel")
                .to("direct:ops.batch.cancel");
        rest().get("/_tesseraql/ops/batch/transfers/{id}/file")
                .to("direct:ops.batch.transferFile");
        rest().get("/_tesseraql/ops/console/transfers/{id}/file")
                .to("direct:ops.console.transferFile");
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
        // The messaging-channel queue event log and dead-letter redelivery, mirroring the
        // outbox surface (docs/silent-tolerance.md O1).
        rest().get("/_tesseraql/ops/events").to("direct:ops.events");
        rest().post("/_tesseraql/ops/events/{id}/redeliver").to("direct:ops.events.redeliver");
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
                        .render(metrics.meter())
                        + io.tesseraql.opsui.PollSourceMetrics.render(metrics.pollSources(),
                                java.time.Instant.now()));
            });
        }

        from("direct:ops.batch.jobs").routeId("ops.batch.jobs")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> {
                    Predicate<String> scope = scope(exchange);
                    // Objects since 0.11 (docs/jobs.md): the trigger story and the
                    // operational promises, so the API is at least as told as the CLI.
                    return jobOwners.entrySet().stream()
                            .filter(entry -> scope.test(entry.getValue()))
                            .map(entry -> jobMap(entry.getKey(), entry.getValue()))
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

        // The cooperative stop (docs/jobs.md "Stopping a run"): sets the flag the running
        // executor polls at step and chunk-commit boundaries; gated like starting a run.
        from("direct:ops.batch.cancel").routeId("ops.batch.cancel")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.run")
                .process(jsonProcessor(this::cancelExecution));

        // A job-produced export has no route-scoped download URL, so it is fetched here
        // (docs/analytics-experience.md track 3) — view-gated and app-scoped like the
        // transfers listing; unknown and out-of-scope read the same (TQL-BATCH-4040). Two
        // faces, one handler: the API for machine callers, the console for the browser
        // session behind the transfers page.
        from("direct:ops.batch.transferFile").routeId("ops.batch.transferFile")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(this::transferFile);
        from("direct:ops.console.transferFile").routeId("ops.console.transferFile")
                .to("tesseraql-auth:authenticate?auth=browser")
                .to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(this::transferFile);

        from("direct:ops.overview").routeId("ops.overview")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> dashboard.overview(20, scope(exchange))));

        from("direct:ops.lanes").routeId("ops.lanes")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> dashboard.overview(0).lanes()));

        from("direct:ops.slowSql").routeId("ops.slowSql")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> mapList(dashboard.slowSql(),
                        OperationsRouteBuilder::sqlExecutionWire)));

        from("direct:ops.traces").routeId("ops.traces")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> mapList(dashboard.traces(scope(exchange)),
                        OperationsRouteBuilder::spanWire)));

        from("direct:ops.traceTree").routeId("ops.traceTree")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> mapList(dashboard.traceTree(scope(exchange)),
                        OperationsRouteBuilder::traceNodeWire)));

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
                .process(jsonProcessor(exchange -> pinningWire(dashboard.pinning())));

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

        from("direct:ops.events").routeId("ops.events")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.view")
                .process(jsonProcessor(exchange -> {
                    Predicate<String> scope = scope(exchange);
                    return events.recent(200).stream()
                            .filter(event -> scope.test(event.appName()))
                            .map(this::channelEventMap)
                            .toList();
                }));

        from("direct:ops.events.redeliver").routeId("ops.events.redeliver")
                .to(VIEW).to("tesseraql-auth:authorize?policy=ops.batch.run")
                .process(jsonProcessor(this::redeliverChannelEvent));
    }

    /** Requeues a FAILED/DEAD event; outside the caller's scope it reads as unknown. */
    private Object redeliverOutboxEvent(Exchange exchange) {
        String id = exchange.getMessage().getHeader("id", String.class);
        io.tesseraql.core.outbox.OutboxEvent event = outbox.find(id)
                .filter(found -> scope(exchange).test(found.appName()))
                .orElse(null);
        if (event == null) {
            throw notFound("Outbox event '" + id + "'");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("redelivered", outbox.redeliver(id));
        return result;
    }

    /** Requeues a DEAD queue message; outside the caller's scope it reads as unknown. */
    private Object redeliverChannelEvent(Exchange exchange) {
        String id = exchange.getMessage().getHeader("id", String.class);
        io.tesseraql.core.messaging.ChannelEvent event = events.find(id)
                .filter(found -> scope(exchange).test(found.appName()))
                .orElse(null);
        if (event == null) {
            throw notFound("Queue event '" + id + "'");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("redelivered", events.redeliver(id));
        return result;
    }

    private Map<String, Object> channelEventMap(io.tesseraql.core.messaging.ChannelEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", event.id());
        map.put("channel", event.channel());
        map.put("topic", event.topic());
        map.put("key", event.key());
        map.put("app", event.appName());
        map.put("status", event.status());
        map.put("attempts", event.attempts());
        map.put("lastError", event.lastError());
        map.put("publishedAt",
                event.publishedAt() == null ? null : event.publishedAt().toString());
        map.put("consumedAt", event.consumedAt() == null ? null : event.consumedAt().toString());
        return map;
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

    /** One declared job for the API listing: identity, trigger story, and policies. */
    private Map<String, Object> jobMap(String jobId, String app) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", jobId);
        map.put("app", app);
        io.tesseraql.yaml.model.JobDefinition definition = definitions.get(jobId);
        map.put("trigger", definition == null
                ? "on demand"
                : io.tesseraql.yaml.model.TriggerSpec.describe(definition.trigger()));
        map.put("overlap", definition != null && definition.skipsOverlap()
                ? "skip"
                : "concurrent");
        io.tesseraql.yaml.model.SlaSpec sla = definition == null ? null : definition.sla();
        if (sla != null) {
            Map<String, Object> slaMap = new LinkedHashMap<>();
            slaMap.put("completeBy", sla.completeBy());
            slaMap.put("runningLongerThan", sla.runningLongerThan());
            map.put("sla", slaMap);
        }
        return map;
    }

    private Object runJob(Exchange exchange) {
        String jobId = exchange.getMessage().getHeader("jobId", String.class);
        // A job outside the caller's scope is indistinguishable from an unknown one.
        if (!scope(exchange).test(jobOwners.get(jobId))) {
            throw notFound("Job '" + jobId + "'");
        }
        Map<String, Object> params = parseBody(exchange);
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL, Principal.class);
        JobExecution execution = runner.run(jobId, params, "manual",
                principal == null ? null : principal.loginId());
        // Work accepted, poll the execution: the same 202 + Location contract the
        // file-transfer start answers (docs/vocabulary-cleanup.md slice 3).
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 202);
        exchange.getMessage().setHeader("Location",
                "/_tesseraql/ops/batch/executions/" + execution.id());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", execution.id());
        result.put("status", execution.status().name());
        return result;
    }

    /** TQL-BATCH-4042: the cancel target is not running — nothing left to stop (HTTP 409). */
    private static final io.tesseraql.core.error.TqlErrorCode NOT_RUNNING = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.BATCH, 4042);

    /** TQL-BATCH-4043: a manual job-run request carried an unparseable JSON body (HTTP 400). */
    private static final io.tesseraql.core.error.TqlErrorCode BAD_RUN_BODY = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.BATCH, 4043);

    private Object cancelExecution(Exchange exchange) {
        String id = exchange.getMessage().getHeader("id", String.class);
        JobExecution execution = repository.findExecution(id)
                .filter(found -> scope(exchange).test(found.appName()))
                .orElse(null);
        if (execution == null) {
            throw notFound("Execution '" + id + "'");
        }
        if (!repository.requestCancel(id)) {
            throw io.tesseraql.core.error.TqlException.builder(NOT_RUNNING)
                    .message("Execution " + id + " is " + execution.status()
                            + " — only a RUNNING execution can be stopped")
                    .build();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", id);
        result.put("cancelRequested", true);
        return result;
    }

    private Object executionDetail(Exchange exchange) {
        String id = exchange.getMessage().getHeader("id", String.class);
        JobExecution execution = repository.findExecution(id)
                .filter(found -> scope(exchange).test(found.appName()))
                .orElse(null);
        if (execution == null) {
            throw notFound("Execution '" + id + "'");
        }
        Map<String, Object> detail = executionMap(execution);
        List<Object> steps = new ArrayList<>();
        for (StepExecution step : repository.findSteps(id)) {
            steps.add(stepMap(step));
        }
        detail.put("steps", steps);
        // The rows a chunk step's skip policy tolerated (docs/batch-platform.md track C):
        // recorded per execution, so "COMPLETED with 3 skips" is inspectable, not folklore.
        List<Object> skips = new ArrayList<>();
        for (JobRepository.SkippedRow skip : repository.findSkips(id)) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("stepId", skip.stepId());
            map.put("rowKey", skip.rowKey());
            map.put("message", skip.message());
            map.put("at", skip.createdAt() == null ? null : skip.createdAt().toString());
            skips.add(map);
        }
        detail.put("skips", skips);
        return detail;
    }

    private Map<String, Object> executionMap(JobExecution execution) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", execution.id());
        map.put("jobId", execution.jobId());
        map.put("app", execution.appName());
        map.put("status", execution.status().name());
        map.put("triggerType", execution.triggerType());
        map.put("businessDate",
                execution.businessDate() == null ? null : execution.businessDate().toString());
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
        map.put("skippedRows", step.skippedRows());
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
            // A present-but-unparseable body was silently dropped, launching the job with no
            // params (e.g. a typo'd businessDate) while answering 202 Accepted.
            throw new io.tesseraql.core.error.TqlException(BAD_RUN_BODY,
                    "Request body is not valid JSON: " + ex.getOriginalMessage());
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
            // A handler that set its own status (the 202 accepted-run) keeps it.
            if (exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE) == null) {
                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
            }
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE,
                    "application/json; charset=utf-8");
            exchange.getMessage().setBody(mapper.writeValueAsString(body));
        };
    }

    /**
     * Streams one completed export (docs/analytics-experience.md track 3). Unknown ids and
     * transfers outside the caller's {@code ops.app.<name>} scope read the same 404; a
     * transfer that is not a completed export is a 409 ({@code TQL-LD-2823}, the route
     * download's refusal).
     */
    private void transferFile(Exchange exchange) throws java.io.IOException {
        String id = exchange.getMessage().getHeader("id", String.class);
        io.tesseraql.core.files.FileTransferService.TransferStatus status = transfers
                .status(id).orElse(null);
        if (status == null || !scope(exchange).test(status.appName())) {
            throw notFound("Transfer '" + id + "'");
        }
        io.tesseraql.core.files.FileTransferService.Download download = transfers.download(id)
                .orElse(null);
        if (download == null) {
            // TQL-LD-2823 (409): the transfer exists but has no downloadable file yet — the
            // same refusal a route-level download answers.
            throw TqlException.builder(new io.tesseraql.core.error.TqlErrorCode(
                    io.tesseraql.core.error.TqlDomain.LD, 2823))
                    .message("Transfer '" + id + "' has no downloadable file")
                    .build();
        }
        exchange.getMessage().removeHeaders("*");
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, download.contentType());
        exchange.getMessage().setHeader("Content-Disposition", "attachment; filename=\""
                + download.filename().replaceAll("[\\r\\n\"]", "_") + "\"");
        exchange.getMessage().setBody(download.content());
    }

    // ---- wire shapes: ISO-8601 timestamps only (docs/vocabulary-cleanup.md slice 3) ----

    private static <T> List<Map<String, Object>> mapList(List<T> items,
            java.util.function.Function<T, Map<String, Object>> mapper) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (T item : items) {
            out.add(mapper.apply(item));
        }
        return out;
    }

    private static String iso(long epochMs) {
        return java.time.Instant.ofEpochMilli(epochMs).toString();
    }

    private static Map<String, Object> sqlExecutionWire(io.tesseraql.core.diag.SqlExecution e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sqlId", e.sqlId());
        map.put("mode", e.mode());
        map.put("durationMs", e.durationMs());
        map.put("rowCount", e.rowCount());
        map.put("startedAt", iso(e.startedAtEpochMs()));
        return map;
    }

    private static Map<String, Object> spanWire(io.tesseraql.core.telemetry.SpanSample span) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", span.name());
        map.put("traceId", span.traceId());
        map.put("spanId", span.spanId());
        map.put("parentSpanId", span.parentSpanId());
        map.put("attributes", span.attributes());
        map.put("durationMs", span.durationMs());
        map.put("error", span.error());
        map.put("startedAt", iso(span.startedAtEpochMs()));
        return map;
    }

    private static Map<String, Object> traceNodeWire(
            io.tesseraql.opsui.OpsDashboard.TraceNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("span", spanWire(node.span()));
        map.put("durationMs", node.durationMs());
        map.put("selfMs", node.selfMs());
        map.put("startedAt", node.startedAt());
        map.put("slow", node.slow());
        map.put("children", mapList(node.children(), OperationsRouteBuilder::traceNodeWire));
        return map;
    }

    private static Map<String, Object> pinningWire(
            io.tesseraql.opsui.OpsDashboard.PinningSummary summary) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("count", summary.count());
        map.put("recent", mapList(summary.recent(), event -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("carrierThread", event.carrierThread());
            row.put("durationMs", event.durationMs());
            row.put("topFrame", event.topFrame());
            row.put("at", iso(event.atEpochMs()));
            return row;
        }));
        return map;
    }
}
