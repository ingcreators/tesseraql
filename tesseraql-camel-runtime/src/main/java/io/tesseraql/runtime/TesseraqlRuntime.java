package io.tesseraql.runtime;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.RouteCompiler;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.PasswordAuthenticator;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobExecutor;
import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.operations.idempotency.JdbcIdempotencyStore;
import io.tesseraql.operations.outbox.JdbcOutboxStore;
import io.tesseraql.operations.outbox.OutboxDispatcher;
import io.tesseraql.security.SecurityConfig;
import io.tesseraql.security.jwt.JwtAuthenticator;
import io.tesseraql.security.policy.PolicyEngine;
import io.tesseraql.security.session.SessionStore;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.CamelContext;
import org.apache.camel.component.platform.http.vertx.VertxPlatformHttpServer;
import org.apache.camel.component.platform.http.vertx.VertxPlatformHttpServerConfiguration;
import org.apache.camel.impl.DefaultCamelContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Camel Main based TesseraQL runtime (design ch. 19.2).
 *
 * <p>Loads an external app home, wires datasources and the embedded HTTP server, compiles the
 * Simple YAML routes into Camel routes, and starts the context. The {@code tesseraql-sql} component
 * is discovered from the classpath service descriptor.
 */
public final class TesseraqlRuntime implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TesseraqlRuntime.class);
    private static final io.tesseraql.core.outbox.OutboxEventSink LOGGING_SINK = event -> LOG
            .info("Outbox delivered {} {}", event.eventType(), event.id());
    private static final io.tesseraql.core.error.TqlErrorCode DUPLICATE_JOB = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.APP, 4202);

    private final CamelContext camelContext;
    private final Map<String, HikariDataSource> dataSources;
    private final HikariDataSource mainDataSource;
    private final int port;
    private final JobRepository jobRepository;
    private final JobExecutor jobExecutor;
    private final JdbcOutboxStore outboxStore;
    private final Map<String, JobFile> jobs;
    private final Map<String, String> jobOwners;
    private final String appName;
    private final java.util.Set<String> hostedApps;
    private final io.tesseraql.core.threading.ExecutionLanes executionLanes;
    private final TenantDataSources tenantDataSources;
    private final io.tesseraql.yaml.config.AppConfig config;
    private final AutoCloseable pinningSource;
    private final AutoCloseable otelSdk;
    private final io.tesseraql.opsui.OpsDashboard opsDashboard;
    private final io.tesseraql.core.outbox.OutboxEventSink outboxSink;

    private TesseraqlRuntime(CamelContext camelContext, Map<String, HikariDataSource> dataSources,
            int port,
            JobRepository jobRepository, JobExecutor jobExecutor, JdbcOutboxStore outboxStore,
            Map<String, JobFile> jobs, Map<String, String> jobOwners, String appName,
            java.util.Set<String> hostedApps,
            io.tesseraql.core.threading.ExecutionLanes executionLanes,
            TenantDataSources tenantDataSources, io.tesseraql.yaml.config.AppConfig config,
            AutoCloseable pinningSource, AutoCloseable otelSdk,
            io.tesseraql.opsui.OpsDashboard opsDashboard,
            io.tesseraql.core.outbox.OutboxEventSink outboxSink) {
        this.camelContext = camelContext;
        this.dataSources = dataSources;
        this.mainDataSource = dataSources.get("main");
        this.jobOwners = Map.copyOf(jobOwners);
        this.hostedApps = java.util.Set.copyOf(hostedApps);
        this.port = port;
        this.jobRepository = jobRepository;
        this.jobExecutor = jobExecutor;
        this.outboxStore = outboxStore;
        this.jobs = jobs;
        this.appName = appName;
        this.executionLanes = executionLanes;
        this.tenantDataSources = tenantDataSources;
        this.config = config;
        this.pinningSource = pinningSource;
        this.otelSdk = otelSdk;
        this.opsDashboard = opsDashboard;
        this.outboxSink = outboxSink;
    }

    /** The operations dashboard for this runtime (health, metrics, traces, alerts). */
    public io.tesseraql.opsui.OpsDashboard opsDashboard() {
        return opsDashboard;
    }

    /** Starts the runtime against {@code appHome}, using the configured {@code server.port}. */
    public static TesseraqlRuntime start(Path appHome) {
        return start(appHome, (DataSources.MainDatasourceOverride) null);
    }

    /**
     * Starts the runtime against {@code appHome} on the configured port, pointing the {@code main}
     * datasource at {@code override} when non-null (the {@code serve --embedded-db} path).
     */
    public static TesseraqlRuntime start(Path appHome,
            DataSources.MainDatasourceOverride override) {
        AppManifest manifest = new ManifestLoader().load(appHome);
        int port = manifest.config().getString("server.port").map(Integer::parseInt).orElse(8080);
        return start(appHome, manifest, port, new io.tesseraql.core.telemetry.RingTracer(100),
                io.tesseraql.core.telemetry.NoopMeter.INSTANCE, override);
    }

    /** Starts the runtime against {@code appHome} on an explicit port (used by tests). */
    public static TesseraqlRuntime start(Path appHome, int port) {
        return start(appHome, port, (DataSources.MainDatasourceOverride) null);
    }

    /** Starts the runtime on an explicit port, with the {@code main} datasource override applied. */
    public static TesseraqlRuntime start(Path appHome, int port,
            DataSources.MainDatasourceOverride override) {
        return start(appHome, new ManifestLoader().load(appHome), port,
                new io.tesseraql.core.telemetry.RingTracer(100),
                io.tesseraql.core.telemetry.NoopMeter.INSTANCE, override);
    }

    /** Starts the runtime with an explicit tracer (used to wire observability). */
    public static TesseraqlRuntime start(Path appHome, int port,
            io.tesseraql.core.telemetry.Tracer tracer) {
        return start(appHome, new ManifestLoader().load(appHome), port, tracer,
                io.tesseraql.core.telemetry.NoopMeter.INSTANCE, null);
    }

    /** Starts the runtime with an explicit tracer and meter (used to wire observability). */
    public static TesseraqlRuntime start(Path appHome, int port,
            io.tesseraql.core.telemetry.Tracer tracer, io.tesseraql.core.telemetry.Meter meter) {
        return start(appHome, new ManifestLoader().load(appHome), port, tracer, meter, null);
    }

    private static TesseraqlRuntime start(Path appHome, AppManifest manifest, int port,
            io.tesseraql.core.telemetry.Tracer tracer, io.tesseraql.core.telemetry.Meter meter,
            DataSources.MainDatasourceOverride override) {
        DefaultCamelContext context = new DefaultCamelContext();
        // Every datasource declared under tesseraql.datasources gets a pool, registered by name
        // so routes, contracts and per-datasource migrations can address it (design ch. 5.2).
        Map<String, HikariDataSource> dataSources = DataSources.createAll(manifest.config(),
                override);
        HikariDataSource dataSource = dataSources.get("main");
        dataSources.forEach((name, pool) -> context.getRegistry().bind(name, pool));

        // OTLP export (design ch. 25.7): when an endpoint is configured, fan spans out to OTLP
        // alongside the in-process ring and export metrics via OpenTelemetry.
        io.tesseraql.core.telemetry.Tracer effectiveTracer = tracer;
        io.tesseraql.core.telemetry.Meter effectiveMeter = meter;
        AutoCloseable otelSdk = null;
        String otlpEndpoint = manifest.config().getString("tesseraql.otel.otlp.endpoint")
                .orElse(null);
        if (otlpEndpoint != null && !otlpEndpoint.isBlank()) {
            String serviceName = manifest.config().getString("tesseraql.otel.serviceName")
                    .or(() -> manifest.config().getString("tesseraql.app.name"))
                    .orElse("tesseraql");
            io.opentelemetry.sdk.OpenTelemetrySdk sdk = io.tesseraql.observability.OpenTelemetrySupport
                    .otlp(otlpEndpoint, serviceName);
            otelSdk = sdk;
            effectiveTracer = new io.tesseraql.core.telemetry.CompositeTracer(
                    tracer, new io.tesseraql.observability.OpenTelemetryTracer(sdk));
            effectiveMeter = new io.tesseraql.observability.OpenTelemetryMeter(sdk);
        }
        context.getRegistry().bind(TesseraqlProperties.TRACER_BEAN, effectiveTracer);
        context.getRegistry().bind(TesseraqlProperties.METER_BEAN, effectiveMeter);

        io.tesseraql.core.threading.ExecutionLanes lanes = LaneConfigs.load(manifest.config());
        context.getRegistry().bind(TesseraqlProperties.LANES_BEAN, lanes);
        for (io.tesseraql.core.threading.Lane lane : lanes.all()) {
            context.getRegistry().bind(
                    TesseraqlProperties.laneExecutorRef(lane.name()), lane.executor());
        }

        int slowSqlCapacity = manifest.config().getString("tesseraql.diagnostics.slowSqlCapacity")
                .map(Integer::parseInt).orElse(100);
        long slowSqlMillis = manifest.config().getString("tesseraql.diagnostics.slowSqlMillis")
                .map(Long::parseLong).orElse(200L);
        io.tesseraql.core.diag.RingSqlExecutionLog slowSqlLog = new io.tesseraql.core.diag.RingSqlExecutionLog(
                slowSqlCapacity, slowSqlMillis);
        context.getRegistry().bind(TesseraqlProperties.SLOW_SQL_LOG_BEAN, slowSqlLog);

        io.tesseraql.core.diag.PinningMonitor pinningMonitor = new io.tesseraql.core.diag.PinningMonitor(
                100);
        io.tesseraql.core.diag.JfrPinningSource pinningSource = null;
        if (manifest.config().getString("tesseraql.diagnostics.pinning.enabled")
                .map(Boolean::parseBoolean).orElse(false)) {
            long pinMs = manifest.config()
                    .getString("tesseraql.diagnostics.pinning.thresholdMillis")
                    .map(Long::parseLong).orElse(20L);
            pinningSource = new io.tesseraql.core.diag.JfrPinningSource(
                    pinningMonitor, java.time.Duration.ofMillis(pinMs));
        }

        TenantDataSources tenantDataSources = TenantDataSources.load(manifest.config());
        if (!tenantDataSources.isEmpty()) {
            context.getRegistry().bind(
                    TesseraqlProperties.TENANT_DATASOURCE_RESOLVER_BEAN, tenantDataSources);
        }

        SecurityConfig security = SecurityConfigFactory.build(manifest.config());
        context.getRegistry().bind(TesseraqlProperties.POLICY_ENGINE_BEAN,
                new PolicyEngine(security));
        // Organizational data scoping (roadmap Phase 29): the resolver expands /*%scope ... */
        // into principal-derived predicates. Bound only when the app declares scopes, so the SQL
        // producer falls back to its reject-any-scope default everywhere else.
        if (!manifest.scopes().isEmpty()) {
            context.getRegistry().bind(TesseraqlProperties.SCOPE_RESOLVER_BEAN,
                    new io.tesseraql.compiler.binding.CompiledScopeResolver(
                            manifest.scopes(), datasourceDialect(manifest.config())));
        }
        if (security.jwt() != null) {
            context.getRegistry().bind(
                    TesseraqlProperties.JWT_AUTHENTICATOR_BEAN,
                    new JwtAuthenticator(security.jwt()));
        }
        if (security.apiKeys() != null) {
            context.getRegistry().bind(
                    TesseraqlProperties.API_KEY_AUTHENTICATOR_BEAN,
                    new io.tesseraql.security.apikey.ApiKeyAuthenticator(security.apiKeys()));
        }
        if (security.mtls() != null) {
            context.getRegistry().bind(
                    TesseraqlProperties.MTLS_AUTHENTICATOR_BEAN,
                    new io.tesseraql.security.mtls.MtlsAuthenticator(security.mtls()));
        }
        // Browser sessions: in-memory per node by default; "jdbc" shares tql_session across all
        // runtime nodes so a login made on one node resolves on every other (design ch. 11.2).
        SessionStore sessionStore;
        if ("jdbc".equalsIgnoreCase(
                manifest.config().getString("tesseraql.sessions.store").orElse("memory"))) {
            io.tesseraql.security.session.JdbcSessionStore jdbcSessions = new io.tesseraql.security.session.JdbcSessionStore(
                    dataSource,
                    java.time.Duration.ofMillis(io.tesseraql.core.util.Durations.toMillis(
                            manifest.config().getString("tesseraql.sessions.ttl")
                                    .orElse("12h"))));
            jdbcSessions.ensureSchema();
            sessionStore = jdbcSessions;
        } else {
            sessionStore = new io.tesseraql.security.session.InMemorySessionStore();
        }
        context.getRegistry().bind(TesseraqlProperties.SESSION_STORE_BEAN, sessionStore);
        io.tesseraql.core.spool.FileTempStore tempStore = new io.tesseraql.core.spool.FileTempStore(
                appHome.resolve("work/tmp/tesseraql"));
        context.getRegistry().bind(TesseraqlProperties.TEMP_STORE_BEAN, tempStore);

        VertxPlatformHttpServerConfiguration httpConfig = new VertxPlatformHttpServerConfiguration();
        httpConfig.setBindHost("0.0.0.0");
        httpConfig.setBindPort(port);

        // The framework's own migrations run before any store touches the schema (versioned
        // history per component, Flyway's lock serializing concurrent node startups); the
        // stores' direct bootstrap below stays as the idempotent fallback for embedders.
        FrameworkMigrations.migrate(dataSource);
        JobRepository jobRepository = new JobRepository(dataSource);
        jobRepository.ensureSchema();
        JdbcIdempotencyStore idempotencyStore = new JdbcIdempotencyStore(dataSource);
        idempotencyStore.ensureSchema();
        context.getRegistry().bind(TesseraqlProperties.IDEMPOTENCY_STORE_BEAN, idempotencyStore);
        JdbcOutboxStore outboxStore = new JdbcOutboxStore(dataSource);
        outboxStore.ensureSchema();
        context.getRegistry().bind(TesseraqlProperties.OUTBOX_STORE_BEAN, outboxStore);
        // Inbound-webhook replay protection (roadmap Phase 26): a delivery is processed at most
        // once on any node sharing this database.
        io.tesseraql.operations.webhook.JdbcWebhookReplayStore webhookReplayStore = new io.tesseraql.operations.webhook.JdbcWebhookReplayStore(
                dataSource);
        webhookReplayStore.ensureSchema();
        context.getRegistry().bind(TesseraqlProperties.WEBHOOK_REPLAY_STORE_BEAN,
                webhookReplayStore);
        // Messaging channel event log backing the built-in pg-notify transport (roadmap Phase 27):
        // the durable bus a publish: relay writes to and a queue-consume route claims from.
        io.tesseraql.operations.messaging.JdbcEventChannelStore eventChannelStore = new io.tesseraql.operations.messaging.JdbcEventChannelStore(
                dataSource);
        eventChannelStore.ensureSchema();
        context.getRegistry().bind(TesseraqlProperties.EVENT_CHANNEL_STORE_BEAN, eventChannelStore);
        // Managed org-unit hierarchy for data scoping (roadmap Phase 29 slice 2): provisioned and
        // bound only in `managed` mode, so an app that owns its own org tables (the `app` default)
        // gets no managed schema. A subtree scope joins tql_org_closure; this store maintains it.
        if (io.tesseraql.yaml.org.OrgUnitSettings.from(manifest.config()).managed()) {
            io.tesseraql.operations.org.JdbcOrgUnitStore orgUnitStore = new io.tesseraql.operations.org.JdbcOrgUnitStore(
                    dataSource);
            orgUnitStore.ensureSchema();
            context.getRegistry().bind(TesseraqlProperties.ORG_UNIT_STORE_BEAN, orgUnitStore);
        }
        // Managed approval-workflow state (roadmap Phase 28 slice 1): provisioned and bound when any
        // declared workflow runs in `managed` mode (the app-wide default or a per-workflow
        // override); `app` mode keeps state in the business table's column and binds no store (the
        // transition route carries its own).
        if (workflowsNeedManagedStore(manifest)) {
            io.tesseraql.operations.workflow.JdbcWorkflowStore workflowStore = new io.tesseraql.operations.workflow.JdbcWorkflowStore(
                    dataSource);
            workflowStore.ensureSchema();
            context.getRegistry().bind(TesseraqlProperties.WORKFLOW_STORE_BEAN, workflowStore);
        }
        // Managed approval-workflow task inbox (roadmap Phase 28 slice 2): provisioned and bound when
        // any transition assigns a task, independent of where the workflow keeps its state, so one
        // inbox spans managed-state and app-state workflows alike.
        WorkflowSweeper workflowSweeper = null;
        if (workflowsAssignTasks(manifest)) {
            io.tesseraql.operations.workflow.JdbcWorkflowTaskStore taskStore = new io.tesseraql.operations.workflow.JdbcWorkflowTaskStore(
                    dataSource);
            taskStore.ensureSchema();
            context.getRegistry().bind(TesseraqlProperties.WORKFLOW_TASK_STORE_BEAN, taskStore);
            // Deadline escalation (roadmap Phase 28 slice 3): a sweeper reassigns overdue tasks per
            // each state's onBreach.reassign resolver, recording history through the managed store.
            List<WorkflowSweeper.Rule> rules = buildSweeperRules(manifest,
                    datasourceDialect(manifest.config()));
            if (!rules.isEmpty()) {
                io.tesseraql.core.workflow.WorkflowStore historyStore = context.getRegistry()
                        .lookupByNameAndType(TesseraqlProperties.WORKFLOW_STORE_BEAN,
                                io.tesseraql.core.workflow.WorkflowStore.class);
                workflowSweeper = new WorkflowSweeper(rules, taskStore, historyStore, outboxStore,
                        manifest.config().getString("tesseraql.app.name").orElse("app"),
                        dataSource);
                context.getRegistry().bind(TesseraqlProperties.WORKFLOW_SWEEPER_BEAN,
                        workflowSweeper);
            }
        }
        io.tesseraql.yaml.messaging.MessagingChannels messagingChannels = io.tesseraql.yaml.messaging.MessagingChannels
                .load(manifest.config());
        // Managed document-number sequences for command steps (roadmap Phase 18).
        io.tesseraql.operations.sequence.JdbcDocumentSequences documentSequences = new io.tesseraql.operations.sequence.JdbcDocumentSequences(
                dataSource);
        documentSequences.ensureSchema();
        context.getRegistry().bind(TesseraqlProperties.DOCUMENT_SEQUENCES_BEAN, documentSequences);
        // Asynchronous file imports/exports (design ch. 28); codecs arrive via ServiceLoader, so
        // adding the optional tesseraql-excel module to the classpath is the whole install.
        io.tesseraql.operations.files.JdbcFileTransferService fileTransfers = new io.tesseraql.operations.files.JdbcFileTransferService(
                jobRepository,
                tempStore, dataSource, io.tesseraql.core.files.FileCodecs.discover());
        fileTransfers.ensureSchema();
        context.getRegistry().bind(TesseraqlProperties.FILE_TRANSFER_BEAN, fileTransfers);
        // Managed attachments (roadmap Phase 30): provisioned and bound when the app declares
        // attachment documents in `managed` mode (the default). The blob store is selected by
        // tesseraql.object-storage.provider — the local file store by default, or S3 from the opt-in
        // tesseraql-s3 module (slice 2) — and the metadata table backs the synthesized
        // upload/list/download routes; an app with no attachments gets neither.
        if (attachmentsNeedManagedStore(manifest)) {
            io.tesseraql.core.blob.BlobStore blobStore = io.tesseraql.yaml.blob.BlobStores.create(
                    manifest.config(), appHome);
            context.getRegistry().bind(TesseraqlProperties.BLOB_STORE_BEAN, blobStore);
            io.tesseraql.operations.attachment.JdbcAttachmentStore attachmentStore = new io.tesseraql.operations.attachment.JdbcAttachmentStore(
                    dataSource);
            attachmentStore.ensureSchema();
            context.getRegistry().bind(TesseraqlProperties.ATTACHMENT_STORE_BEAN, attachmentStore);
            // Malware scanning (roadmap Phase 30 slice 3): the installed AttachmentScanner (the
            // no-op default unless a scanner module is on the classpath) runs on upload; an infected
            // object is quarantined or deleted per tesseraql.attachments.scan.onInfected and is never
            // served (the download gate refuses a non-clean object).
            String onInfected = io.tesseraql.yaml.attachment.AttachmentSettings
                    .from(manifest.config()).onInfected();
            context.getRegistry().bind(TesseraqlProperties.ATTACHMENT_SERVICE_BEAN,
                    new io.tesseraql.operations.attachment.DefaultAttachmentService(blobStore,
                            attachmentStore, io.tesseraql.core.scan.AttachmentScanners.discover(),
                            onInfected));
        }
        JobExecutor jobExecutor = new JobExecutor(jobRepository, tempStore, slowSqlLog, tracer)
                .notificationOutbox(outboxStore)
                // Outbound REST for http-call pipeline steps (roadmap Phase 26): deny-by-default
                // egress, secret-managed credentials, timeouts, and circuit breaking from config.
                .httpCall(new io.tesseraql.operations.http.HttpCallClient(
                        io.tesseraql.yaml.http.HttpOutbound.load(manifest.config()),
                        manifest.config(), tracer));
        // Notification channels and operations alerts (roadmap Phase 20).
        io.tesseraql.yaml.notify.NotificationChannels notificationChannels = io.tesseraql.yaml.notify.NotificationChannels
                .load(manifest.config());
        String alertChannel = manifest.config()
                .getString("tesseraql.notifications.alerts.channel").orElse(null);
        if (alertChannel != null) {
            // Job failures alert through the same notification channels (roadmap Phase 20),
            // enqueued on the outbox so the alert inherits at-least-once delivery.
            jobExecutor.onFailure((jobId, executionId, jobApp, message) -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("jobId", jobId);
                payload.put("executionId", executionId);
                payload.put("app", jobApp);
                payload.put("error", message == null ? "" : message);
                outboxStore.insert(io.tesseraql.yaml.notify.NotifyEvents.event(
                        alertChannel, "ops.jobFailure", payload, jobApp == null ? "app" : jobApp));
            });
        }
        Map<String, JobFile> jobs = new LinkedHashMap<>();
        manifest.jobs().forEach(job -> jobs.put(job.definition().id(), job));
        String appName = manifest.config().getString("tesseraql.app.name").orElse("app");
        // The owning app per job id (main app jobs default), so execution records are tagged with
        // the app that declared the job, not just the hosting runtime (design ch. 26, 32).
        Map<String, String> jobOwners = new LinkedHashMap<>();
        // Every app this runtime hosts (main + mounted), scoping outbox claims (design ch. 39).
        java.util.Set<String> hostedApps = new java.util.LinkedHashSet<>();
        hostedApps.add(appName);

        TenantDataSources tenantPools = tenantDataSources;
        AppConfig runtimeConfig = manifest.config();
        OperationsRouteBuilder.JobRunner jobRunner = (jobId, params) -> {
            JobFile jobFile = jobs.get(jobId);
            if (jobFile == null) {
                throw new IllegalArgumentException("Unknown job: " + jobId);
            }
            String owner = jobOwners.getOrDefault(jobId, appName);
            if (jobFile.definition().perTenant()) {
                List<String> tenants = TenantRegistry.tenantIds(runtimeConfig, dataSource,
                        tenantPools);
                if (!tenants.isEmpty()) {
                    JobExecution last = null;
                    for (String tenantId : tenants) {
                        last = jobExecutor.run(jobFile,
                                tenantPools.dataSourceFor(tenantId, dataSource),
                                io.tesseraql.core.tenant.TenantContext.of(tenantId),
                                owner, params, "manual");
                    }
                    return last;
                }
            }
            return jobExecutor.run(jobFile, dataSource, owner, params, "manual");
        };

        io.tesseraql.core.outbox.OutboxEventSink outboxSink;
        io.tesseraql.opsui.OpsDashboard opsDashboard;
        try {
            opsDashboard = new io.tesseraql.opsui.OpsDashboard(jobRepository, lanes, slowSqlLog,
                    tracer instanceof io.tesseraql.core.telemetry.TraceLog traceLog
                            ? traceLog
                            : io.tesseraql.core.telemetry.TraceLog.empty(),
                    manifest.config().getString("tesseraql.diagnostics.slowSpanMillis")
                            .map(Long::parseLong).orElse(200L),
                    new io.tesseraql.opsui.OpsDashboard.AlertThresholds(
                            manifest.config()
                                    .getString("tesseraql.diagnostics.errorRateWarnPercent")
                                    .map(Double::parseDouble).orElse(5.0),
                            manifest.config().getString("tesseraql.diagnostics.slowRateWarnPercent")
                                    .map(Double::parseDouble).orElse(20.0),
                            manifest.config()
                                    .getString("tesseraql.diagnostics.batchFailureWarnPercent")
                                    .map(Double::parseDouble).orElse(10.0)),
                    pinningMonitor)
                    // Dead-lettered deliveries surface as an operational alert (Phase 20).
                    .outboxCounts(outboxStore::countByStatus);
            // The app's db/migration runs before anything queries its schema: fresh installs,
            // upgrades and canary activations all converge here (design ch. 31, 32).
            AppMigrations.migrate(appName, appHome, manifest.config(), dataSource,
                    tenantDataSources, dataSources::get);
            context.addService(new VertxPlatformHttpServer(httpConfig));
            context.addRoutes(new RouteCompiler().appName(appName).compile(manifest));
            // Mounted apps (jar-bundled system apps and config-listed directories, design ch. 32)
            // are plain yaml/sql/template trees compiled exactly like the main app. They load before
            // the MCP endpoint is wired so their MCP surface joins the main app's on one endpoint and
            // the conflict check spans every hosted app.
            List<SystemApps.MountedApp> mountedApps = SystemApps.load(manifest.config(), appHome);
            SystemApps.requireNoRouteConflicts(manifest, mountedApps);
            for (SystemApps.MountedApp mounted : mountedApps) {
                // Mounted apps migrate their own schema (per-app history table) before serving.
                AppMigrations.migrate(mounted.name(), mounted.manifest().appHome(),
                        manifest.config(), dataSource, tenantDataSources, dataSources::get);
                context.addRoutes(new RouteCompiler()
                        .appName(mounted.name()).compile(mounted.manifest()));
                // Mounted apps' batch jobs join the same scheduler and manual-run surface,
                // tagged with the owning app; duplicate ids across apps fail the mount.
                for (JobFile job : mounted.manifest().jobs()) {
                    String jobId = job.definition().id();
                    if (jobs.putIfAbsent(jobId, job) != null) {
                        throw new io.tesseraql.core.error.TqlException(DUPLICATE_JOB,
                                "Job id '" + jobId + "' of app '" + mounted.name()
                                        + "' is already declared by another app");
                    }
                    jobOwners.put(jobId, mounted.name());
                }
                hostedApps.add(mounted.name());
            }
            // Application-declared MCP tools, resources, and UI resources (roadmap Phase 24): the
            // compiler emitted a direct:mcp.<id> route per tool, a direct:mcp.resource.<id> route
            // per resource, and a direct:mcp.ui.<id> route per UI resource, for the main app and
            // every mounted app (design ch. 32 mounted-app tools). Serve them all over one Streamable
            // HTTP endpoint at /_tesseraql/mcp, each route's own security gating the call; the
            // conflict check above kept tool names and resource uris unique across apps.
            List<AppManifest> mcpApps = new java.util.ArrayList<>();
            mcpApps.add(manifest);
            mountedApps.forEach(mounted -> mcpApps.add(mounted.manifest()));
            int mcpTools = mcpApps.stream().mapToInt(app -> app.tools().size()).sum();
            int mcpResources = mcpApps.stream().mapToInt(app -> app.resources().size()).sum();
            int mcpUiResources = mcpApps.stream().mapToInt(app -> app.uiResources().size()).sum();
            if ((mcpTools > 0 || mcpResources > 0 || mcpUiResources > 0)
                    && manifest.config().getString("tesseraql.mcp.enabled")
                            .map(Boolean::parseBoolean).orElse(true)) {
                io.tesseraql.mcp.McpServer mcpServer = AppMcpServer.build(appName, mcpApps,
                        context.createProducerTemplate());
                context.addRoutes(new McpRouteBuilder(
                        new io.tesseraql.mcp.McpHttpHandler(mcpServer, null)));
                LOG.info("Serving {} MCP tool(s), {} resource(s), and {} UI resource(s) at"
                        + " /_tesseraql/mcp", mcpTools, mcpResources, mcpUiResources);
            }
            // Static assets (design ch. 12, 40): the main app's assets/, each mounted app's
            // assets/ under its name, framework css under /assets/_tesseraql, vendored WebJars
            // under /assets/vendor.
            Map<String, java.nio.file.Path> appAssets = new LinkedHashMap<>();
            for (SystemApps.MountedApp mounted : mountedApps) {
                java.nio.file.Path assets = mounted.manifest().appHome().resolve("assets");
                if (java.nio.file.Files.isDirectory(assets)) {
                    appAssets.put(mounted.name(), assets);
                }
            }
            context.addRoutes(new AssetsRouteBuilder(appHome.resolve("assets"), appAssets,
                    new ClientMessages(appHome,
                            manifest.config().getString("tesseraql.i18n.defaultLocale")
                                    .orElse("en"))));
            // The ops API needs each job's owning app so per-app scope can gate listing and runs.
            Map<String, String> ownedJobs = new LinkedHashMap<>();
            jobs.keySet().forEach(id -> ownedJobs.put(id, jobOwners.getOrDefault(id, appName)));
            context.addRoutes(new OperationsRouteBuilder(
                    jobRunner, jobRepository, ownedJobs, opsDashboard, outboxStore));
            // Service providers expose non-SQL runtime state to mounted yaml/template apps
            // (the bundled ops-console and studio apps render these, design ch. 26.11, 16, 47).
            io.tesseraql.opsui.OpsDashboard dashboardRef = opsDashboard;
            io.tesseraql.core.service.ServiceProviders serviceProviders = new io.tesseraql.core.service.ServiceProviders()
                    // Batch visibility narrows to the caller's ops.app.<name> grants,
                    // bound by the console routes as principal.permissions (ch. 26.11).
                    .register("ops.overview",
                            params -> io.tesseraql.opsui.OpsViews.overview(dashboardRef.overview(20,
                                    io.tesseraql.opsui.OpsScope.allowedApps(
                                            params.get("permissions")))))
                    .register("ops.traces",
                            params -> io.tesseraql.opsui.OpsViews.traces(dashboardRef.traceTree(
                                    io.tesseraql.opsui.OpsScope.allowedApps(
                                            params.get("permissions")))))
                    .register("ops.transfers", params -> {
                        java.util.function.Predicate<String> scope = io.tesseraql.opsui.OpsScope
                                .allowedApps(
                                        params.get("permissions"));
                        return io.tesseraql.opsui.OpsViews.transfers(
                                fileTransfers.recent(50).stream()
                                        .filter(transfer -> scope.test(transfer.appName()))
                                        .toList());
                    })
                    .register("ops.outbox", params -> {
                        java.util.function.Predicate<String> scope = io.tesseraql.opsui.OpsScope
                                .allowedApps(params.get("permissions"));
                        return io.tesseraql.opsui.OpsViews.outbox(
                                outboxStore.recent(100).stream()
                                        .filter(event -> scope.test(event.appName()))
                                        .toList());
                    })
                    .register("ops.execution", params -> {
                        String id = params.get("id") == null
                                ? ""
                                : String.valueOf(params.get("id"));
                        java.util.function.Predicate<String> scope = io.tesseraql.opsui.OpsScope
                                .allowedApps(
                                        params.get("permissions"));
                        // An execution outside the caller's scope renders as not found.
                        JobExecution execution = jobRepository.findExecution(id)
                                .filter(found -> scope.test(found.appName()))
                                .orElse(null);
                        return io.tesseraql.opsui.OpsViews.execution(id, execution,
                                execution == null ? List.of() : jobRepository.findSteps(id));
                    });
            context.getRegistry().bind(TesseraqlProperties.SERVICE_PROVIDERS_BEAN,
                    serviceProviders);
            Map<String, String> claimKeys = new LinkedHashMap<>();
            jobs.keySet().forEach(
                    id -> claimKeys.put(id, jobOwners.getOrDefault(id, appName) + ":" + id));
            context.addRoutes(new SchedulingRouteBuilder(
                    jobRunner, jobRepository, List.copyOf(jobs.values()), claimKeys));
            // Approval-workflow deadline sweeper (roadmap Phase 28 slice 3): a cluster-safe timer
            // escalates overdue tasks, so exactly one node sweeps per interval.
            if (workflowSweeper != null) {
                context.addRoutes(new WorkflowSweepRoutes(workflowSweeper, jobRepository,
                        io.tesseraql.yaml.workflow.WorkflowSettings
                                .sweepIntervalMillis(manifest.config()),
                        appName));
            }
            // Directory-polling consumers for poll-triggered file-import jobs (roadmap Phase 26):
            // local/SFTP/FTPS sources feed the file-import pipeline, under a deny-by-default host
            // allow-list. The Camel file/ftp endpoint stays an implementation detail.
            context.addRoutes(new PollingRouteBuilder(List.copyOf(jobs.values()),
                    io.tesseraql.yaml.connectors.PollConnectors.load(manifest.config()), appName,
                    jobOwners));
            // Messaging consumers (roadmap Phase 27): each queue-consume route drains its channel
            // off the durable tql_event table — that table is what makes delivery at-least-once.
            // The wake mechanism depends on the channel's transport: pg-notify adds low-latency
            // LISTEN/NOTIFY (PostgreSQL only), db-poll just sweeps on the backstop interval (every
            // dialect). Subscriptions split by transport so each runs under the right driver.
            List<QueueConsumer.Subscription> pgNotifySubs = new java.util.ArrayList<>();
            List<QueueConsumer.Subscription> dbPollSubs = new java.util.ArrayList<>();
            for (io.tesseraql.yaml.manifest.RouteFile consumerFile : manifest.consumers()) {
                io.tesseraql.yaml.model.ConsumeSpec consume = consumerFile.definition().consume();
                if (consume == null || consume.channel() == null || consume.topic() == null) {
                    continue;
                }
                QueueConsumer.Subscription sub = new QueueConsumer.Subscription(consume.channel(),
                        consume.topic(), consumerFile.definition().id());
                String transport = messagingChannels.find(consume.channel())
                        .map(io.tesseraql.yaml.messaging.MessagingChannels.Channel::transport)
                        .orElse(io.tesseraql.yaml.messaging.MessagingChannels.PG_NOTIFY);
                (io.tesseraql.yaml.messaging.MessagingChannels.DB_POLL.equals(transport)
                        ? dbPollSubs
                        : pgNotifySubs).add(sub);
            }
            int messagingMaxAttempts = outboxMaxAttempts(manifest.config());
            long backstop = io.tesseraql.core.util.Durations.toMillis(manifest.config()
                    .getString("tesseraql.messaging.backstop").orElse("10s"));
            if (!pgNotifySubs.isEmpty()) {
                if ("postgresql".equals(
                        io.tesseraql.core.util.DatabaseVendors.vendor(dataSource).orElse(null))) {
                    context.addService(new PgNotifyListener(dataSource,
                            new QueueConsumer(context, eventChannelStore, pgNotifySubs,
                                    messagingMaxAttempts),
                            backstop));
                } else {
                    LOG.warn("{} pg-notify consumer(s) declared but the main datasource is not"
                            + " PostgreSQL; LISTEN/NOTIFY will not run — use transport: db-poll",
                            pgNotifySubs.size());
                }
            }
            if (!dbPollSubs.isEmpty()) {
                context.addRoutes(new QueuePollRouteBuilder(new QueueConsumer(context,
                        eventChannelStore, dbPollSubs, messagingMaxAttempts), backstop));
            }

            IdentityService identity = new IdentityService(
                    name -> context.getRegistry().lookupByNameAndType(name,
                            javax.sql.DataSource.class),
                    datasourceDialect(manifest.config()));
            RealmConfig realm = IdentityConfigFactory.defaultRealm(manifest.config(), appHome);
            context.getRegistry().bind(TesseraqlProperties.IDENTITY_SERVICE_BEAN, identity);
            context.getRegistry().bind(TesseraqlProperties.IDENTITY_REALM_BEAN, realm);
            context.addRoutes(new LoginRouteBuilder(
                    new PasswordAuthenticator(identity), realm, sessionStore));
            // Optional feature modules (SCIM, SAML, ...) self-install via ServiceLoader, from the
            // classpath or from signature-verified plugin jars in isolated loaders (ch. 47).
            for (io.tesseraql.compiler.ext.RuntimeExtension extension : RuntimeExtensions
                    .discover(manifest.config(), appHome)) {
                if (extension.enabled(manifest.config())) {
                    extension.install(new io.tesseraql.compiler.ext.ExtensionContext(
                            context, manifest, dataSource));
                    LOG.info("Installed runtime extension '{}'", extension.name());
                }
            }
            // The outbox always logs deliveries; the notification sink (mail/webhooks, roadmap
            // Phase 20) and an extension-contributed sink (e.g. SCIM outbound provisioning) are
            // composed on top when configured/bound.
            io.tesseraql.core.outbox.OutboxEventSink extensionSink = context.getRegistry()
                    .lookupByNameAndType(
                            TesseraqlProperties.OUTBOX_EVENT_SINK_BEAN,
                            io.tesseraql.core.outbox.OutboxEventSink.class);
            io.tesseraql.core.outbox.OutboxEventSink notificationSink = notificationChannels
                    .isEmpty()
                            ? null
                            : new NotificationSink(notificationChannels, appHome, context);
            // The channel-publish sink relays publish: EVENT events onto messaging channels
            // (roadmap Phase 27), composed alongside the notification sink on the same outbox.
            io.tesseraql.core.outbox.OutboxEventSink channelSink = messagingChannels.isEmpty()
                    ? null
                    : new ChannelPublishSink(messagingChannels, eventChannelStore);
            outboxSink = event -> {
                LOGGING_SINK.send(event);
                if (notificationSink != null) {
                    notificationSink.send(event);
                }
                if (channelSink != null) {
                    channelSink.send(event);
                }
                if (extensionSink != null) {
                    extensionSink.send(event);
                }
            };
            if (manifest.config().getString("tesseraql.studio.enabled")
                    .map(Boolean::parseBoolean).orElse(true)) {
                boolean readOnly = manifest.config().getString("tesseraql.studio.readOnly")
                        .map(Boolean::parseBoolean).orElse(true);
                io.tesseraql.studio.StudioService studio = new io.tesseraql.studio.StudioService(
                        manifest, readOnly);
                RouteReloader reloader = new RouteReloader(context, appHome, manifest, studio);
                // The Studio test runner (backlog A2): run a route's read-only sql cases against the
                // dev datasource. Gated on writable Studio + an explicit opt-in, sandboxed per run
                // (read-only connection, statement timeout, row cap, rollback on close).
                boolean testRunnerEnabled = !readOnly
                        && manifest.config().getString("tesseraql.studio.testRunner.enabled")
                                .map(Boolean::parseBoolean).orElse(false);
                int testTimeout = manifest.config()
                        .getString("tesseraql.studio.testRunner.queryTimeoutSeconds")
                        .map(Integer::parseInt).orElse(5);
                int testMaxRows = manifest.config()
                        .getString("tesseraql.studio.testRunner.maxRows")
                        .map(Integer::parseInt).orElse(1000);
                StudioTestService studioTests = new StudioTestService(
                        name -> context.getRegistry().lookupByNameAndType(name,
                                javax.sql.DataSource.class),
                        appHome, realm, datasourceDialect(manifest.config()),
                        testRunnerEnabled, testTimeout, testMaxRows);
                // The Studio scaffold generator (backlog B3): introspect a table from the dev
                // datasource and generate its CRUD slice, reusing the CLI's introspection + generator
                // so the output is byte-identical. Gated on writable Studio + an explicit opt-in.
                boolean scaffoldEnabled = !readOnly
                        && manifest.config().getString("tesseraql.studio.scaffold.enabled")
                                .map(Boolean::parseBoolean).orElse(false);
                StudioScaffoldService studioScaffold = new StudioScaffoldService(
                        name -> context.getRegistry().lookupByNameAndType(name,
                                javax.sql.DataSource.class),
                        "main", studio, scaffoldEnabled);
                // Granular read-only (backlog D6): an optional editRoles allow-list refines the
                // writable master switch — when set, only callers holding one of those roles may edit.
                java.util.Set<String> editRoles = manifest.config()
                        .getString("tesseraql.studio.editRoles")
                        .map(roles -> java.util.Arrays.stream(roles.split(","))
                                .map(String::trim).filter(role -> !role.isEmpty())
                                .collect(java.util.stream.Collectors.toSet()))
                        .orElse(java.util.Set.of());
                StudioAccess studioAccess = new StudioAccess(!readOnly, editRoles);
                // Output-field masking in the JSON render preview (Studio backlog A1 follow-up): the
                // runtime supplies the mask over the canonical FieldPolicyApplier (so Studio stays
                // free of the security/compiler stack), evaluated for the sample principal the
                // developer puts under `principal` in the render sample.
                PolicyEngine studioPolicyEngine = context.getRegistry().lookupByNameAndType(
                        TesseraqlProperties.POLICY_ENGINE_BEAN, PolicyEngine.class);
                io.tesseraql.studio.StudioService.FieldMask studioMask = (fields, body,
                        ctx) -> new io.tesseraql.compiler.binding.FieldPolicyApplier(fields,
                                studioPolicyEngine, samplePrincipal(ctx)).apply(body);
                // PDF preview for query-export pdf routes (Studio backlog A1 follow-up): the runtime
                // renders through the canonical PDF codec when the optional tesseraql-pdf module is on
                // the classpath, returning null (a graceful "module absent" message) otherwise — so
                // Studio stays free of the heavy openhtmltopdf/pdfbox stack.
                io.tesseraql.studio.StudioService.PdfRender studioPdf = (export, routeDir,
                        rows) -> renderExportPdf(export, routeDir, appHome, rows);
                context.addRoutes(new StudioRouteBuilder(studio, reloader, studioTests,
                        studioScaffold, studioAccess, studioMask, studioPdf));
                // Providers backing the bundled studio app (design ch. 16, 47).
                serviceProviders
                        .register("studio.explorer", params -> {
                            Object query = params.get("q");
                            String q = query == null ? "" : String.valueOf(query);
                            Map<String, Object> model = io.tesseraql.studio.StudioViews
                                    .explorer(studio.explorer(q));
                            // Edit affordances follow the caller's edit permission (backlog D6).
                            boolean canEdit = studioAccess.canEdit(params.get("roles"));
                            model.put("editable", canEdit);
                            model.put("readOnly", !canEdit);
                            // Offer the scaffold action only when B3 is on and the caller may edit.
                            model.put("scaffoldEnabled", scaffoldEnabled && canEdit);
                            // Echo the filter query (Studio backlog C4) so the input keeps its value.
                            model.put("query", q);
                            return model;
                        })
                        .register("studio.source", params -> {
                            String path = String.valueOf(params.get("path"));
                            String sample = studio.sampleModel(path);
                            String draft = studio.readDraft(path);
                            Map<String, Object> model;
                            if (draft == null) {
                                // No draft: show the source (404s when the file does not exist).
                                String src = studio.source(path);
                                model = io.tesseraql.studio.StudioViews.source(path, src,
                                        studio.isReadOnly(), false, src, sample);
                            } else {
                                // A draft is edited; sourceIfExists is null for a new-file draft.
                                model = io.tesseraql.studio.StudioViews.source(path, draft,
                                        studio.isReadOnly(), true, studio.sourceIfExists(path),
                                        sample);
                            }
                            // Offer the "run tests" action on a route page only when A2 is enabled.
                            model.put("testRunnerEnabled", testRunnerEnabled);
                            // Warn when applying would overwrite a concurrently changed source (D5).
                            model.put("conflict", draft != null && studio.draftConflicts(path));
                            // The edit surface follows the caller's edit permission (backlog D6).
                            boolean canEdit = studioAccess.canEdit(params.get("roles"));
                            model.put("editable", canEdit);
                            model.put("readOnly", !canEdit);
                            return model;
                        })
                        .register("studio.save", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            String path = String.valueOf(params.get("path"));
                            Object content = params.get("content");
                            studio.saveDraft(path, content == null ? "" : String.valueOf(content));
                            return Map.of("saved", path);
                        })
                        .register("studio.newRoute", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            String path = String.valueOf(params.get("path"));
                            Object recipe = params.get("recipe");
                            studio.newRouteDraft(path,
                                    recipe == null ? "query-json" : String.valueOf(recipe));
                            return Map.of("created", path);
                        })
                        .register("studio.apply", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            String path = String.valueOf(params.get("path"));
                            // force=true overwrites a concurrently changed source (backlog D5).
                            boolean force = "true".equals(String.valueOf(params.get("force")));
                            // The caller is recorded to the audit trail (backlog D6).
                            studio.applyDraft(path, force, actorOf(params));
                            reloader.reload();
                            return Map.of("applied", path);
                        })
                        .register("studio.preview", params -> {
                            String path = String.valueOf(params.get("path"));
                            Object content = params.get("content");
                            return io.tesseraql.studio.StudioViews.preview(studio.preview(path,
                                    content == null ? null : String.valueOf(content)));
                        })
                        .register("studio.render", params -> {
                            String path = String.valueOf(params.get("path"));
                            Object content = params.get("content");
                            Object sample = params.get("sampleModel");
                            // Live data: run the route's query through the A2 sandbox for real rows,
                            // gated like the test runner (writable Studio + opt-in enabled).
                            boolean live = "true".equals(String.valueOf(params.get("live")));
                            io.tesseraql.studio.StudioService.RowSource rows = live
                                    && studioTests.isEnabled() ? studioTests::liveRows : null;
                            return io.tesseraql.studio.StudioViews.render(studio.render(path,
                                    content == null ? null : String.valueOf(content),
                                    sample == null ? null : String.valueOf(sample), rows,
                                    studioMask, studioPdf));
                        })
                        .register("studio.runTests",
                                params -> studioTests
                                        .runForPath(String.valueOf(params.get("path"))))
                        .register("studio.scaffold.tables",
                                params -> io.tesseraql.studio.StudioViews.scaffoldTables(
                                        studioScaffold.tables(), studioScaffold.isEnabled()))
                        .register("studio.scaffold.preview",
                                params -> io.tesseraql.studio.StudioViews.scaffoldPreview(
                                        studioScaffold.preview(
                                                String.valueOf(params.get("table")))))
                        .register("studio.scaffold.apply", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            return io.tesseraql.studio.StudioViews.scaffoldResult(
                                    studioScaffold.apply(String.valueOf(params.get("table")),
                                            "true".equals(String.valueOf(params.get("force"))),
                                            actorOf(params)));
                        })
                        .register("studio.discard", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            String path = String.valueOf(params.get("path"));
                            studio.deleteDraft(path);
                            return Map.of("discarded", path);
                        })
                        .register("studio.drafts", params -> io.tesseraql.studio.StudioViews
                                .drafts(studio.drafts()))
                        .register("studio.audit", params -> io.tesseraql.studio.StudioViews
                                .audit(studio.auditEntries(200)));
                // Providers backing the bundled documentation portal (documentation portal v1/v2/v3):
                // they read the packaged spec.json, falling back to a live model from the manifest,
                // and overlay the optional run report.json (test results + coverage) and schema.json
                // (introspected table definitions) when present.
                io.tesseraql.studio.DocService doc = new io.tesseraql.studio.DocService(manifest);
                serviceProviders
                        .register("docs.index", params -> io.tesseraql.studio.DocViews
                                .index(doc.appName(), doc.spec(), doc.report()))
                        .register("docs.route", params -> {
                            String id = String.valueOf(params.get("id"));
                            io.tesseraql.studio.DocService.RouteEntry entry = doc.route(id);
                            if (entry == null) {
                                return Map.of("notFound", true, "id", id);
                            }
                            io.tesseraql.studio.ReportOverlay overlay = doc.report();
                            return io.tesseraql.studio.DocViews.route(entry,
                                    overlay == null ? null : overlay.routeReport(id));
                        })
                        .register("docs.search", params -> {
                            Object query = params.get("q");
                            String q = query == null ? "" : String.valueOf(query);
                            return io.tesseraql.studio.DocViews.searchResults(q, doc.search(q));
                        })
                        .register("docs.coverage", params -> io.tesseraql.studio.DocViews
                                .coverage(doc.appName(), doc.report(), doc.history()))
                        .register("docs.schema", params -> io.tesseraql.studio.DocViews
                                .schema(doc.appName(), doc.schema()))
                        .register("docs.table", params -> {
                            String ds = String.valueOf(params.get("ds"));
                            String name = String.valueOf(params.get("name"));
                            io.tesseraql.yaml.scaffold.CatalogSchema.Table table = doc.table(ds,
                                    name);
                            if (table == null) {
                                return Map.of("notFound", true, "name", name, "datasource", ds);
                            }
                            return io.tesseraql.studio.DocViews.table(ds, table);
                        });
            }
            // Retention (design ch. 44): enabled by configuring the sweep interval. When
            // tesseraql.retention.attachments is set and the managed attachment store is bound, the
            // sweep also reclaims aged attachment rows and their blobs (roadmap Phase 30 slice 3).
            var retentionSweep = manifest.config().getString("tesseraql.retention.sweep");
            if (retentionSweep.isPresent()) {
                io.tesseraql.core.attachment.AttachmentStore attachmentStore = context.getRegistry()
                        .lookupByNameAndType(TesseraqlProperties.ATTACHMENT_STORE_BEAN,
                                io.tesseraql.core.attachment.AttachmentStore.class);
                io.tesseraql.core.blob.BlobStore blobStore = context.getRegistry()
                        .lookupByNameAndType(TesseraqlProperties.BLOB_STORE_BEAN,
                                io.tesseraql.core.blob.BlobStore.class);
                java.time.Duration attachmentRetention = manifest.config()
                        .getString("tesseraql.retention.attachments")
                        .map(io.tesseraql.core.util.Durations::parse).orElse(null);
                context.addRoutes(new RetentionRouteBuilder(
                        new io.tesseraql.operations.retention.RetentionSweeper(dataSource,
                                attachmentStore, blobStore),
                        io.tesseraql.core.util.Durations.toMillis(retentionSweep.get()),
                        io.tesseraql.core.util.Durations.parse(
                                manifest.config().getString("tesseraql.retention.outbox")
                                        .orElse("30d")),
                        io.tesseraql.core.util.Durations.parse(
                                manifest.config().getString("tesseraql.retention.jobs")
                                        .orElse("90d")),
                        attachmentRetention));
            }
            var outboxDelay = manifest.config().getString("tesseraql.outbox.dispatch.fixedDelay");
            if (outboxDelay.isPresent()) {
                context.addRoutes(new OutboxDispatchRouteBuilder(outboxStore, outboxSink,
                        io.tesseraql.core.util.Durations.toMillis(outboxDelay.get()), hostedApps,
                        outboxMaxAttempts(manifest.config())));
            }
            if (alertChannel != null) {
                // Threshold-breach alerts from the dashboard notify through the same channel
                // (roadmap Phase 20).
                long alertPeriod = io.tesseraql.core.util.Durations.toMillis(manifest.config()
                        .getString("tesseraql.notifications.alerts.checkInterval").orElse("60s"));
                context.addRoutes(new AlertNotifyRouteBuilder(opsDashboard, outboxStore,
                        alertChannel, alertPeriod, appName));
            }
            context.start();
        } catch (Exception ex) {
            tenantDataSources.close();
            lanes.close();
            dataSources.values().forEach(HikariDataSource::close);
            throw new IllegalStateException("Failed to start TesseraQL runtime", ex);
        }
        LOG.info("TesseraQL runtime started on port {} for app {}", port, appHome);
        return new TesseraqlRuntime(context, dataSources, port, jobRepository, jobExecutor,
                outboxStore, jobs, jobOwners, appName, hostedApps, lanes, tenantDataSources,
                manifest.config(), pinningSource, otelSdk, opsDashboard, outboxSink);
    }

    /** Whether any declared workflow runs in managed mode (the default or a per-workflow override). */
    private static boolean workflowsNeedManagedStore(
            io.tesseraql.yaml.manifest.AppManifest manifest) {
        if (manifest.workflows().isEmpty()) {
            return false;
        }
        boolean defaultManaged = io.tesseraql.yaml.workflow.WorkflowSettings
                .from(manifest.config()).managed();
        for (io.tesseraql.yaml.manifest.WorkflowFile workflow : manifest.workflows()) {
            String mode = workflow.definition().mode();
            boolean managed = mode == null || mode.isBlank()
                    ? defaultManaged
                    : "managed".equalsIgnoreCase(mode);
            if (managed) {
                return true;
            }
        }
        return false;
    }

    /** Whether the app declares attachment documents in {@code managed} mode (roadmap Phase 30). */
    private static boolean attachmentsNeedManagedStore(
            io.tesseraql.yaml.manifest.AppManifest manifest) {
        return !manifest.attachments().isEmpty()
                && io.tesseraql.yaml.attachment.AttachmentSettings.from(manifest.config())
                        .managed();
    }

    /** Whether any declared workflow has a transition that assigns a task (roadmap Phase 28 slice 2). */
    private static boolean workflowsAssignTasks(io.tesseraql.yaml.manifest.AppManifest manifest) {
        for (io.tesseraql.yaml.manifest.WorkflowFile workflow : manifest.workflows()) {
            for (io.tesseraql.yaml.model.TransitionSpec transition : workflow.definition()
                    .transitions()) {
                if (transition.assign() != null && transition.assign().file() != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The sweeper's escalation rules: each state deadline's onBreach.reassign resolver, parsed. */
    private static List<WorkflowSweeper.Rule> buildSweeperRules(
            io.tesseraql.yaml.manifest.AppManifest manifest, String dialect) {
        List<WorkflowSweeper.Rule> rules = new java.util.ArrayList<>();
        boolean defaultManaged = io.tesseraql.yaml.workflow.WorkflowSettings.from(manifest.config())
                .managed();
        for (io.tesseraql.yaml.manifest.WorkflowFile workflow : manifest.workflows()) {
            io.tesseraql.yaml.model.WorkflowDefinition def = workflow.definition();
            if (def.document() == null) {
                continue;
            }
            String docType = def.document().type();
            io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify escalateNotify = escalateReminder(
                    def);
            String mode = def.mode() == null || def.mode().isBlank() ? null : def.mode();
            boolean managed = mode == null ? defaultManaged : "managed".equalsIgnoreCase(mode);
            java.nio.file.Path dir = workflow.source().getParent();
            for (io.tesseraql.yaml.model.DeadlineSpec deadline : def.deadlines()) {
                io.tesseraql.yaml.model.DeadlineSpec.OnBreachSpec onBreach = deadline.onBreach();
                if (onBreach == null) {
                    continue;
                }
                // escalate (auto-transition) takes precedence over reassign when both are declared.
                if (onBreach.escalate() != null && !onBreach.escalate().isBlank()) {
                    WorkflowSweeper.Escalate escalate = escalateTransition(def, onBreach.escalate(),
                            managed, dir, dialect);
                    if (escalate != null) {
                        rules.add(
                                new WorkflowSweeper.Rule(docType, deadline.state(), null, escalate,
                                        escalateNotify));
                    }
                } else if (onBreach.reassign() != null && !onBreach.reassign().isBlank()) {
                    java.nio.file.Path file = io.tesseraql.core.dialect.DialectSqlResolver.resolve(
                            dir.resolve(onBreach.reassign()).normalize(), dialect);
                    try {
                        rules.add(new WorkflowSweeper.Rule(docType, deadline.state(),
                                io.tesseraql.core.sql.Sql2WayParser
                                        .parse(java.nio.file.Files.readString(file)),
                                null, escalateNotify));
                    } catch (java.io.IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                }
            }
        }
        return rules;
    }

    /** Resolves the named {@code onBreach.escalate} transition into a sweeper escalate rule. */
    private static WorkflowSweeper.Escalate escalateTransition(
            io.tesseraql.yaml.model.WorkflowDefinition def, String transitionId, boolean managed,
            java.nio.file.Path dir, String dialect) {
        for (io.tesseraql.yaml.model.TransitionSpec transition : def.transitions()) {
            if (!transitionId.equals(transition.id())) {
                continue;
            }
            List<io.tesseraql.core.sql.SqlNode> commandNodes = null;
            if (transition.command() != null) {
                java.nio.file.Path file = io.tesseraql.core.dialect.DialectSqlResolver.resolve(
                        dir.resolve(transition.command()).normalize(), dialect);
                try {
                    commandNodes = io.tesseraql.core.sql.Sql2WayParser
                            .parse(java.nio.file.Files.readString(file));
                } catch (java.io.IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            }
            return new WorkflowSweeper.Escalate(transition.id(), transition.to(), commandNodes,
                    managed, def.document().table(), def.document().stateColumn(),
                    def.document().key());
        }
        return null;
    }

    /** The compiled escalation reminder (Phase 20 channels), or {@code null} when undeclared. */
    private static io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify escalateReminder(
            io.tesseraql.yaml.model.WorkflowDefinition def) {
        if (def.reminders() == null || def.reminders().escalated() == null) {
            return null;
        }
        return io.tesseraql.yaml.notify.NotifyEvents.compile(def.id(), "escalated",
                def.reminders().escalated());
    }

    /** The audit actor a Studio service provider was bound (the caller's {@code principal.loginId}). */
    private static String actorOf(Map<String, Object> params) {
        Object actor = params.get("actor");
        return actor == null ? null : String.valueOf(actor);
    }

    /**
     * The sample principal for a Studio JSON render's field masking (backlog A1 follow-up): built from
     * the render context's {@code principal} map ({@code roles}/{@code permissions}/…), or {@code null}
     * (an anonymous viewer) when the sample carries none.
     */
    @SuppressWarnings("unchecked")
    private static io.tesseraql.security.Principal samplePrincipal(Map<String, Object> context) {
        if (!(context.get("principal") instanceof Map<?, ?> map)) {
            return null;
        }
        java.util.function.Function<String, String> str = key -> map.get(key) == null
                ? null
                : String.valueOf(map.get(key));
        java.util.function.Function<String, List<String>> list = key -> map
                .get(key) instanceof List<?> values
                        ? values.stream().map(String::valueOf).toList()
                        : List.of();
        Map<String, Object> claims = map.get("claims") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw
                : Map.of();
        return new io.tesseraql.security.Principal(str.apply("subject"), str.apply("loginId"),
                str.apply("displayName"), str.apply("tenantId"), list.apply("groups"),
                list.apply("roles"), list.apply("permissions"), claims);
    }

    /**
     * Renders a {@code query-export} {@code format: pdf} route's PDF for the Studio preview (backlog
     * A1 follow-up) through the canonical PDF codec, or {@code null} when no {@code pdf} codec is on
     * the classpath (the optional {@code tesseraql-pdf} module is absent).
     */
    private static byte[] renderExportPdf(io.tesseraql.yaml.model.ExportSpec export,
            Path routeDir, Path appHome, List<Map<String, Object>> rows) {
        io.tesseraql.core.files.FileCodec codec;
        try {
            codec = io.tesseraql.core.files.FileCodecs.discover().require("pdf");
        } catch (io.tesseraql.core.error.TqlException ex) {
            return null;
        }
        Path template = export.template() == null || export.template().isBlank()
                ? null
                : routeDir.resolve(export.template());
        io.tesseraql.core.files.FileWriteSpec spec = export.toWriteSpec(template, appHome);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            codec.write(out, spec, rows.iterator());
        } catch (Exception ex) {
            throw new IllegalStateException("PDF render failed: " + ex.getMessage(), ex);
        }
        return out.toByteArray();
    }

    /** The configured dialect for the main datasource, or inferred from its JDBC URL (design ch. 42). */
    private static String datasourceDialect(AppConfig config) {
        String prefix = "tesseraql.datasources.main.";
        return config.getString(prefix + "dialect")
                .orElseGet(() -> io.tesseraql.core.dialect.Dialect
                        .fromJdbcUrl(config.getString(prefix + "jdbcUrl")
                                .orElse(""))
                        .map(io.tesseraql.core.dialect.Dialect::id)
                        .orElse(null));
    }

    /** Runs a batch job by id and returns its final execution record (design ch. 26). */
    public JobExecution runJob(String jobId, Map<String, Object> params) {
        JobFile jobFile = jobs.get(jobId);
        if (jobFile == null) {
            throw new IllegalArgumentException("Unknown job: " + jobId);
        }
        return jobExecutor.run(jobFile, mainDataSource,
                jobOwners.getOrDefault(jobId, appName), params, "manual");
    }

    /**
     * Runs a batch job once per configured tenant, each on its own datasource and tenant context
     * (design ch. 30.3), returning every execution record.
     */
    public List<JobExecution> runJobForAllTenants(String jobId, Map<String, Object> params) {
        JobFile jobFile = jobs.get(jobId);
        if (jobFile == null) {
            throw new IllegalArgumentException("Unknown job: " + jobId);
        }
        List<JobExecution> executions = new java.util.ArrayList<>();
        for (String tenantId : TenantRegistry.tenantIds(config, mainDataSource,
                tenantDataSources)) {
            executions.add(jobExecutor.run(jobFile,
                    tenantDataSources.dataSourceFor(tenantId, mainDataSource),
                    io.tesseraql.core.tenant.TenantContext.of(tenantId),
                    jobOwners.getOrDefault(jobId, appName), params, "manual"));
        }
        return executions;
    }

    public JobRepository jobRepository() {
        return jobRepository;
    }

    /** Dispatches pending outbox events once, returning the number delivered (design ch. 39.2). */
    public int dispatchOutboxOnce() {
        return new OutboxDispatcher(outboxStore, outboxSink, hostedApps,
                outboxMaxAttempts(config)).dispatch(100);
    }

    /** The delivery-attempt ceiling before an event dead-letters (roadmap Phase 20). */
    private static int outboxMaxAttempts(AppConfig config) {
        return config.getString("tesseraql.outbox.dispatch.maxAttempts")
                .map(Integer::parseInt).orElse(OutboxDispatcher.DEFAULT_MAX_ATTEMPTS);
    }

    public JdbcOutboxStore outboxStore() {
        return outboxStore;
    }

    /** The asynchronous file transfer service (design ch. 28). */
    public io.tesseraql.core.files.FileTransferService fileTransfers() {
        return camelContext.getRegistry().lookupByNameAndType(
                TesseraqlProperties.FILE_TRANSFER_BEAN,
                io.tesseraql.core.files.FileTransferService.class);
    }

    public int port() {
        return port;
    }

    public CamelContext camelContext() {
        return camelContext;
    }

    @Override
    public void close() {
        closeQuietly(pinningSource);
        closeQuietly(otelSdk);
        io.tesseraql.operations.files.JdbcFileTransferService fileTransfers = camelContext
                .getRegistry().lookupByNameAndType(
                        TesseraqlProperties.FILE_TRANSFER_BEAN,
                        io.tesseraql.operations.files.JdbcFileTransferService.class);
        if (fileTransfers != null) {
            fileTransfers.close();
        }
        try {
            camelContext.stop();
        } finally {
            try {
                executionLanes.close();
            } finally {
                try {
                    tenantDataSources.close();
                } finally {
                    dataSources.values().forEach(HikariDataSource::close);
                }
            }
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // best-effort shutdown of the diagnostics source
        }
    }
}
