package io.tesseraql.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.HttpMounts;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.operations.batch.StepExecution;
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
 * 43.7), and the browser-face console data endpoints under {@code /_tesseraql/ops/data} the stack
 * shell delegates to (docs/stack-shells.md structural decision 2).
 *
 * <p>Authorization is the atoms, checked here rather than through a deployment-declared policy —
 * a framework surface checks atoms, never roles (docs/stack-shells.md structural decision 1).
 * Data attributed to an app (jobs, executions, traces) narrows to the caller's
 * {@code tql.ops.view.<name>} grants, deny by default; actions require
 * {@code tql.ops.run.<name>}, and out-of-scope reads exactly like unknown. Runtime-wide
 * diagnostics (lanes, slow SQL, pinning, aggregate metrics, alerts) describe the shared substrate
 * and open to any holder of any {@code tql.ops.view} grant.
 */
final class OperationsRouteBuilder extends RouteBuilder {

    private static final String VIEW = "tesseraql-auth:authenticate?auth=bearer";
    private static final String BROWSER = "tesseraql-auth:authenticate?auth=browser";
    private static final String CSRF = "tesseraql-auth:csrf";

    /** The app's code catalogs, or null when it declares none (docs/lookups.md, decision 14). */
    private static io.tesseraql.core.catalog.CatalogStore catalogStore(
            org.apache.camel.Exchange exchange) {
        return exchange.getContext().getRegistry().lookupByNameAndType(
                io.tesseraql.camel.TesseraqlProperties.CATALOG_STORE_BEAN,
                io.tesseraql.core.catalog.CatalogStore.class);
    }

    /**
     * One catalog's hold as JSON. {@code loadedAt} is null and {@code codes} is -1 for a
     * catalog nothing has asked for yet — the status surface reports the hold, it never takes
     * one, so "never loaded" stays visible instead of being caused by looking.
     */
    private static Map<String, Object> catalogStatusMap(
            io.tesseraql.core.catalog.CatalogStore.Status status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", status.name());
        row.put("tables", status.tables());
        row.put("loaded", status.loadedAt() != null);
        row.put("codes", status.codes() < 0 ? null : status.codes());
        row.put("languages", status.languages());
        row.put("loadedAt", status.loadedAt() == null
                ? null
                : java.time.Instant.ofEpochMilli(status.loadedAt()).toString());
        row.put("lastError", status.lastError());
        return row;
    }

    private final ObjectMapper mapper = new ObjectMapper();
    /** The shared find/scope/act cores both the JSON API and the console providers call. */
    private final OpsActions actions;
    private final JobRepository repository;
    private final Map<String, String> jobOwners;
    private final Map<String, io.tesseraql.yaml.model.JobDefinition> definitions;
    private final io.tesseraql.opsui.OpsDashboard dashboard;
    private final MetricsSettings metrics;
    private final io.tesseraql.operations.audit.JdbcRouteAuditStore routeAudit;
    private final io.tesseraql.core.files.FileTransferService transfers;

    /**
     * The Prometheus exposition settings (roadmap Phase 45): opt-in, bearer-gated default.
     * {@code pollSources} joins the scrape as gauge families rendered from the registry at
     * scrape time (docs/poll-source-metrics.md).
     */
    record MetricsSettings(boolean enabled, boolean unauthenticated,
            io.tesseraql.core.telemetry.AggregatingMeter meter,
            io.tesseraql.opsui.PollSourceStatus pollSources,
            io.tesseraql.opsui.RuntimeMetrics runtime) {
    }

    OperationsRouteBuilder(OpsActions actions, JobRepository repository,
            Map<String, String> jobOwners,
            Map<String, io.tesseraql.yaml.model.JobDefinition> definitions,
            io.tesseraql.opsui.OpsDashboard dashboard, MetricsSettings metrics,
            io.tesseraql.operations.audit.JdbcRouteAuditStore routeAudit,
            io.tesseraql.core.files.FileTransferService transfers) {
        this.actions = actions;
        this.repository = repository;
        this.transfers = transfers;
        // Job id -> owning app, insertion-ordered so the job list keeps its declaration order.
        this.jobOwners = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(jobOwners));
        this.definitions = java.util.Collections
                .unmodifiableMap(new LinkedHashMap<>(definitions));
        this.dashboard = dashboard;
        this.metrics = metrics;
        this.routeAudit = routeAudit;
    }

    @Override
    public void configure() {
        onException(TqlException.class).handled(true).process(new ErrorResponseRenderer());
        onException(Exception.class).handled(true).process(new ErrorResponseRenderer());

        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/batch/jobs",
                "direct:ops.batch.jobs");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/batch/executions",
                "direct:ops.batch.executions");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/batch/executions/{id}",
                "direct:ops.batch.executionDetail");
        HttpMounts.mount(getContext(), "POST", "/_tesseraql/ops/batch/jobs/{jobId}/run",
                "direct:ops.batch.run");
        HttpMounts.mount(getContext(), "POST", "/_tesseraql/ops/batch/executions/{id}/cancel",
                "direct:ops.batch.cancel");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/batch/transfers/{id}/file",
                "direct:ops.batch.transferFile");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/console/transfers/{id}/file",
                "direct:ops.console.transferFile");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/overview", "direct:ops.overview");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/lanes", "direct:ops.lanes");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/slow-sql", "direct:ops.slowSql");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/traces", "direct:ops.traces");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/traces/tree",
                "direct:ops.traceTree");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/traces/summary",
                "direct:ops.traceSummary");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/traces/metrics",
                "direct:ops.traceMetrics");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/alerts", "direct:ops.alerts");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/pinning", "direct:ops.pinning");
        // The business-route audit trail read surface (roadmap Phase 45): bearer-gated and
        // narrowed to the caller's tql.ops.view.<name> grants like every ops read.
        if (routeAudit != null) {
            HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/audit", "direct:ops.audit");
            from("direct:ops.audit").routeId("ops.audit")
                    .to(VIEW).process(requireAnyOpsView())
                    .process(jsonProcessor(
                            exchange -> routeAudit.recent(200, viewScope(exchange))));
        }

        // What each code catalog holds and a manual refresh (docs/lookups.md, decision 14).
        // The store is looked up per request rather than injected: an app with no catalogs/
        // simply answers an empty list, and the endpoints do not depend on start-up order.
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/catalogs", "direct:ops.catalogs");
        HttpMounts.mount(getContext(), "POST", "/_tesseraql/ops/catalogs/{name}/refresh",
                "direct:ops.catalogs.refresh");
        // The outbox delivery log and dead-letter redelivery (roadmap Phase 20).
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/outbox", "direct:ops.outbox");
        HttpMounts.mount(getContext(), "POST", "/_tesseraql/ops/outbox/{id}/redeliver",
                "direct:ops.outbox.redeliver");
        // The messaging-channel queue event log and dead-letter redelivery, mirroring the
        // outbox surface (docs/silent-tolerance.md O1).
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/events", "direct:ops.events");
        HttpMounts.mount(getContext(), "POST", "/_tesseraql/ops/events/{id}/redeliver",
                "direct:ops.events.redeliver");
        // Health for load balancers and deploy tooling (roadmap Phase 45): unauthenticated by
        // design, exposing only the status word - details stay behind the authorized ops API.
        // /health/live is pure liveness (the process answers; never touches a dependency);
        // /health/ready and the bare /health run the full roll-up incl. the datasource probe
        // and answer 503 on DOWN, so traffic actually sheds when the app cannot serve.
        // Liveness and readiness are not routes: they are answered on the platform router,
        // off the roll-up the dashboard already holds, so a saturated runtime can still say that
        // it is saturated (docs/http-threading.md decision 3, and see HealthRoutes).

        // The Prometheus text exposition (roadmap Phase 45, decision point 9): opt-in, and
        // bearer + ops.metrics.view policy by default — metric labels reveal route ids, so
        // the scrape is authorized like the rest of the ops API unless the operator
        // explicitly opts a cluster-internal scraper out of auth.
        if (metrics != null && metrics.enabled()) {
            HttpMounts.mount(getContext(), "GET", "/_tesseraql/metrics", "direct:ops.metrics");
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
                                java.time.Instant.now())
                // Heap, GC, threads and the pool (docs/audit-hardening.md Decision 9):
                // request rates and latency histograms could not answer "is it out of
                // heap" or "is the pool exhausted", which is what gets asked first.
                        + (metrics.runtime() == null ? "" : metrics.runtime().render()));
            });
        }

        from("direct:ops.batch.jobs").routeId("ops.batch.jobs")
                .to(VIEW).process(requireAnyOpsView())
                .process(jsonProcessor(exchange -> {
                    Predicate<String> scope = viewScope(exchange);
                    // Objects since 0.11 (docs/jobs.md): the trigger story and the
                    // operational promises, so the API is at least as told as the CLI.
                    return jobOwners.entrySet().stream()
                            .filter(entry -> scope.test(entry.getValue()))
                            .map(entry -> jobMap(entry.getKey(), entry.getValue()))
                            .toList();
                }));

        from("direct:ops.batch.executions").routeId("ops.batch.executions")
                .to(VIEW).process(requireAnyOpsView())
                .process(jsonProcessor(exchange -> {
                    Predicate<String> scope = viewScope(exchange);
                    return repository.listExecutions(50).stream()
                            .filter(execution -> scope.test(execution.appName()))
                            .map(this::executionMap)
                            .toList();
                }));

        from("direct:ops.batch.executionDetail").routeId("ops.batch.executionDetail")
                .to(VIEW).process(requireAnyOpsView())
                .process(jsonProcessor(this::executionDetail));

        from("direct:ops.batch.run").routeId("ops.batch.run")
                .to(VIEW)
                .process(jsonProcessor(this::runJob));

        // The cooperative stop (docs/jobs.md "Stopping a run"): sets the flag the running
        // executor polls at step and chunk-commit boundaries; gated like starting a run.
        from("direct:ops.batch.cancel").routeId("ops.batch.cancel")
                .to(VIEW)
                .process(jsonProcessor(this::cancelExecution));

        // A job-produced export has no route-scoped download URL, so it is fetched here
        // (docs/analytics-experience.md track 3) — view-gated and app-scoped like the
        // transfers listing; unknown and out-of-scope read the same (TQL-BATCH-4040). Two
        // faces, one handler: the API for machine callers, the console for the browser
        // session behind the transfers page.
        from("direct:ops.batch.transferFile").routeId("ops.batch.transferFile")
                .to(VIEW).process(requireAnyOpsView())
                .process(this::transferFile);
        from("direct:ops.console.transferFile").routeId("ops.console.transferFile")
                .to(BROWSER).process(requireAnyOpsView())
                .process(this::transferFile);

        from("direct:ops.overview").routeId("ops.overview")
                .to(VIEW).process(requireAnyOpsView())
                .process(jsonProcessor(exchange -> dashboard.overview(20, viewScope(exchange))));

        from("direct:ops.lanes").routeId("ops.lanes")
                .to(VIEW).process(requireAnyOpsView())
                .process(jsonProcessor(exchange -> dashboard.overview(0).lanes()));

        from("direct:ops.slowSql").routeId("ops.slowSql")
                .to(VIEW).process(requireAnyOpsView())
                .process(jsonProcessor(exchange -> mapList(dashboard.slowSql(),
                        OperationsRouteBuilder::sqlExecutionWire)));

        from("direct:ops.traces").routeId("ops.traces")
                .to(VIEW).process(requireAnyOpsView())
                .process(jsonProcessor(exchange -> mapList(dashboard.traces(viewScope(exchange)),
                        OperationsRouteBuilder::spanWire)));

        from("direct:ops.traceTree").routeId("ops.traceTree")
                .to(VIEW).process(requireAnyOpsView())
                .process(jsonProcessor(exchange -> mapList(dashboard.traceTree(viewScope(exchange)),
                        OperationsRouteBuilder::traceNodeWire)));

        from("direct:ops.traceSummary").routeId("ops.traceSummary")
                .to(VIEW).process(requireAnyOpsView())
                .process(jsonProcessor(exchange -> dashboard.traceSummaries(
                        exchange.getMessage().getHeader("filter", String.class),
                        viewScope(exchange))));

        from("direct:ops.traceMetrics").routeId("ops.traceMetrics")
                .to(VIEW).process(requireAnyOpsView())
                .process(jsonProcessor(exchange -> dashboard.traceMetrics()));

        from("direct:ops.alerts").routeId("ops.alerts")
                .to(VIEW).process(requireAnyOpsView())
                .process(jsonProcessor(exchange -> dashboard.alerts()));

        from("direct:ops.pinning").routeId("ops.pinning")
                .to(VIEW).process(requireAnyOpsView())
                .process(jsonProcessor(exchange -> pinningWire(dashboard.pinning())));

        from("direct:ops.catalogs").routeId("ops.catalogs")
                .to(VIEW).process(requireAnyOpsView())
                .process(jsonProcessor(exchange -> {
                    io.tesseraql.core.catalog.CatalogStore store = catalogStore(exchange);
                    return store == null
                            ? List.of()
                            : store.status().stream()
                                    .map(OperationsRouteBuilder::catalogStatusMap).toList();
                }));

        from("direct:ops.catalogs.refresh").routeId("ops.catalogs.refresh")
                .to(VIEW)
                .process(jsonProcessor(exchange -> {
                    io.tesseraql.core.catalog.CatalogStore store = catalogStore(exchange);
                    String name = exchange.getMessage().getHeader("name", String.class);
                    // A catalog belongs to the application this runtime serves, so refreshing
                    // one is acting on that application: tql.ops.run.<thisApp>, and out of
                    // scope reads exactly like unknown.
                    if (store == null || !runScope(exchange).test(actions.mainApp())
                            || store.status().stream()
                                    .noneMatch(status -> status.name().equals(name))) {
                        throw OpsActions.notFound("Catalog '" + name + "'");
                    }
                    // reload() re-reads whatever the hold says, which is the whole point of a
                    // manual refresh: an operator presses it because the source changed
                    // somewhere the app could not see.
                    store.reload(name);
                    return store.status().stream()
                            .filter(status -> status.name().equals(name))
                            .map(OperationsRouteBuilder::catalogStatusMap).findFirst()
                            .orElseThrow();
                }));

        from("direct:ops.outbox").routeId("ops.outbox")
                .to(VIEW).process(requireAnyOpsView())
                .process(jsonProcessor(exchange -> mapList(
                        actions.recentOutbox(viewScope(exchange)), this::outboxEventMap)));

        from("direct:ops.outbox.redeliver").routeId("ops.outbox.redeliver")
                .to(VIEW)
                .process(jsonProcessor(this::redeliverOutboxEvent));

        from("direct:ops.events").routeId("ops.events")
                .to(VIEW).process(requireAnyOpsView())
                .process(jsonProcessor(exchange -> mapList(
                        actions.recentEvents(viewScope(exchange)), this::channelEventMap)));

        from("direct:ops.events.redeliver").routeId("ops.events.redeliver")
                .to(VIEW)
                .process(jsonProcessor(this::redeliverChannelEvent));

        // --- The stack shell's delegation face (docs/stack-shells.md structural decision 2) ---
        // Browser-authenticated JSON endpoints answering the same template-ready view models the
        // console's ops.* providers shape, so the origin shell can render this member's pages
        // without the member carrying the console's chrome. The session store is shared across
        // the stack, so the shell forwards the caller's own cookie and this runtime
        // authenticates the same principal and re-runs its own grant checks — the shell adds
        // reach, never authority. A caller without tql.ops.view.<thisApp> is refused with the
        // 404-shaped TQL-BATCH-4040, the same answer an unknown resource gives.
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/data/overview",
                "direct:ops.data.overview");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/data/jobs", "direct:ops.data.jobs");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/data/traces",
                "direct:ops.data.traces");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/data/transfers",
                "direct:ops.data.transfers");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/data/outbox",
                "direct:ops.data.outbox");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/data/events",
                "direct:ops.data.events");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/data/audit",
                "direct:ops.data.audit");
        HttpMounts.mount(getContext(), "GET", "/_tesseraql/ops/data/executions/{id}",
                "direct:ops.data.execution");
        HttpMounts.mount(getContext(), "POST", "/_tesseraql/ops/data/jobs/run",
                "direct:ops.data.jobRun");
        HttpMounts.mount(getContext(), "POST", "/_tesseraql/ops/data/outbox/{id}/redeliver",
                "direct:ops.data.outboxRedeliver");
        HttpMounts.mount(getContext(), "POST", "/_tesseraql/ops/data/events/{id}/redeliver",
                "direct:ops.data.eventsRedeliver");

        from("direct:ops.data.overview").routeId("ops.data.overview")
                .to(BROWSER).process(requireMemberView())
                .process(jsonProcessor(
                        exchange -> invokeProvider(exchange, "ops.overview", Map.of())));
        from("direct:ops.data.jobs").routeId("ops.data.jobs")
                .to(BROWSER).process(requireMemberView())
                .process(jsonProcessor(
                        exchange -> invokeProvider(exchange, "ops.jobs", Map.of())));
        from("direct:ops.data.traces").routeId("ops.data.traces")
                .to(BROWSER).process(requireMemberView())
                .process(jsonProcessor(
                        exchange -> invokeProvider(exchange, "ops.traces", Map.of())));
        from("direct:ops.data.transfers").routeId("ops.data.transfers")
                .to(BROWSER).process(requireMemberView())
                .process(jsonProcessor(
                        exchange -> invokeProvider(exchange, "ops.transfers", Map.of())));
        from("direct:ops.data.outbox").routeId("ops.data.outbox")
                .to(BROWSER).process(requireMemberView())
                .process(jsonProcessor(
                        exchange -> invokeProvider(exchange, "ops.outbox", Map.of())));
        from("direct:ops.data.events").routeId("ops.data.events")
                .to(BROWSER).process(requireMemberView())
                .process(jsonProcessor(
                        exchange -> invokeProvider(exchange, "ops.events", Map.of())));
        from("direct:ops.data.audit").routeId("ops.data.audit")
                .to(BROWSER).process(requireMemberView())
                .process(jsonProcessor(exchange -> invokeProvider(exchange, "ops.audit",
                        headerParams(exchange, "route", "actor", "status"))));
        from("direct:ops.data.execution").routeId("ops.data.execution")
                .to(BROWSER).process(requireMemberView())
                .process(jsonProcessor(exchange -> invokeProvider(exchange, "ops.execution",
                        headerParams(exchange, "id"))));
        // Actions: CSRF-validated (the shell forwards the caller's X-CSRF-Token beside the
        // cookie), and the tql.ops.run.<name> check lives in the provider's run scope — out of
        // scope reads exactly like unknown.
        from("direct:ops.data.jobRun").routeId("ops.data.jobRun")
                .to(BROWSER).to(CSRF)
                .process(jsonProcessor(exchange -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    if (exchange.getMessage().getBody() instanceof Map<?, ?> form) {
                        form.forEach((key, value) -> values.put(String.valueOf(key), value));
                    }
                    Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL,
                            Principal.class);
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("id", exchange.getMessage().getHeader("id", String.class));
                    params.put("values", values);
                    params.put("actor", principal == null ? null : principal.loginId());
                    return invokeProvider(exchange, "ops.jobRun", params);
                }));
        from("direct:ops.data.outboxRedeliver").routeId("ops.data.outboxRedeliver")
                .to(BROWSER).to(CSRF)
                .process(jsonProcessor(exchange -> invokeProvider(exchange,
                        "ops.outboxRedeliver", headerParams(exchange, "id"))));
        from("direct:ops.data.eventsRedeliver").routeId("ops.data.eventsRedeliver")
                .to(BROWSER).to(CSRF)
                .process(jsonProcessor(exchange -> invokeProvider(exchange,
                        "ops.eventsRedeliver", headerParams(exchange, "id"))));
    }

    /**
     * The delegation face's own fence: the caller must hold {@code tql.ops.view.<thisApp>} —
     * refused with the 404-shaped TQL-BATCH-4040 so an out-of-scope member reads exactly like
     * an unknown one, whichever side of the shell the probe comes from.
     */
    private Processor requireMemberView() {
        return exchange -> {
            if (!viewScope(exchange).test(actions.mainApp())) {
                throw OpsActions.notFound("Application '" + actions.mainApp() + "'");
            }
        };
    }

    /** The console's view-model provider, invoked with the session principal's own facts. */
    private Object invokeProvider(Exchange exchange, String name, Map<String, Object> extra) {
        io.tesseraql.core.service.ServiceProviders providers = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.SERVICE_PROVIDERS_BEAN,
                        io.tesseraql.core.service.ServiceProviders.class);
        Map<String, Object> params = new LinkedHashMap<>(extra);
        params.put("permissions", permissions(exchange));
        return providers.require(name).invoke(params);
    }

    /** The named headers (path/query/form values) as provider params, nulls kept out. */
    private static Map<String, Object> headerParams(Exchange exchange, String... names) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (String name : names) {
            Object value = exchange.getMessage().getHeader(name);
            if (value != null) {
                params.put(name, value);
            }
        }
        return params;
    }

    /** Requeues a FAILED/DEAD event; outside the caller's scope it reads as unknown. */
    private Object redeliverOutboxEvent(Exchange exchange) {
        return actions.redeliverOutbox(
                exchange.getMessage().getHeader("id", String.class), runScope(exchange));
    }

    /** Requeues a DEAD queue message; outside the caller's scope it reads as unknown. */
    private Object redeliverChannelEvent(Exchange exchange) {
        return actions.redeliverEvent(
                exchange.getMessage().getHeader("id", String.class), runScope(exchange));
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

    private static Object permissions(Exchange exchange) {
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL, Principal.class);
        return principal == null ? null : principal.permissions();
    }

    /**
     * The caller's per-app view scope: what this runtime serves, narrowed by the principal's
     * {@code tql.ops.view.<name>} grants (docs/stack-shells.md structural decision 1).
     */
    private Predicate<String> viewScope(Exchange exchange) {
        return actions.viewScope(permissions(exchange));
    }

    /** The caller's per-app run scope — acting, not seeing ({@code tql.ops.run.<name>}). */
    private Predicate<String> runScope(Exchange exchange) {
        return actions.runScope(permissions(exchange));
    }

    /**
     * The entry gate for data that belongs to no single application — lanes, slow SQL, pinning,
     * aggregate trace metrics, alerts, the catalog listing. They describe the shared substrate
     * the caller's applications run on, so any {@code tql.ops.view} grant opens them; a caller
     * with none is refused rather than shown an empty page, exactly as the retired entry
     * permission refused (TQL-SEC-4031).
     */
    private Processor requireAnyOpsView() {
        return exchange -> {
            if (!io.tesseraql.opsui.OpsScope.holdsAnyView(permissions(exchange))) {
                throw new TqlException(io.tesseraql.security.policy.PolicyEngine.FORBIDDEN,
                        "Principal holds no tql.ops.view grant (deny by default)");
            }
        };
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
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL, Principal.class);
        JobExecution execution = actions.runJob(jobId, () -> parseBody(exchange),
                principal == null ? null : principal.loginId(), runScope(exchange));
        // Work accepted, poll the execution: the same 202 + Location contract the
        // file-transfer start answers (docs/vocabulary-cleanup.md slice 3).
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 202);
        exchange.getMessage().setHeader("Location", io.tesseraql.camel.BasePath.url(exchange,
                "/_tesseraql/ops/batch/executions/" + execution.id()));
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
        JobExecution execution = actions.findExecution(id, runScope(exchange));
        if (execution == null) {
            throw OpsActions.notFound("Execution '" + id + "'");
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
        JobExecution execution = actions.findExecution(id, viewScope(exchange));
        if (execution == null) {
            throw OpsActions.notFound("Execution '" + id + "'");
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
     * transfers outside the caller's {@code tql.ops.view.<name>} scope read the same 404; a
     * transfer that is not a completed export is a 409 ({@code TQL-LD-2823}, the route
     * download's refusal).
     */
    private void transferFile(Exchange exchange) throws java.io.IOException {
        String id = exchange.getMessage().getHeader("id", String.class);
        io.tesseraql.core.files.FileTransferService.TransferStatus status = transfers
                .status(id).orElse(null);
        if (status == null || !viewScope(exchange).test(status.appName())) {
            throw OpsActions.notFound("Transfer '" + id + "'");
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
