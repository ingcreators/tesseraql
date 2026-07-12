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

    /**
     * TQL-STUDIO-4234: the data-browser row edit was rejected — editor disabled, unknown
     * table, no row matches the key, or the update failed (HTTP 400).
     */
    private static final io.tesseraql.core.error.TqlErrorCode ROW_EDIT_REJECTED = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.STUDIO, 4234);

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
        // alongside the in-process ring and export metrics via OpenTelemetry. Independent of
        // the push path, a JDK-only aggregating meter always collects per-route counters and
        // latency histograms for the pull-based /_tesseraql/metrics exposition (roadmap
        // Phase 45, decision point 9 resolved: no new dependency for the scrape path).
        io.tesseraql.core.telemetry.AggregatingMeter aggregatingMeter = new io.tesseraql.core.telemetry.AggregatingMeter();
        io.tesseraql.core.telemetry.Tracer effectiveTracer = tracer;
        // The provided meter (tests inject a recording one) keeps receiving everything the
        // aggregator sees; a no-op provided meter needs no fan-out.
        io.tesseraql.core.telemetry.Meter effectiveMeter = meter == io.tesseraql.core.telemetry.NoopMeter.INSTANCE
                ? aggregatingMeter
                : new io.tesseraql.core.telemetry.CompositeMeter(aggregatingMeter,
                        meter);
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
            effectiveMeter = meter == io.tesseraql.core.telemetry.NoopMeter.INSTANCE
                    ? new io.tesseraql.core.telemetry.CompositeMeter(aggregatingMeter,
                            new io.tesseraql.observability.OpenTelemetryMeter(sdk))
                    : new io.tesseraql.core.telemetry.CompositeMeter(aggregatingMeter, meter,
                            new io.tesseraql.observability.OpenTelemetryMeter(sdk));
        }
        // MDC bridging (roadmap Phase 45): Camel copies the trace-correlation keys across
        // its async boundaries so lane-dispatched steps keep logging with the request's ids.
        String activeProfile = io.tesseraql.yaml.manifest.ManifestLoader.activeProfile();
        if (activeProfile != null) {
            LOG.info("Environment profile active: {} (config/env/{}.yml)", activeProfile,
                    activeProfile);
        }
        context.setUseMDCLogging(true);
        context.setMDCLoggingKeysPattern("traceId,spanId");
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
        io.tesseraql.core.spool.FileTempStore tempStore = new io.tesseraql.core.spool.FileTempStore(
                appHome.resolve("work/tmp/tesseraql"));
        context.getRegistry().bind(TesseraqlProperties.TEMP_STORE_BEAN, tempStore);

        VertxPlatformHttpServerConfiguration httpConfig = new VertxPlatformHttpServerConfiguration();
        httpConfig.setBindHost("0.0.0.0");
        httpConfig.setBindPort(port);
        // SSE endpoints register on the platform's Vert.x router, which exists only once
        // the context (and with it the HTTP server) has started — collected here, run
        // right after context.start() (see SseRoutes for why they are not Camel routes).
        java.util.List<Runnable> sseEndpoints = new java.util.ArrayList<>();

        // The framework's own migrations run before any store touches the schema (versioned
        // history per component, Flyway's lock serializing concurrent node startups); the
        // stores' direct bootstrap below stays as the idempotent fallback for embedders.
        FrameworkMigrations.migrate(dataSource);
        // Browser sessions: in-memory per node by default; "jdbc" shares tql_session across all
        // runtime nodes so a login made on one node resolves on every other (design ch. 11.2).
        // Constructed after Flyway on purpose: the versioned history owns evolutions like the
        // V2 subject column, and the store's direct ensureSchema stays the tolerated,
        // idempotent fallback for embedders without it.
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
        JobRepository jobRepository = new JobRepository(dataSource);
        jobRepository.ensureSchema();
        JdbcIdempotencyStore idempotencyStore = new JdbcIdempotencyStore(dataSource);
        idempotencyStore.ensureSchema();
        context.getRegistry().bind(TesseraqlProperties.IDEMPOTENCY_STORE_BEAN, idempotencyStore);
        JdbcOutboxStore outboxStore = new JdbcOutboxStore(dataSource);
        outboxStore.ensureSchema();
        context.getRegistry().bind(TesseraqlProperties.OUTBOX_STORE_BEAN, outboxStore);
        // The opt-in business-route audit log (roadmap Phase 45): who called what, with the
        // declared decision-relevant params, per-app scoped like every other ops table.
        io.tesseraql.operations.audit.JdbcRouteAuditStore routeAuditStore = null;
        if (manifest.config().getString("tesseraql.audit.routes.enabled")
                .map(Boolean::parseBoolean).orElse(false)) {
            routeAuditStore = new io.tesseraql.operations.audit.JdbcRouteAuditStore(dataSource);
            routeAuditStore.ensureSchema();
            context.getRegistry().bind(TesseraqlProperties.ROUTE_AUDIT_SINK_BEAN,
                    routeAuditStore);
        }
        // The account surface (roadmap Phase 48): the managed per-user preference store, plus
        // the marker bean the shared shell keys the settings link off. Mounted with the bundled
        // account app (the auth-ui precedent) — AccountAppProvider.enabled is the one source of
        // truth for both the app mount and this wiring. One final reference, so the account
        // service providers registered below can capture it.
        final io.tesseraql.core.account.PreferenceStore preferences = AccountAppProvider
                .enabled(manifest.config()) ? accountPreferenceStore(dataSource) : null;
        final io.tesseraql.core.account.ShortcutStore shortcuts;
        if (preferences != null) {
            context.getRegistry().bind(TesseraqlProperties.PREFERENCE_STORE_BEAN, preferences);
            context.getRegistry().bind(TesseraqlProperties.ACCOUNT_SURFACE_BEAN, Boolean.TRUE);
            // Pins and recents (roadmap Phase 51) ride the account surface: the sidebar's
            // Pinned group reads through the same wrapper the mutations refresh.
            io.tesseraql.operations.account.JdbcShortcutStore jdbcShortcuts = new io.tesseraql.operations.account.JdbcShortcutStore(
                    dataSource);
            jdbcShortcuts.ensureSchema();
            shortcuts = new io.tesseraql.core.account.CachingShortcutStore(jdbcShortcuts);
            context.getRegistry().bind(TesseraqlProperties.SHORTCUT_STORE_BEAN, shortcuts);
        } else {
            shortcuts = null;
        }
        // The operator's default page theme (roadmap Phase 48): the shell's fallback when the
        // user has no stored or cookie choice. Values outside the enum are ignored.
        String uiTheme = manifest.config().getString("tesseraql.ui.theme").orElse(null);
        if ("light".equals(uiTheme) || "dark".equals(uiTheme)) {
            context.getRegistry().bind(TesseraqlProperties.UI_THEME_BEAN, uiTheme);
        }
        // Whether the password form (and so self-service password change) is on: the same
        // flag the bundled login page reads (roadmap Phase 48 slice 4).
        final boolean passwordLoginEnabled = manifest.config()
                .getString("tesseraql.console.login.password.enabled")
                .map(Boolean::parseBoolean).orElse(true);
        // The locales the account surface's language picker offers — the same negotiated set
        // every route resolves against (Phase 22 semantics, one source of truth).
        final List<String> accountLocales = io.tesseraql.compiler.binding.I18nSettings
                .from(manifest.config(), appHome).supportedTags();
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
            // Standing absence rules (roadmap Phase 52): built wherever the task inbox is -
            // every assignee funnel resolves through this one store, one hop, never a chain.
            io.tesseraql.operations.workflow.JdbcDelegationStore delegationStore = new io.tesseraql.operations.workflow.JdbcDelegationStore(
                    dataSource);
            delegationStore.ensureSchema();
            context.getRegistry().bind(TesseraqlProperties.DELEGATION_STORE_BEAN,
                    delegationStore);
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
                        dataSource, delegationStore);
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
        // The outbound egress policy (roadmap Phase 26): deny-by-default allow-list, named
        // credentials, timeouts. One instance gates every framework-issued outbound call —
        // http-call steps here and the Studio copilot endpoint below.
        final io.tesseraql.yaml.http.HttpOutbound httpOutbound = io.tesseraql.yaml.http.HttpOutbound
                .load(manifest.config());
        JobExecutor jobExecutor = new JobExecutor(jobRepository, tempStore, slowSqlLog, tracer)
                .notificationOutbox(outboxStore)
                // Recipient-aware notify steps honor per-user opt-outs (roadmap Phase 48).
                .preferenceStore(preferences)
                // Outbound REST for http-call pipeline steps (roadmap Phase 26): deny-by-default
                // egress, secret-managed credentials, timeouts, and circuit breaking from config.
                .httpCall(new io.tesseraql.operations.http.HttpCallClient(httpOutbound,
                        manifest.config(), tracer));
        // Notification channels and operations alerts (roadmap Phase 20).
        io.tesseraql.yaml.notify.NotificationChannels notificationChannels = io.tesseraql.yaml.notify.NotificationChannels
                .load(manifest.config());
        // Channels the operator marked user-facing (roadmap Phase 48): only these appear on
        // the account page's notification section - system/ops channels never do.
        final List<String> optOutChannels = notificationChannels.names().stream()
                .filter(name -> notificationChannels.require(name).setting("userOptOut")
                        .map(Boolean::parseBoolean).orElse(false))
                .sorted().toList();
        // The in-app inbox (roadmap Phase 49): the store exists exactly when a channel of
        // type inbox is declared - no channel, no table, no bell. ensureSchema is the only
        // owner of tql_user_notification (deliberately outside the Flyway component set).
        // The one live-event hub (docs/inbox.md "Live badge", docs/realtime.md): the inbox
        // badge and the live-view topics share it, and /_tesseraql/events serves both.
        java.util.Set<String> declaredTopics = new java.util.TreeSet<>();
        manifest.routes().forEach(route -> declaredTopics.addAll(route.definition().emit()));
        boolean inboxConfigured = notificationChannels.names().stream()
                .anyMatch(name -> io.tesseraql.yaml.notify.NotificationChannels.INBOX
                        .equals(notificationChannels.require(name).type()));
        final LiveStreams liveStreams = inboxConfigured || !declaredTopics.isEmpty()
                ? new LiveStreams()
                : null;
        final io.tesseraql.core.inbox.InboxStore inboxStore;
        if (inboxConfigured) {
            io.tesseraql.operations.inbox.JdbcInboxStore jdbcInbox = new io.tesseraql.operations.inbox.JdbcInboxStore(
                    dataSource,
                    java.time.Duration.ofDays(manifest.config()
                            .getString("tesseraql.inbox.retentionDays")
                            .map(Long::parseLong).orElse(90L)));
            jdbcInbox.ensureSchema();
            // Wrapped twice: the caching layer keeps the bell's per-page unread count a map
            // lookup (a local mutation invalidates it first), and the notifying layer
            // signals the subject's open /_tesseraql/events streams (docs/inbox.md, "Live
            // badge") — the sink, the pages, and the mark-read routes all share the same
            // wrapper, so every mutation pushes a fresh badge with no per-caller wiring.
            inboxStore = new NotifyingInboxStore(
                    new io.tesseraql.core.inbox.CachingInboxStore(jdbcInbox), liveStreams);
            context.getRegistry().bind(TesseraqlProperties.INBOX_STORE_BEAN, inboxStore);
        } else {
            inboxStore = null;
        }
        if (liveStreams != null) {
            // Live views (docs/realtime.md): commands reach the bus through the registry
            // (TopicEmitProcessor), so hot-reloaded routes keep working. On PostgreSQL the
            // bus rides pg_notify across nodes and the bridge forwards peers' signals into
            // this node's hub; on other databases signals stay per-node (documented).
            if (!declaredTopics.isEmpty()) {
                boolean postgres = "postgresql".equals(
                        io.tesseraql.core.util.DatabaseVendors.vendor(dataSource).orElse(null));
                context.getRegistry().bind(TesseraqlProperties.TOPIC_BUS_BEAN, postgres
                        ? new CrossNodeTopicBus(liveStreams, dataSource)
                        : liveStreams);
                if (postgres) {
                    try {
                        context.addService(new TopicNotifyBridge(dataSource, liveStreams));
                    } catch (Exception ex) {
                        // The bridge is a freshness hint: without it this node still signals
                        // locally, so a wiring failure must not stop the boot.
                        LOG.warn("Cross-node topic bridge not started: {}", ex.getMessage());
                    }
                }
            }
            final io.tesseraql.core.inbox.InboxStore inboxForEvents = inboxStore;
            java.util.Set<String> topics = java.util.Set.copyOf(declaredTopics);
            sseEndpoints.add(() -> LiveEvents.register(context, port, liveStreams,
                    inboxForEvents, topics));
        }
        // Invitations (roadmap Phase 50 slice 2): configured when both the accept-link base
        // and a mail channel are named; anything half-set fails the boot (SEC 4120). The
        // one-time token store is shared with password recovery and built when either flow
        // is on - the iam-admin invite provider below and the identity block both use it.
        final String inviteUrl = manifest.config()
                .getString("tesseraql.identity.invite.url").orElse(null);
        final String inviteChannel = manifest.config()
                .getString("tesseraql.identity.invite.channel").orElse(null);
        final boolean inviteEnabled;
        if (inviteUrl != null || inviteChannel != null) {
            if (inviteUrl == null || inviteChannel == null) {
                throw new io.tesseraql.core.error.TqlException(
                        new io.tesseraql.core.error.TqlErrorCode(
                                io.tesseraql.core.error.TqlDomain.SEC, 4120),
                        "tesseraql.identity.invite needs BOTH channel: and url:");
            }
            if (!io.tesseraql.yaml.notify.NotificationChannels.MAIL.equals(
                    notificationChannels.require(inviteChannel).type())) {
                throw new io.tesseraql.core.error.TqlException(
                        new io.tesseraql.core.error.TqlErrorCode(
                                io.tesseraql.core.error.TqlDomain.SEC, 4120),
                        "Invite channel '" + inviteChannel + "' must be type mail");
            }
            inviteEnabled = true;
        } else {
            inviteEnabled = false;
        }
        final java.time.Duration inviteTtl = java.time.Duration.ofDays(manifest.config()
                .getString("tesseraql.identity.invite.ttlDays")
                .map(Long::parseLong).orElse(7L));
        final boolean recoveryEnabled = manifest.config()
                .getString("tesseraql.identity.recovery.enabled")
                .map(Boolean::parseBoolean).orElse(false);
        final io.tesseraql.core.credential.CredentialTokenStore credentialTokens;
        if (recoveryEnabled || inviteEnabled) {
            io.tesseraql.operations.credential.JdbcCredentialTokenStore jdbcTokens = new io.tesseraql.operations.credential.JdbcCredentialTokenStore(
                    dataSource);
            jdbcTokens.ensureSchema();
            credentialTokens = jdbcTokens;
        } else {
            credentialTokens = null;
        }
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
                    .outboxCounts(outboxStore::countByStatus)
                    // Truthful readiness (roadmap Phase 45): every configured datasource is
                    // probed live; any failure rolls health up to DOWN so a load balancer
                    // actually sheds traffic.
                    .datasourceProbe(() -> probeDatasources(dataSource, dataSources));
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
            int mcpPrompts = mcpApps.stream().mapToInt(app -> app.prompts().size()).sum();
            if ((mcpTools > 0 || mcpResources > 0 || mcpUiResources > 0 || mcpPrompts > 0)
                    && manifest.config().getString("tesseraql.mcp.enabled")
                            .map(Boolean::parseBoolean).orElse(true)) {
                io.tesseraql.mcp.McpServer mcpServer = AppMcpServer.build(appName, mcpApps,
                        context.createProducerTemplate());
                context.addRoutes(new McpRouteBuilder(
                        new io.tesseraql.mcp.McpHttpHandler(mcpServer, null)));
                LOG.info(
                        "Serving {} MCP tool(s), {} resource(s), {} UI resource(s), and {} prompt(s)"
                                + " at /_tesseraql/mcp",
                        mcpTools, mcpResources, mcpUiResources, mcpPrompts);
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
            // The Prometheus scrape endpoint is opt-in and bearer-gated by default; a
            // cluster-internal scraper may opt out of auth explicitly (roadmap Phase 45).
            OperationsRouteBuilder.MetricsSettings metricsSettings = new OperationsRouteBuilder.MetricsSettings(
                    manifest.config().getString("tesseraql.metrics.enabled")
                            .map(Boolean::parseBoolean).orElse(false),
                    manifest.config().getString("tesseraql.metrics.unauthenticated")
                            .map(Boolean::parseBoolean).orElse(false),
                    aggregatingMeter);
            context.addRoutes(new OperationsRouteBuilder(
                    jobRunner, jobRepository, ownedJobs, opsDashboard, outboxStore,
                    metricsSettings, routeAuditStore));
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
                    })
                    // The bundled login page reads which sign-in methods are available (password
                    // always; OIDC/SAML when their extension is enabled) plus the first-login hint.
                    .register("auth.loginMethods", params -> LoginMethods.of(manifest.config()))
                    // The bundled account surface (roadmap Phase 48): the routes map the
                    // session principal's facts into the params, so the providers can only
                    // ever describe — or write for — the caller. Settings write through the
                    // cached preference store bound above; it is null only when the surface
                    // is off, in which case the account routes are not mounted either.
                    .register("account.profile.view", AccountViews::profile)
                    .register("account.settings.view",
                            params -> AccountViews.settings(params, preferences,
                                    accountLocales, optOutChannels, sessionStore,
                                    passwordLoginEnabled,
                                    io.tesseraql.yaml.account.PreferencesSpec.live(appHome),
                                    context.getRegistry().lookupByNameAndType(
                                            TesseraqlProperties.TOTP_STORE_BEAN,
                                            io.tesseraql.core.credential.TotpStore.class),
                                    appName,
                                    context.getRegistry().lookupByNameAndType(
                                            TesseraqlProperties.DELEGATION_STORE_BEAN,
                                            io.tesseraql.core.workflow.DelegationStore.class),
                                    shortcuts))
                    .register("account.language.save",
                            params -> AccountViews.saveLanguage(params, preferences,
                                    accountLocales))
                    .register("account.theme.save",
                            params -> AccountViews.saveTheme(params, preferences))
                    .register("account.notify.save",
                            params -> AccountViews.saveNotifyOptOut(params, preferences,
                                    optOutChannels))
                    .register("account.app.save",
                            params -> AccountViews.saveAppPreference(params, preferences,
                                    io.tesseraql.yaml.account.PreferencesSpec.live(appHome)))
                    // Identity and realm resolve from the registry at call time: they are
                    // bound after this chain builds, and an SSO-only deployment answers with
                    // the honest 4803 instead of failing to register.
                    // The in-app inbox surface (roadmap Phase 49 slice 2): list, mark one
                    // read, mark all read - the subject always the session principal's.
                    .register("account.inbox.view",
                            params -> AccountViews.inbox(params, inboxStore))
                    .register("account.inbox.read",
                            params -> AccountViews.markInboxRead(params, inboxStore))
                    .register("account.inbox.readAll",
                            params -> AccountViews.markAllInboxRead(params, inboxStore))
                    // The iam-admin invite (roadmap Phase 50 slice 2): identity and realm
                    // resolve from the registry at call time (they bind later); the token
                    // store and channel settings are the hoisted finals above.
                    .register("identity.invite",
                            params -> IdentityInvites.invite(params, credentialTokens,
                                    outboxStore,
                                    context.getRegistry().lookupByNameAndType(
                                            TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                                            io.tesseraql.identity.IdentityService.class),
                                    context.getRegistry().lookupByNameAndType(
                                            TesseraqlProperties.IDENTITY_REALM_BEAN,
                                            io.tesseraql.identity.RealmConfig.class),
                                    inviteChannel, inviteUrl, inviteTtl, appName,
                                    inviteEnabled))
                    // TOTP self-service (roadmap Phase 50 slice 3): begin/confirm/disable.
                    // The store binds in the identity block, so resolve lazily like
                    // identity/realm; disable re-verifies the password.
                    .register("account.totp.begin",
                            params -> AccountViews.totpBegin(params,
                                    context.getRegistry().lookupByNameAndType(
                                            TesseraqlProperties.TOTP_STORE_BEAN,
                                            io.tesseraql.core.credential.TotpStore.class)))
                    .register("account.totp.confirm",
                            params -> AccountViews.totpConfirm(params,
                                    context.getRegistry().lookupByNameAndType(
                                            TesseraqlProperties.TOTP_STORE_BEAN,
                                            io.tesseraql.core.credential.TotpStore.class)))
                    .register("account.totp.disable",
                            params -> AccountViews.totpDisable(params,
                                    context.getRegistry().lookupByNameAndType(
                                            TesseraqlProperties.TOTP_STORE_BEAN,
                                            io.tesseraql.core.credential.TotpStore.class),
                                    context.getRegistry().lookupByNameAndType(
                                            TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                                            io.tesseraql.identity.IdentityService.class),
                                    context.getRegistry().lookupByNameAndType(
                                            TesseraqlProperties.IDENTITY_REALM_BEAN,
                                            io.tesseraql.identity.RealmConfig.class)))
                    // The operator's delegation visibility (roadmap Phase 52 slice 2):
                    // read-only rows for the IAM admin panel, tenant-scoped to the caller.
                    .register("identity.delegations", params -> {
                        io.tesseraql.core.workflow.DelegationStore store = context.getRegistry()
                                .lookupByNameAndType(TesseraqlProperties.DELEGATION_STORE_BEAN,
                                        io.tesseraql.core.workflow.DelegationStore.class);
                        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
                        if (store != null) {
                            java.time.Instant now = java.time.Instant.now();
                            for (io.tesseraql.core.workflow.DelegationStore.Entry entry : store
                                    .unexpired(params.get("tenantId") == null
                                            ? null
                                            : String.valueOf(params.get("tenantId")),
                                            now, 200)) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("subject", entry.subject());
                                row.put("delegate", entry.delegateSubject());
                                row.put("startsAt", entry.startsAt().toString());
                                row.put("endsAt", entry.endsAt().toString());
                                row.put("active", !now.isBefore(entry.startsAt()));
                                rows.add(row);
                            }
                        }
                        return rows;
                    })
                    // Pins and recents (roadmap Phase 51): toggle the current page, remove
                    // from the account card - the caller's own shortcuts only.
                    .register("account.pins.toggle",
                            params -> AccountViews.togglePin(params, shortcuts))
                    .register("account.shortcuts.remove",
                            params -> AccountViews.removeShortcut(params, shortcuts))
                    // Out-of-office self-service (roadmap Phase 52); store binds with the
                    // task inbox, identity/realm resolve lazily like the neighbours.
                    .register("account.delegation.save",
                            params -> AccountViews.saveDelegation(params,
                                    context.getRegistry().lookupByNameAndType(
                                            TesseraqlProperties.DELEGATION_STORE_BEAN,
                                            io.tesseraql.core.workflow.DelegationStore.class),
                                    context.getRegistry().lookupByNameAndType(
                                            TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                                            io.tesseraql.identity.IdentityService.class),
                                    context.getRegistry().lookupByNameAndType(
                                            TesseraqlProperties.IDENTITY_REALM_BEAN,
                                            io.tesseraql.identity.RealmConfig.class)))
                    .register("account.delegation.clear",
                            params -> AccountViews.clearDelegation(params,
                                    context.getRegistry().lookupByNameAndType(
                                            TesseraqlProperties.DELEGATION_STORE_BEAN,
                                            io.tesseraql.core.workflow.DelegationStore.class)))
                    .register("account.password.change",
                            params -> AccountViews.changePassword(params,
                                    context.getRegistry().lookupByNameAndType(
                                            TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                                            io.tesseraql.identity.IdentityService.class),
                                    context.getRegistry().lookupByNameAndType(
                                            TesseraqlProperties.IDENTITY_REALM_BEAN,
                                            io.tesseraql.identity.RealmConfig.class),
                                    passwordLoginEnabled));
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
                    jobOwners, appHome));
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
            // TOTP enrollments (roadmap Phase 50 slice 3): available wherever password
            // login is - the account page enrolls, the login route enforces.
            io.tesseraql.operations.credential.JdbcTotpStore totpStore = new io.tesseraql.operations.credential.JdbcTotpStore(
                    dataSource);
            totpStore.ensureSchema();
            context.getRegistry().bind(TesseraqlProperties.TOTP_STORE_BEAN, totpStore);
            context.addRoutes(new LoginRouteBuilder(
                    new PasswordAuthenticator(identity), realm, sessionStore, totpStore));
            // The IAM Admin bulk endpoint (docs/hypermedia-ui.md "Bulk actions"): Java
            // because the form posts repeated ids fields, which the Simple-YAML input
            // surface deliberately does not model. Gated by iam.admin.write like the
            // per-user routes the iam-admin app compiles.
            context.addRoutes(new IamAdminRouteBuilder());
            // Password recovery (roadmap Phase 50 slice 1): fail-fast validation - a half
            // configuration must not silently produce a reset page that goes nowhere.
            String recoveryChannel = null;
            String recoveryUrl = null;
            if (recoveryEnabled) {
                recoveryChannel = manifest.config()
                        .getString("tesseraql.identity.recovery.channel")
                        .orElseThrow(() -> new io.tesseraql.core.error.TqlException(
                                new io.tesseraql.core.error.TqlErrorCode(
                                        io.tesseraql.core.error.TqlDomain.SEC, 4120),
                                "tesseraql.identity.recovery.enabled needs a channel:"));
                if (!io.tesseraql.yaml.notify.NotificationChannels.MAIL.equals(
                        notificationChannels.require(recoveryChannel).type())) {
                    throw new io.tesseraql.core.error.TqlException(
                            new io.tesseraql.core.error.TqlErrorCode(
                                    io.tesseraql.core.error.TqlDomain.SEC, 4120),
                            "Recovery channel '" + recoveryChannel + "' must be type mail");
                }
                recoveryUrl = manifest.config()
                        .getString("tesseraql.identity.recovery.url")
                        .orElseThrow(() -> new io.tesseraql.core.error.TqlException(
                                new io.tesseraql.core.error.TqlErrorCode(
                                        io.tesseraql.core.error.TqlDomain.SEC, 4120),
                                "tesseraql.identity.recovery.enabled needs a url:"));
            }
            if (recoveryEnabled || inviteEnabled) {
                context.addRoutes(new RecoveryRouteBuilder(credentialTokens, identity, realm,
                        sessionStore, outboxStore, recoveryChannel, recoveryUrl,
                        java.time.Duration.ofMinutes(manifest.config()
                                .getString("tesseraql.identity.recovery.ttlMinutes")
                                .map(Long::parseLong).orElse(30L)),
                        appName, inviteEnabled));
            }
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
                            : new NotificationSink(notificationChannels, appHome, context,
                                    inboxStore);
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
            boolean readOnly = manifest.config().getString("tesseraql.studio.readOnly")
                    .map(Boolean::parseBoolean).orElse(true);
            io.tesseraql.studio.StudioService studio = new io.tesseraql.studio.StudioService(
                    manifest, readOnly);
            RouteReloader reloader = new RouteReloader(context, appHome, manifest, studio,
                    appName, mountedApps);
            // The serve --watch file watcher drives the exact reloader Studio's apply uses;
            // bound unstarted (independent of Studio being enabled) so watchRoutes() can
            // start it on demand without threading it through the runtime constructor.
            context.getRegistry().bind(RouteWatcher.BEAN, new RouteWatcher(appHome, reloader));
            if (manifest.config().getString("tesseraql.studio.enabled")
                    .map(Boolean::parseBoolean).orElse(true)) {
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
                // The Studio data browser: read-only, paginated row access over the dev datasource.
                // Opt-in (exposes data); read-only connection + statement timeout + a scan cap.
                boolean dataBrowserEnabled = manifest.config()
                        .getString("tesseraql.studio.dataBrowser.enabled")
                        .map(Boolean::parseBoolean).orElse(false);
                // Row editing is its own opt-in on top of the browser (roadmap Phase 43, Track
                // J4): it writes business data, so browsing alone never implies it.
                boolean dataEditEnabled = manifest.config()
                        .getString("tesseraql.studio.dataBrowser.edit.enabled")
                        .map(Boolean::parseBoolean).orElse(false);
                // Copilot (roadmap Phase 44): entirely absent unless the operator opts in
                // and names an endpoint + model; the api key stays a lazy config read so a
                // ${secret.*} reference resolves at call time, never at startup. The endpoint
                // must pass the same deny-by-default egress allow-list an http-call step
                // obeys — an off-allow-list host fails the boot (SEC 4085).
                final io.tesseraql.studio.CopilotService copilotService = manifest.config()
                        .getString("tesseraql.copilot.enabled")
                        .map(Boolean::parseBoolean).orElse(false)
                                ? new io.tesseraql.studio.CopilotService(studio, manifest,
                                        copilotEndpoint(manifest.config(), httpOutbound),
                                        manifest.config()
                                                .requireString("tesseraql.copilot.model"),
                                        () -> manifest.config()
                                                .getString("tesseraql.copilot.apiKey")
                                                .orElse(null),
                                        manifest.config()
                                                .getString("tesseraql.copilot.maxTurns")
                                                .map(Integer::parseInt).orElse(6))
                                : null;
                StudioDataService studioData = new StudioDataService(
                        name -> context.getRegistry().lookupByNameAndType(name,
                                javax.sql.DataSource.class),
                        dataBrowserEnabled, dataEditEnabled, testTimeout, testMaxRows);
                // Granular read-only (backlog D6): an optional editRoles allow-list refines the
                // writable master switch — when set, only callers holding one of those roles may edit.
                java.util.Set<String> editRoles = manifest.config()
                        .getString("tesseraql.studio.editRoles")
                        .map(roles -> java.util.Arrays.stream(roles.split(","))
                                .map(String::trim).filter(role -> !role.isEmpty())
                                .collect(java.util.stream.Collectors.toSet()))
                        .orElse(java.util.Set.of());
                // Confirm-diff-before-every-apply (Studio backlog D5 follow-up): an opt-in gate that
                // makes the editor acknowledge the diff before each apply, not only on a conflict.
                boolean confirmApply = manifest.config()
                        .getString("tesseraql.studio.confirmApply")
                        .map(Boolean::parseBoolean).orElse(false);
                StudioAccess studioAccess = new StudioAccess(!readOnly, editRoles, confirmApply);
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
                // The copilot's send + stream transports (docs/copilot.md): below the YAML
                // surface because of streaming and HX-Request negotiation. Send is a Camel
                // route; the stream is an SseRoutes endpoint registered after start.
                // Mounted with Studio; unconfigured stays a clean TQL-STUDIO-4235 refusal.
                context.addRoutes(new CopilotRouteBuilder(copilotService, studioAccess));
                sseEndpoints.add(() -> CopilotRouteBuilder.registerStream(context, port,
                        copilotService, studioAccess));
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
                        // The New-route drawer fragment (Studio sidebar IA): echoes the folder prefix a
                        // "new route here" trigger passes so the form's Path seeds to the browsed
                        // location. A tiny provider because response.html models resolve from `sql`.
                        .register("studio.newForm", params -> {
                            Object prefix = params.get("prefix");
                            return Map.of("prefix", prefix == null ? "" : String.valueOf(prefix));
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
                            // The Studio-to-editor half of the boundary's round trip (Phase 57).
                            model.put("editorHref", studio.editorHref(path));
                            // Offer the "run tests" action on a route page only when A2 is enabled.
                            model.put("testRunnerEnabled", testRunnerEnabled);
                            // Warn when applying would overwrite a concurrently changed source (D5).
                            model.put("conflict", draft != null && studio.draftConflicts(path));
                            // Require an explicit diff acknowledgment before apply when configured.
                            model.put("confirmApply", studioAccess.confirmApply());
                            // The edit surface follows the caller's edit permission (backlog D6).
                            boolean canEdit = studioAccess.canEdit(params.get("roles"));
                            model.put("editable", canEdit);
                            model.put("readOnly", !canEdit);
                            // On a route SQL file, offer the 2-way SQL builder inline (insert into the
                            // editor): populate its table dropdown from the schema overlay.
                            if (Boolean.TRUE.equals(model.get("isRouteSql"))) {
                                java.util.List<String> tables = new io.tesseraql.studio.DocService(
                                        manifest).tableNames();
                                model.put("tables", tables);
                                model.put("hasTables", !tables.isEmpty());
                            }
                            return model;
                        })
                        .register("studio.save", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            String path = String.valueOf(params.get("path"));
                            Object content = params.get("content");
                            studio.saveDraft(path, content == null ? "" : String.valueOf(content));
                            return Map.of("saved", path);
                        })
                        // The form-driven route editor (roadmap Phase 43, Track J1): the
                        // governed fields — recipe, auth, policy, CSRF, inputs — as structured
                        // form fields over the same document tree; saving lands a draft and the
                        // text editor stays the escape hatch.
                        // The Studio copilot (roadmap Phase 44, decision point 8): a chat
                        // loop against an OPERATOR-CONFIGURED model endpoint — no model
                        // shipped, no key in app source (the credential resolves lazily
                        // through the config placeholder chain), reads free, writes only as
                        // audited DRAFTS a human applies in the editor.
                        .register("studio.copilot.view", params -> {
                            Map<String, Object> model = new java.util.LinkedHashMap<>();
                            model.put("enabled", copilotService != null);
                            boolean canEdit = studioAccess.canEdit(params.get("roles"));
                            model.put("editable", canEdit);
                            // Entries arrive pre-rendered (CopilotFragments, the single
                            // markup source shared with the SSE done event); the message
                            // input too, so the htmx out-of-band clear cannot drift from
                            // the page's own form.
                            model.put("entries", copilotService == null
                                    ? java.util.List.of()
                                    : copilotService.transcript(actorOf(params)).stream()
                                            .map(io.tesseraql.studio.CopilotFragments::entryHtml)
                                            .toList());
                            model.put("input",
                                    io.tesseraql.studio.CopilotFragments.messageInput(false));
                            return model;
                        })
                        .register("studio.copilot.reset", params -> {
                            requireCopilot(copilotService);
                            copilotService.reset(actorOf(params));
                            return Map.of("reset", true);
                        })
                        .register("studio.routeForm.view", params -> {
                            String path = String.valueOf(params.get("path"));
                            boolean canEdit = studioAccess.canEdit(params.get("roles"));
                            Map<String, Object> model = new java.util.LinkedHashMap<>();
                            model.put("form", studio.routeForm(path));
                            model.put("editable", canEdit);
                            model.put("readOnly", !canEdit);
                            // Framework-derived options (roadmap Phase 57): the same surfaces
                            // the shipped JSON Schema is drift-tested against, so the schema,
                            // the linter, and this form can never disagree.
                            model.put("recipes", io.tesseraql.yaml.lint.AppLinter
                                    .knownRouteRecipes().stream().sorted().toList());
                            model.put("authOptions", io.tesseraql.yaml.lint.AppLinter
                                    .knownAuthModes().stream().sorted().toList());
                            model.put("inputTypes", io.tesseraql.yaml.lint.AppLinter
                                    .knownInputTypes().stream().sorted().toList());
                            java.util.List<String> policyIds = studio.securityPolicies().stream()
                                    .map(policy -> String.valueOf(policy.get("id"))).toList();
                            model.put("policyOptions", policyIds);
                            model.put("slots", ROUTE_FORM_INPUT_SLOTS);
                            return model;
                        })
                        .register("studio.routeForm.save", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            String path = String.valueOf(params.get("path"));
                            java.util.List<io.tesseraql.studio.StudioService.FormInput> inputs = new java.util.ArrayList<>();
                            for (int i = 0; i < ROUTE_FORM_INPUT_SLOTS; i++) {
                                inputs.add(new io.tesseraql.studio.StudioService.FormInput(
                                        str(params, "in" + i + "name"),
                                        str(params, "in" + i + "type"),
                                        params.get("in" + i + "req") != null,
                                        str(params, "in" + i + "min"),
                                        str(params, "in" + i + "max"),
                                        str(params, "in" + i + "maxlen"),
                                        str(params, "in" + i + "minlen"),
                                        str(params, "in" + i + "pattern"),
                                        str(params, "in" + i + "enum")));
                            }
                            studio.routeFormSave(path, str(params, "recipe"),
                                    str(params, "auth"), str(params, "policy"),
                                    params.get("csrf") != null, inputs);
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
                        // Menu editor (app sidebar menu, Slice 2): read/write the app's declarative,
                        // role-filtered config/menu.yml. view renders the current items + add form;
                        // add/remove/move mutate the file (edit-gated + audited in StudioService).
                        // preview renders the menu exactly as a caller with a chosen role/permission
                        // would see it (server-side visibleFor). A menu edit is deliberately not tied
                        // to a full route reload (like createMigration): renderers read the menu via
                        // MenuSpec.live (a stat-cheap re-read), so an edit shows on the next rendered
                        // page immediately, yet editing the sidebar can never be broken by an unrelated
                        // route that fails to recompile.
                        .register("studio.menu.view", params -> {
                            boolean canEdit = studioAccess
                                    .canEdit(params.get("principalRoles") == null
                                            ? params.get("roles")
                                            : params.get("principalRoles"));
                            java.util.List<io.tesseraql.yaml.menu.MenuSpec.MenuItem> items = studio
                                    .menuItems();
                            // Known route paths back both href autocomplete and the per-item
                            // dangling-href hint (an href that matches no served route).
                            java.util.List<String> paths = studio.routePaths();
                            java.util.Set<String> pathSet = new java.util.HashSet<>(paths);
                            java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
                            for (int i = 0; i < items.size(); i++) {
                                io.tesseraql.yaml.menu.MenuSpec.MenuItem item = items.get(i);
                                Map<String, Object> row = new java.util.LinkedHashMap<>();
                                row.put("index", i);
                                row.put("first", i == 0);
                                row.put("last", i == items.size() - 1);
                                row.put("label", item.label());
                                row.put("href", item.href());
                                row.put("icon", item.icon());
                                boolean isPublic = item.roles().isEmpty()
                                        && item.permissions().isEmpty();
                                row.put("public", isPublic);
                                row.put("visibility", menuVisibility(item));
                                // An href pointing at no served route is flagged (not an error — it
                                // may be an external link, an asset, or another mounted app).
                                row.put("unmatched", item.href() != null && !item.href().isBlank()
                                        && !pathSet.contains(item.href()));
                                rows.add(row);
                            }
                            Map<String, Object> model = new java.util.LinkedHashMap<>();
                            model.put("editable", canEdit);
                            model.put("readOnly", !canEdit);
                            model.put("items", rows);
                            model.put("hasItems", !rows.isEmpty());
                            model.put("roleOptions", studio.knownRoles());
                            model.put("permissionOptions", studio.knownPermissions());
                            model.put("hrefOptions", paths);
                            model.put("iconOptions", MENU_ICON_OPTIONS);
                            return model;
                        })
                        .register("studio.menu.add", params -> {
                            studioAccess.requireEdit(params.get("principalRoles"));
                            studio.addMenuItem(str(params, "label"), str(params, "href"),
                                    str(params, "icon"), str(params, "roles"),
                                    str(params, "permissions"), actorOf(params));
                            return Map.of("added", true);
                        })
                        .register("studio.menu.remove", params -> {
                            studioAccess.requireEdit(params.get("principalRoles"));
                            studio.removeMenuItem(parseIndex(params.get("index")), actorOf(params));
                            return Map.of("removed", true);
                        })
                        .register("studio.menu.move", params -> {
                            studioAccess.requireEdit(params.get("principalRoles"));
                            int delta = "up".equals(String.valueOf(params.get("dir"))) ? -1 : 1;
                            studio.moveMenuItem(parseIndex(params.get("index")), delta,
                                    actorOf(params));
                            return Map.of("moved", true);
                        })
                        .register("studio.menu.preview", params -> {
                            String role = str(params, "role");
                            String permission = str(params, "permission");
                            java.util.List<String> roles = role == null
                                    ? java.util.List.of()
                                    : java.util.List.of(role);
                            java.util.List<String> perms = permission == null
                                    ? java.util.List.of()
                                    : java.util.List.of(permission);
                            java.util.List<Map<String, Object>> visible = new java.util.ArrayList<>();
                            for (io.tesseraql.yaml.menu.MenuSpec.MenuItem item : io.tesseraql.yaml.menu.MenuSpec
                                    .load(manifest.appHome()).visibleFor(roles, perms)) {
                                Map<String, Object> row = new java.util.LinkedHashMap<>();
                                row.put("label", item.label());
                                row.put("href", item.href());
                                row.put("icon", item.icon());
                                visible.add(row);
                            }
                            String who = role == null && permission == null
                                    ? "an anonymous caller"
                                    : java.util.stream.Stream.of(
                                            role == null ? null : "role " + role,
                                            permission == null ? null : "permission " + permission)
                                            .filter(java.util.Objects::nonNull)
                                            .collect(java.util.stream.Collectors.joining(" + "));
                            Map<String, Object> model = new java.util.LinkedHashMap<>();
                            model.put("items", visible);
                            model.put("empty", visible.isEmpty());
                            model.put("summary", "Showing the menu for " + who + ".");
                            return model;
                        })
                        // Health dashboard (governance): runs the same AppLinter as the CLI/Maven lint
                        // over the app and surfaces its findings grouped by severity, each linking to
                        // the source editor. An app that fails to even load is shown as one blocking
                        // finding rather than a 500, so the dashboard is usable exactly when it matters.
                        .register("studio.health", params -> {
                            java.util.List<io.tesseraql.yaml.lint.LintFinding> findings;
                            try {
                                findings = studio.health();
                            } catch (RuntimeException ex) {
                                findings = java.util.List.of(new io.tesseraql.yaml.lint.LintFinding(
                                        "TQL-STUDIO-4225", "error", "app",
                                        "The app failed to load: " + ex.getMessage()));
                            }
                            int errors = 0;
                            java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
                            for (io.tesseraql.yaml.lint.LintFinding finding : findings) {
                                if (finding.isError()) {
                                    errors++;
                                }
                                Map<String, Object> row = new java.util.LinkedHashMap<>();
                                row.put("code", finding.code());
                                row.put("severity", finding.severity());
                                row.put("error", finding.isError());
                                row.put("source", finding.source());
                                row.put("line", finding.line());
                                row.put("message", finding.message());
                                // Link to the source editor when the finding names an app file (not a
                                // config-level pseudo-source like "config"/"app").
                                boolean editable = finding.source() != null
                                        && finding.source().contains("/");
                                row.put("sourceUrl", editable
                                        ? "/_tesseraql/studio/ui/source?path=" + java.net.URLEncoder
                                                .encode(finding.source(),
                                                        java.nio.charset.StandardCharsets.UTF_8)
                                        : null);
                                rows.add(row);
                            }
                            Map<String, Object> model = new java.util.LinkedHashMap<>();
                            model.put("findings", rows);
                            model.put("total", findings.size());
                            model.put("errors", errors);
                            model.put("warnings", findings.size() - errors);
                            model.put("clean", findings.isEmpty());
                            return model;
                        })
                        // Security overview (governance): the route-to-policy map + the app's policies,
                        // flagging unprotected routes (no auth), CSRF gaps (state-changing browser
                        // routes without CSRF), and routes referencing an undefined policy. Read-only
                        // in this slice; editing policies is a later slice.
                        .register("studio.security", params -> {
                            java.util.List<Map<String, Object>> policies = studio
                                    .securityPolicies();
                            java.util.Set<String> policyIds = new java.util.HashSet<>();
                            for (Map<String, Object> policy : policies) {
                                policyIds.add(String.valueOf(policy.get("id")));
                            }
                            java.util.List<Map<String, Object>> routes = studio.routeSecurity();
                            int unprotected = 0;
                            int csrfGaps = 0;
                            int unknownPolicies = 0;
                            for (Map<String, Object> route : routes) {
                                if (Boolean.TRUE.equals(route.get("unprotected"))) {
                                    unprotected++;
                                }
                                if (Boolean.TRUE.equals(route.get("csrfGap"))) {
                                    csrfGaps++;
                                }
                                Object policy = route.get("policy");
                                boolean unknown = policy != null
                                        && !policyIds.contains(String.valueOf(policy));
                                route.put("unknownPolicy", unknown);
                                if (unknown) {
                                    unknownPolicies++;
                                }
                                route.put("sourceUrl", "/_tesseraql/studio/ui/source?path="
                                        + java.net.URLEncoder.encode(
                                                String.valueOf(route.get("source")),
                                                java.nio.charset.StandardCharsets.UTF_8));
                            }
                            // Reverse index: which routes each policy guards, so a policy links to
                            // its consumers and a policy used by none is flagged as dead config.
                            Map<String, java.util.List<Map<String, Object>>> byPolicy = new java.util.HashMap<>();
                            for (Map<String, Object> route : routes) {
                                Object policy = route.get("policy");
                                if (policy != null) {
                                    byPolicy.computeIfAbsent(String.valueOf(policy),
                                            key -> new java.util.ArrayList<>()).add(route);
                                }
                            }
                            int unusedPolicies = 0;
                            for (Map<String, Object> policy : policies) {
                                java.util.List<Map<String, Object>> used = byPolicy.getOrDefault(
                                        String.valueOf(policy.get("id")), java.util.List.of());
                                policy.put("routes", used);
                                policy.put("usedBy", used.size());
                                policy.put("unused", used.isEmpty());
                                if (used.isEmpty()) {
                                    unusedPolicies++;
                                }
                            }
                            Map<String, Object> model = new java.util.LinkedHashMap<>();
                            model.put("routes", routes);
                            model.put("policies", policies);
                            model.put("totalRoutes", routes.size());
                            model.put("unprotected", unprotected);
                            model.put("csrfGaps", csrfGaps);
                            model.put("unknownPolicies", unknownPolicies);
                            model.put("unusedPolicies", unusedPolicies);
                            model.put("policyCount", policies.size());
                            model.put("editable", studioAccess.canEdit(params.get("roles")));
                            return model;
                        })
                        // Policy editing (security overview, edit slice): grant/revoke a role or
                        // permission rule on a policy. StudioService writes the policy's full rule set
                        // to config/overlay.yml (the base config is untouched; overlay overrides it),
                        // gated + audited; then the PolicyEngine is rebuilt from the fresh config and
                        // rebound, so the change is authorized live on the next request — no restart.
                        .register("studio.policyAddRule", params -> {
                            studioAccess.requireEdit(params.get("principalRoles"));
                            studio.addPolicyRule(str(params, "policy"), str(params, "kind"),
                                    str(params, "value"), actorOf(params));
                            rebindPolicyEngine(context, manifest.appHome());
                            return Map.of("added", true);
                        })
                        .register("studio.policyRemoveRule", params -> {
                            studioAccess.requireEdit(params.get("principalRoles"));
                            studio.removePolicyRule(str(params, "policy"), str(params, "kind"),
                                    str(params, "value"), actorOf(params));
                            rebindPolicyEngine(context, manifest.appHome());
                            return Map.of("removed", true);
                        })
                        // API try-it console: invoke one of the app's own routes and show the raw
                        // response (status, headers, body). The run proxies a loopback HTTP call to
                        // 127.0.0.1:<port><path> — confined to app-relative paths (no host, no scheme),
                        // so it can never reach off-box (no SSRF). The target route enforces its own
                        // security, so try-it grants nothing extra: public routes just work, bearer
                        // routes take a token the caller pastes. (Browser-session forwarding is a later
                        // slice.) view supplies the served route paths for the path autocomplete.
                        .register("studio.tryit", params -> {
                            Map<String, Object> model = new java.util.LinkedHashMap<>();
                            model.put("pathOptions", studio.routePaths());
                            // Deep-linked ?path=&method= (e.g. from a route's docs page) prefills the
                            // form + a request-body/query skeleton from the route's declared inputs.
                            model.put("prefill", studio.tryPrefill(str(params, "method"),
                                    str(params, "path")));
                            return model;
                        })
                        .register("studio.tryRun", params -> {
                            Map<String, Object> result = tryInvoke(port, params);
                            // The test recorder (Track J3): a recordable invocation offers "Save
                            // as test case" on the result fragment, echoing what was sent.
                            result.putAll(studio.recordability(
                                    String.valueOf(result.get("method")), str(params, "path")));
                            result.put("query", str(params, "query"));
                            result.put("sentBody", str(params, "body"));
                            return result;
                        })
                        .register("studio.tryRecord", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            String method = String.valueOf(params.get("method"));
                            String path = String.valueOf(params.get("path"));
                            Map<String, Object> recordable = studio.recordability(method, path);
                            if (!Boolean.TRUE.equals(recordable.get("recordable"))) {
                                throw new io.tesseraql.core.error.TqlException(
                                        new io.tesseraql.core.error.TqlErrorCode(
                                                io.tesseraql.core.error.TqlDomain.STUDIO, 4233),
                                        String.valueOf(recordable.get("reason")));
                            }
                            Map<String, String> query = parseQueryString(str(params, "query"));
                            Map<String, Object> body = parseJsonObject(str(params, "body"));
                            Map<String, Object> caseParams = studio.recordedCaseParams(method,
                                    path, query, body);
                            // The sandbox captures the expectation so the recorded case is a
                            // real data regression, passing by construction. Optional: without
                            // the test runner the case still records (no expectation).
                            Integer rowCount = null;
                            if (studioTests.isEnabled()) {
                                io.tesseraql.yaml.manifest.RouteFile match = null;
                                for (io.tesseraql.yaml.manifest.RouteFile route : manifest
                                        .routes()) {
                                    if (path.equals(route.urlPath())
                                            && method.equalsIgnoreCase(route.httpMethod())) {
                                        match = route;
                                        break;
                                    }
                                }
                                if (match != null) {
                                    Map<String, Object> recordContext = new java.util.LinkedHashMap<>();
                                    recordContext.put("query", query);
                                    recordContext.put("params", body.isEmpty() ? query : body);
                                    rowCount = studioTests.sandboxRowCount(match.definition(),
                                            match.source().getParent(), recordContext);
                                }
                            }
                            String name = studio.appendRecordedTest(str(params, "name"),
                                    studio.recordedSqlFile(method, path), caseParams, rowCount,
                                    actorOf(params));
                            return Map.of("recorded", name);
                        })
                        // Config viewer (governance): the effective merged configuration (application
                        // .yml + tesseraql.yml + overlay.yml), flattened to dotted keys, with secret
                        // values redacted. Read-only — a curated overlay-backed editor is a later slice.
                        // Connector & SSO authoring (roadmap Phase 43, Track J2): the managed
                        // connector config — egress allow-lists, outbound/poll credentials,
                        // webhook verifiers — and the IAM wizards write config/overlay.yml
                        // through the same gated path as policies. Secret REFERENCES only;
                        // egress changes are always confirm-gated; all of it restart-bound
                        // (these sections load at boot), which the pages state.
                        .register("studio.connectors.view", params -> {
                            boolean canEdit = studioAccess.canEdit(params.get("roles"));
                            Map<String, Object> model = new java.util.LinkedHashMap<>(
                                    studio.connectorsView());
                            model.put("saved", params.get("saved"));
                            model.put("editable", canEdit);
                            model.put("readOnly", !canEdit);
                            return model;
                        })
                        .register("studio.connectors.egress", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            requireExplicitConfirm(params, "Egress allow-list changes");
                            studio.updateEgressHosts(str(params, "scope"), str(params, "host"),
                                    "true".equals(String.valueOf(params.get("remove"))),
                                    actorOf(params));
                            return Map.of("saved", true);
                        })
                        .register("studio.connectors.webhook", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            studio.writeWebhookVerifier(str(params, "name"),
                                    str(params, "secret"), str(params, "signatureHeader"),
                                    str(params, "timestampHeader"), str(params, "idHeader"),
                                    str(params, "tolerance"), actorOf(params));
                            return Map.of("saved", true);
                        })
                        .register("studio.connectors.credential", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            studio.writeConnectorCredential(str(params, "scope"),
                                    str(params, "name"), str(params, "type"),
                                    str(params, "token"), str(params, "username"),
                                    str(params, "password"), str(params, "header"),
                                    str(params, "value"), actorOf(params));
                            return Map.of("saved", true);
                        })
                        .register("studio.wizard.oidc.apply", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            Map<String, Object> values = new java.util.LinkedHashMap<>();
                            values.put("tesseraql.oidc.enabled", true);
                            values.put("tesseraql.oidc.discoveryUri",
                                    requiredParam(params, "discoveryUri"));
                            values.put("tesseraql.oidc.clientId",
                                    requiredParam(params, "clientId"));
                            values.put("tesseraql.oidc.redirectUri",
                                    requiredParam(params, "redirectUri"));
                            putIfPresent(values, "tesseraql.oidc.scopes", params, "scopes");
                            putIfPresent(values, "tesseraql.oidc.postLoginUrl", params,
                                    "postLoginUrl");
                            String clientSecret = io.tesseraql.studio.StudioService
                                    .secretReferenceOrNull("The OIDC client secret",
                                            str(params, "clientSecret"));
                            if (clientSecret != null) {
                                values.put("tesseraql.oidc.clientSecret", clientSecret);
                            }
                            values.put("tesseraql.oidc.link.enabled", true);
                            values.put("tesseraql.oidc.link.provision",
                                    "true".equals(str(params, "provision")));
                            studio.writeOverlaySection(values, "sso", actorOf(params));
                            return Map.of("applied", "oidc");
                        })
                        .register("studio.wizard.saml.apply", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            Map<String, Object> values = new java.util.LinkedHashMap<>();
                            values.put("tesseraql.saml.enabled", true);
                            values.put("tesseraql.saml.sp.audience",
                                    requiredParam(params, "spAudience"));
                            values.put("tesseraql.saml.sp.acsUrl",
                                    requiredParam(params, "acsUrl"));
                            putIfPresent(values, "tesseraql.saml.sp.nameIdFormat", params,
                                    "nameIdFormat");
                            values.put("tesseraql.saml.idp.ssoUrl",
                                    requiredParam(params, "ssoUrl"));
                            putIfPresent(values, "tesseraql.saml.idp.sloUrl", params, "sloUrl");
                            putIfPresent(values, "tesseraql.saml.idp.metadata", params,
                                    "idpMetadataPath");
                            putIfPresent(values, "tesseraql.saml.idp.publicKey", params,
                                    "publicKeyPath");
                            values.put("tesseraql.saml.attributes.loginId",
                                    requiredParam(params, "loginIdAttribute"));
                            putIfPresent(values, "tesseraql.saml.attributes.email", params,
                                    "emailAttribute");
                            values.put("tesseraql.saml.link.enabled", true);
                            values.put("tesseraql.saml.link.provision",
                                    "true".equals(str(params, "provision")));
                            studio.writeOverlaySection(values, "sso", actorOf(params));
                            return Map.of("applied", "saml");
                        })
                        .register("studio.wizard.scim.apply", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            Map<String, Object> values = new java.util.LinkedHashMap<>();
                            values.put("tesseraql.scim.enabled", true);
                            values.put("tesseraql.scim.groups.enabled",
                                    "true".equals(str(params, "groups")));
                            boolean outbound = "true".equals(str(params, "outbound"));
                            values.put("tesseraql.scim.outbound.enabled", outbound);
                            String outboundUrl = str(params, "outboundUrl");
                            if (outbound && outboundUrl != null && !outboundUrl.isBlank()) {
                                values.put("tesseraql.scim.outbound.target.url",
                                        outboundUrl.trim());
                                String token = io.tesseraql.studio.StudioService
                                        .secretReferenceOrNull("The SCIM outbound token",
                                                str(params, "tokenRef"));
                                if (token == null) {
                                    throw new io.tesseraql.core.error.TqlException(
                                            new io.tesseraql.core.error.TqlErrorCode(
                                                    io.tesseraql.core.error.TqlDomain.STUDIO,
                                                    4231),
                                            "An outbound SCIM target needs a token secret "
                                                    + "reference like ${secret.env.SCIM_TOKEN}");
                                }
                                values.put("tesseraql.scim.outbound.target.token", token);
                            }
                            studio.writeOverlaySection(values, "sso", actorOf(params));
                            return Map.of("applied", "scim");
                        })
                        .register("studio.wizard.identity.apply", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            String realmId = requiredParam(params, "realmId");
                            String prefix = "tesseraql.identity.realms." + realmId + ".";
                            Map<String, Object> values = new java.util.LinkedHashMap<>();
                            values.put("tesseraql.identity.defaultRealm", realmId);
                            values.put(prefix + "type", requiredParam(params, "type"));
                            values.put(prefix + "datasource",
                                    requiredParam(params, "datasource"));
                            putIfPresent(values, prefix + "sqlRoot", params, "sqlRoot");
                            putIfPresent(values, prefix + "capabilities.userManagement", params,
                                    "userManagement");
                            studio.writeOverlaySection(values, "sso", actorOf(params));
                            return Map.of("applied", "identity");
                        })
                        .register("studio.config", params -> {
                            java.util.List<Map<String, Object>> rows = studio.effectiveConfig();
                            long secrets = rows.stream()
                                    .filter(r -> Boolean.TRUE.equals(r.get("secret"))).count();
                            Map<String, Object> model = new java.util.LinkedHashMap<>();
                            model.put("rows", rows);
                            model.put("count", rows.size());
                            model.put("secretCount", secrets);
                            model.put("settings", studio.editableSettings());
                            model.put("editable", studioAccess.canEdit(params.get("roles")));
                            return model;
                        })
                        // Config editor (curated): override one whitelisted, restart-to-apply setting
                        // in config/overlay.yml (base untouched), or remove it when blank. Edit-gated +
                        // audited; only StudioService's whitelist of safe scalar keys is accepted.
                        .register("studio.configSet", params -> {
                            studioAccess.requireEdit(params.get("principalRoles"));
                            Object value = params.get("value");
                            studio.setConfigValue(str(params, "key"),
                                    value == null ? "" : String.valueOf(value), actorOf(params));
                            return Map.of("saved", true);
                        })
                        // Live feature flags editor: set/remove a flag in config/flags.yml. The request
                        // binder reads flags.<name> live, so a change takes effect on the next request
                        // (no restart). Edit-gated + audited.
                        .register("studio.flags", params -> {
                            Map<String, Object> flags = studio.flags();
                            java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
                            flags.forEach((name, value) -> {
                                Map<String, Object> row = new java.util.LinkedHashMap<>();
                                row.put("name", name);
                                row.put("value", value == null ? "" : String.valueOf(value));
                                row.put("type", value instanceof Boolean
                                        ? "boolean"
                                        : value instanceof Number ? "number" : "string");
                                // The on/off state of a boolean flag drives the one-click toggle.
                                row.put("on", value instanceof Boolean b && b);
                                rows.add(row);
                            });
                            Map<String, Object> model = new java.util.LinkedHashMap<>();
                            model.put("flags", rows);
                            model.put("hasFlags", !rows.isEmpty());
                            model.put("editable", studioAccess.canEdit(params.get("roles")));
                            return model;
                        })
                        .register("studio.flagsSet", params -> {
                            studioAccess.requireEdit(params.get("principalRoles"));
                            Object value = params.get("value");
                            studio.setFlag(str(params, "name"),
                                    value == null ? "" : String.valueOf(value),
                                    str(params, "type"), actorOf(params));
                            return Map.of("saved", true);
                        })
                        .register("studio.flagsRemove", params -> {
                            studioAccess.requireEdit(params.get("principalRoles"));
                            studio.removeFlag(str(params, "name"), actorOf(params));
                            return Map.of("removed", true);
                        })
                        // Data browser (read-only): paginated rows of a chosen table over the dev
                        // datasource, opt-in via tesseraql.studio.dataBrowser.enabled. The table is
                        // validated against the live catalog (no injection); a query error surfaces as
                        // a message rather than a 500.
                        .register("studio.data", params -> {
                            Map<String, Object> model = new java.util.LinkedHashMap<>();
                            model.put("enabled", studioData.isEnabled());
                            if (!studioData.isEnabled()) {
                                return model;
                            }
                            model.put("tables", studioData.tables());
                            model.put("exportMax", studioData.exportLimit());
                            String table = str(params, "table");
                            if (table == null) {
                                return model;
                            }
                            // Sort/filter state is echoed to the model so the form + prev/next/export
                            // links keep it; the service validates the columns against the real table.
                            String sortColumn = str(params, "sort");
                            String sortDir = "desc".equalsIgnoreCase(String.valueOf(
                                    params.get("dir"))) ? "desc" : "asc";
                            model.put("sortColumn", sortColumn == null ? "" : sortColumn);
                            model.put("sortDir", sortDir);
                            String combinator = "or".equalsIgnoreCase(String.valueOf(
                                    params.get("combinator"))) ? "or" : "and";
                            model.put("combinator", combinator);
                            // Up to DATA_FILTER_SLOTS conditions from indexed slots fcN/foN/fvN,
                            // combined by the combinator (AND/OR).
                            java.util.List<StudioDataService.FilterCond> filters = new java.util.ArrayList<>();
                            java.util.List<Map<String, Object>> filterRows = new java.util.ArrayList<>();
                            for (int i = 0; i < DATA_FILTER_SLOTS; i++) {
                                String column = str(params, "fc" + i);
                                String op = str(params, "fo" + i) == null
                                        ? "contains"
                                        : str(params, "fo" + i);
                                String value = params.get("fv" + i) == null
                                        ? ""
                                        : String.valueOf(params.get("fv" + i));
                                Map<String, Object> row = new java.util.LinkedHashMap<>();
                                row.put("column", column == null ? "" : column);
                                row.put("op", op);
                                row.put("value", value);
                                filterRows.add(row);
                                if (column != null) {
                                    filters.add(
                                            new StudioDataService.FilterCond(column, op, value));
                                }
                            }
                            model.put("filterRows", filterRows);
                            // Whether any filter/sort is active — drives the "Clear filters" control
                            // and keeps the filter disclosure open. The count labels the summary.
                            model.put("hasFilters", !filters.isEmpty() || sortColumn != null);
                            model.put("filterCount", filters.size());
                            model.put("queryBase", dataQueryBase(table, combinator, sortColumn,
                                    sortDir, filterRows));
                            try {
                                int page = parseIndex(params.get("page"));
                                StudioDataService.DataPage data = studioData.browse(table,
                                        page < 0 ? 0 : page, sortColumn, sortDir, combinator,
                                        filters);
                                model.put("table", data.table());
                                model.put("columns", data.columns());
                                java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
                                for (java.util.List<String> values : data.rows()) {
                                    java.util.List<Map<String, Object>> cells = new java.util.ArrayList<>();
                                    for (String value : values) {
                                        Map<String, Object> cell = new java.util.LinkedHashMap<>();
                                        cell.put("value", value == null ? "" : value);
                                        cell.put("isNull", value == null);
                                        cells.add(cell);
                                    }
                                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                                    row.put("cells", cells);
                                    rows.add(row);
                                }
                                model.put("rows", rows);
                                model.put("rowCount", rows.size());
                                model.put("page", data.page());
                                model.put("hasNext", data.hasNext());
                                model.put("hasPrev", data.page() > 0);
                                model.put("nextPage", data.page() + 1);
                                model.put("prevPage", data.page() - 1);
                            } catch (RuntimeException ex) {
                                model.put("error", ex.getMessage());
                            }
                            // Row editing (Track J4): the edit affordance appears only when
                            // the editor opt-in, the caller's edit permission, AND a primary
                            // key all line up; links carry the PK columns and the row's values.
                            boolean rowEditable = studioData.isEditEnabled()
                                    && studioAccess.canEdit(params.get("roles"));
                            model.put("editEnabled", rowEditable);
                            model.put("updated", params.get("updated"));
                            Object columns = model.get("columns");
                            Object rowMaps = model.get("rows");
                            if (rowEditable && columns instanceof java.util.List<?> cols
                                    && rowMaps instanceof java.util.List<?> shownRows) {
                                java.util.List<String> pk = studioData.primaryKey(
                                        String.valueOf(model.get("table")));
                                java.util.List<Integer> indexes = new java.util.ArrayList<>();
                                for (String column : pk) {
                                    int at = -1;
                                    for (int i = 0; i < cols.size(); i++) {
                                        if (String.valueOf(cols.get(i))
                                                .equalsIgnoreCase(column)) {
                                            at = i;
                                            break;
                                        }
                                    }
                                    indexes.add(at);
                                }
                                boolean complete = !pk.isEmpty() && !indexes.contains(-1);
                                java.util.List<String> editHrefs = new java.util.ArrayList<>();
                                for (Object rowObject : shownRows) {
                                    String href = null;
                                    if (complete && rowObject instanceof Map<?, ?> row
                                            && row.get(
                                                    "cells") instanceof java.util.List<?> cells) {
                                        StringBuilder link = new StringBuilder(
                                                "/_tesseraql/studio/ui/data/edit?table="
                                                        + urlEncode(String.valueOf(
                                                                model.get("table"))));
                                        boolean ok = true;
                                        for (int i = 0; i < pk.size(); i++) {
                                            Map<?, ?> cell = (Map<?, ?>) cells
                                                    .get(indexes.get(i));
                                            if (Boolean.TRUE.equals(cell.get("isNull"))) {
                                                ok = false;
                                                break;
                                            }
                                            link.append("&k").append(i).append('=')
                                                    .append(urlEncode(pk.get(i)));
                                            link.append("&v").append(i).append('=').append(
                                                    urlEncode(String.valueOf(
                                                            cell.get("value"))));
                                        }
                                        href = ok ? link.toString() : null;
                                    }
                                    editHrefs.add(href);
                                }
                                model.put("editHrefs", editHrefs);
                            }
                            return model;
                        })
                        // Data browser CSV export: the current view (table + filter + sort) as CSV,
                        // capped at the scan limit. Served as a file download by the export route.
                        // Row edit (Track J4): PK-scoped single-row form + UPDATE, under the
                        // row-editor opt-in + editRoles + an explicit confirm + the audit trail.
                        .register("studio.data.editForm", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            String table = String.valueOf(params.get("table"));
                            Map<String, Object> model = new java.util.LinkedHashMap<>();
                            model.put("table", table);
                            try {
                                StudioDataService.RowView row = studioData.row(table,
                                        dataRowKey(params));
                                model.put("pkColumns", row.pkColumns());
                                model.put("fields", row.fields());
                                model.put("keyPairs", dataRowKey(params).entrySet().stream()
                                        .map(e -> Map.of("column", e.getKey(),
                                                "value", e.getValue()))
                                        .toList());
                            } catch (RuntimeException ex) {
                                model.put("error", ex.getMessage());
                            }
                            return model;
                        })
                        .register("studio.data.update", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            requireExplicitConfirm(params, "Row edits");
                            String table = String.valueOf(params.get("table"));
                            Map<String, String> changes = new java.util.LinkedHashMap<>();
                            for (int i = 0; i < DATA_EDIT_SLOTS; i++) {
                                String column = str(params, "cn" + i);
                                if (column == null
                                        || params.get("cs" + i) == null) {
                                    continue;
                                }
                                Object value = params.get("cv" + i);
                                changes.put(column, value == null ? "" : String.valueOf(value));
                            }
                            try {
                                studioData.updateRow(table, dataRowKey(params), changes);
                            } catch (IllegalArgumentException | IllegalStateException ex) {
                                throw new io.tesseraql.core.error.TqlException(ROW_EDIT_REJECTED,
                                        ex.getMessage());
                            }
                            // Audit the row identity and the columns touched — never the values
                            // (the browser may hold sensitive business data).
                            studio.recordDataEdit(actorOf(params), table + " ["
                                    + String.join(", ", dataRowKey(params).entrySet().stream()
                                            .map(e -> e.getKey() + "=" + e.getValue()).toList())
                                    + "] set " + String.join(", ", changes.keySet()));
                            return Map.of("updated", table);
                        })
                        .register("studio.data.export", params -> {
                            if (!studioData.isEnabled()) {
                                return Map.of("csv", "# The data browser is disabled.\r\n");
                            }
                            try {
                                return Map.of("csv", studioData.exportCsv(str(params, "table"),
                                        str(params, "sort"),
                                        "desc".equalsIgnoreCase(String.valueOf(params.get("dir")))
                                                ? "desc"
                                                : "asc",
                                        "or".equalsIgnoreCase(String.valueOf(
                                                params.get("combinator"))) ? "or" : "and",
                                        dataFilters(params)));
                            } catch (RuntimeException ex) {
                                return Map.of("csv", "# " + ex.getMessage() + "\r\n");
                            }
                        })
                        // i18n message editor (governance/authoring): a key × locale table over the
                        // app's messages/<locale>.yml catalogs, flagging missing translations; the set
                        // action upserts one translation (edit-gated + audited). The message resolver
                        // and client catalog read messages/ live, so an edit is served immediately.
                        .register("studio.messages", params -> {
                            Map<String, Map<String, String>> catalogs = studio.messageCatalogs();
                            java.util.List<String> locales = new java.util.ArrayList<>(
                                    catalogs.keySet());
                            java.util.TreeSet<String> keys = new java.util.TreeSet<>();
                            catalogs.values().forEach(entries -> keys.addAll(entries.keySet()));
                            java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
                            int missing = 0;
                            for (String key : keys) {
                                java.util.List<Map<String, Object>> cells = new java.util.ArrayList<>();
                                for (String locale : locales) {
                                    String value = catalogs.get(locale).get(key);
                                    boolean isMissing = value == null || value.isBlank();
                                    if (isMissing) {
                                        missing++;
                                    }
                                    Map<String, Object> cell = new java.util.LinkedHashMap<>();
                                    cell.put("locale", locale);
                                    cell.put("value", value == null ? "" : value);
                                    cell.put("missing", isMissing);
                                    cells.add(cell);
                                }
                                Map<String, Object> row = new java.util.LinkedHashMap<>();
                                row.put("key", key);
                                row.put("cells", cells);
                                rows.add(row);
                            }
                            Map<String, Object> model = new java.util.LinkedHashMap<>();
                            model.put("locales", locales);
                            model.put("rows", rows);
                            model.put("keyCount", keys.size());
                            model.put("localeCount", locales.size());
                            model.put("missingCount", missing);
                            model.put("editable", studioAccess.canEdit(params.get("roles")));
                            return model;
                        })
                        .register("studio.messageSet", params -> {
                            studioAccess.requireEdit(params.get("principalRoles"));
                            Object value = params.get("value");
                            studio.setMessage(str(params, "locale"), str(params, "key"),
                                    value == null ? "" : String.valueOf(value), actorOf(params));
                            return Map.of("saved", true);
                        })
                        // New migration page (Studio backlog: migration authoring): the form shows the
                        // next versioned number for the main datasource; the create writes a Flyway
                        // migration under db/…/migration and the result links to the source editor.
                        .register("studio.migration.new", params -> {
                            boolean canEdit = studioAccess.canEdit(params.get("roles"));
                            Map<String, Object> model = new java.util.LinkedHashMap<>();
                            model.put("editable", canEdit);
                            model.put("readOnly", !canEdit);
                            model.put("nextVersion", studio.nextMigrationVersion("main", null));
                            // The DDL builder's table dropdown is populated from the schema overlay.
                            io.tesseraql.studio.DocService migrationDoc = new io.tesseraql.studio.DocService(
                                    manifest);
                            java.util.List<String> tables = migrationDoc.tableNames();
                            model.put("tables", tables);
                            model.put("hasTables", !tables.isEmpty());
                            // A schema baseline enables generating a migration from the schema diff.
                            model.put("hasSchemaBaseline", migrationDoc.hasSchemaBaseline());
                            return model;
                        })
                        // Cascade for the DDL builder: a chosen table's columns, for the index
                        // builder's column autocomplete (rendered as datalist options).
                        .register("studio.migration.columns",
                                params -> Map.of("columns", new io.tesseraql.studio.DocService(
                                        manifest).columnNames(
                                                params.get("table") == null
                                                        ? null
                                                        : String.valueOf(params.get("table")))))
                        // Generate a migration from the schema diff (baseline schema.json vs current):
                        // captures direct database changes back into a migration. Pure generation.
                        .register("studio.migration.diff", params -> {
                            io.tesseraql.studio.DocService diffDoc = new io.tesseraql.studio.DocService(
                                    manifest);
                            String ddl;
                            if (!diffDoc.hasSchemaBaseline()) {
                                ddl = "-- No schema baseline. Copy .tesseraql/docs/schema.json to "
                                        + "schema.baseline.json, then regenerate to see changes since.";
                            } else {
                                String diff = diffDoc.schemaDiffDdl();
                                ddl = diff == null || diff.isBlank()
                                        ? "-- No schema changes since the baseline."
                                        : diff;
                            }
                            return Map.of("ddl", ddl);
                        })
                        // The 2-way SQL builder (migration authoring follow-on): generate a route's
                        // select/insert/update/delete 2-way SQL for a chosen table + operation, from
                        // the schema overlay. Pure generation — no side effect.
                        .register("studio.sqlBuilder.new", params -> {
                            boolean canEdit = studioAccess.canEdit(params.get("roles"));
                            java.util.List<String> tables = new io.tesseraql.studio.DocService(
                                    manifest).tableNames();
                            Map<String, Object> model = new java.util.LinkedHashMap<>();
                            model.put("editable", canEdit);
                            model.put("readOnly", !canEdit);
                            model.put("tables", tables);
                            model.put("hasTables", !tables.isEmpty());
                            return model;
                        })
                        // Cascade: a chosen table's columns for the by-column filter dropdown.
                        .register("studio.sqlBuilder.columns",
                                params -> Map.of("columns", new io.tesseraql.studio.DocService(
                                        manifest).columnNames(
                                                params.get("table") == null
                                                        ? null
                                                        : String.valueOf(params.get("table")))))
                        .register("studio.sqlBuilder.build", params -> {
                            String tableName = params.get("table") == null
                                    ? null
                                    : String.valueOf(params.get("table"));
                            io.tesseraql.yaml.scaffold.CatalogSchema.Table table = new io.tesseraql.studio.DocService(
                                    manifest).tableByName(tableName);
                            String sql;
                            if (table == null) {
                                sql = "-- No such table in the schema overlay: " + tableName;
                            } else {
                                String generated = io.tesseraql.studio.SqlBuilder.generate(table,
                                        String.valueOf(params.get("operation")),
                                        params.get("column") == null
                                                ? null
                                                : String.valueOf(params.get("column")));
                                sql = generated.isEmpty() ? "-- Unknown operation." : generated;
                            }
                            return Map.of("sql", sql);
                        })
                        // Validation rule builder: generate a route's validate: YAML for one rule from
                        // a chosen operation (required/min/max/range/equals/one-of/expression/sql).
                        // Pure text generation to copy into the route — no side effect.
                        .register("studio.validationBuilder",
                                params -> Map.of("editable",
                                        studioAccess.canEdit(params.get("roles"))))
                        .register("studio.validationBuilder.build", params -> Map.of("snippet",
                                io.tesseraql.studio.ValidationRuleBuilder.generate(
                                        str(params, "operation"), str(params, "source"),
                                        str(params, "field"), str(params, "value"),
                                        str(params, "value2"), str(params, "id"),
                                        str(params, "code"), str(params, "message"),
                                        str(params, "when"))))
                        .register("studio.migration.create", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            String datasource = params.get("datasource") == null
                                    ? "main"
                                    : String.valueOf(params.get("datasource"));
                            String vendor = params.get("vendor") == null
                                    ? null
                                    : String.valueOf(params.get("vendor"));
                            boolean repeatable = "repeatable"
                                    .equals(String.valueOf(params.get("kind")));
                            String description = params.get("description") == null
                                    ? null
                                    : String.valueOf(params.get("description"));
                            String ddl = params.get("ddl") == null
                                    ? null
                                    : String.valueOf(params.get("ddl"));
                            io.tesseraql.studio.StudioService.MigrationResult result = studio
                                    .createMigration(datasource, vendor, repeatable, description,
                                            ddl, false, actorOf(params));
                            Map<String, Object> model = new java.util.LinkedHashMap<>();
                            model.put("created", true);
                            model.put("path", result.path());
                            model.put("filename", result.filename());
                            model.put("version", result.version());
                            model.put("repeatable", result.repeatable());
                            model.put("editorUrl", "/_tesseraql/studio/ui/source?path="
                                    + java.net.URLEncoder.encode(result.path(),
                                            java.nio.charset.StandardCharsets.UTF_8));
                            return model;
                        })
                        // Migrate now (roadmap Phase 42): applies the app's pending migrations to
                        // the dev datasource on demand, closing the schema -> scaffold -> serve
                        // loop without a process bounce. Same Flyway path as startup (main set +
                        // tenant pools + named per-datasource sets); edit-gated, confirm-gated
                        // like apply, and recorded to the audit trail.
                        .register("studio.migration.migrate", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            studioAccess.requireConfirm(
                                    "true".equals(String.valueOf(params.get("confirm"))));
                            int applied = AppMigrations.migrate(appName, appHome,
                                    manifest.config(), dataSource, tenantDataSources,
                                    dataSources::get);
                            studio.recordMigrationRun(actorOf(params), "db/migration");
                            Map<String, Object> model = new java.util.LinkedHashMap<>();
                            model.put("applied", applied);
                            return model;
                        })
                        // Dry-run a migration's DDL against the sandbox (auto-rollback) before it
                        // lands — gated like the test runner (opt-in enabled); Postgres only.
                        .register("studio.migration.dryRun", params -> {
                            String path = String.valueOf(params.get("path"));
                            Object content = params.get("content");
                            io.tesseraql.studio.StudioService.DryRunResult result;
                            if (!studioTests.isEnabled()) {
                                result = io.tesseraql.studio.StudioService.DryRunResult.declined(
                                        "The Studio test runner is disabled (set "
                                                + "tesseraql.studio.testRunner.enabled).");
                            } else {
                                result = studio.dryRunMigration(path,
                                        content == null ? null : String.valueOf(content),
                                        studioTests::dryRunDdl);
                            }
                            Map<String, Object> model = new java.util.LinkedHashMap<>();
                            model.put("ran", result.ran());
                            model.put("ok", result.ok());
                            model.put("message", result.message());
                            return model;
                        })
                        // Form-driven DDL builder (migration authoring): generate standard DDL for a
                        // common operation, dropped into the New migration page's DDL field. Pure
                        // generation — no side effect — so no edit gate beyond the endpoint's auth.
                        .register("studio.migration.build", params -> {
                            java.util.function.Function<String, String> field = key -> params
                                    .get(key) == null ? null : String.valueOf(params.get(key));
                            String operation = String.valueOf(params.get("operation"));
                            String ddl = switch (operation) {
                                case "add-column" -> io.tesseraql.studio.MigrationDdl.addColumn(
                                        field.apply("table"), field.apply("column"),
                                        field.apply("type"),
                                        !"true".equals(field.apply("notNull")),
                                        field.apply("default"));
                                case "create-index" -> io.tesseraql.studio.MigrationDdl.createIndex(
                                        field.apply("table"), field.apply("columns"),
                                        "true".equals(field.apply("unique")), field.apply("name"));
                                case "create-table" -> io.tesseraql.studio.MigrationDdl.createTable(
                                        field.apply("table"), field.apply("columnLines"),
                                        field.apply("primaryKey"));
                                default -> throw new io.tesseraql.core.error.TqlException(
                                        new io.tesseraql.core.error.TqlErrorCode(
                                                io.tesseraql.core.error.TqlDomain.STUDIO, 4224),
                                        "Unknown DDL operation: " + operation);
                            };
                            return Map.of("ddl", ddl);
                        })
                        .register("studio.apply", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            String path = String.valueOf(params.get("path"));
                            // force=true overwrites a concurrently changed source (backlog D5).
                            boolean force = "true".equals(String.valueOf(params.get("force")));
                            boolean confirm = "true".equals(String.valueOf(params.get("confirm")));
                            // When confirm-before-apply is on, require an explicit acknowledgment
                            // (the conflict force checkbox counts as one).
                            studioAccess.requireConfirm(confirm || force);
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
                            Object result = io.tesseraql.studio.StudioViews.scaffoldResult(
                                    studioScaffold.apply(String.valueOf(params.get("table")),
                                            "true".equals(String.valueOf(params.get("force"))),
                                            actorOf(params)));
                            // The instant loop (roadmap Phase 42): the scaffolded routes mount
                            // right away — no restart between apply and serving.
                            reloader.reload();
                            return result;
                        })
                        .register("studio.discard", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            String path = String.valueOf(params.get("path"));
                            studio.deleteDraft(path);
                            return Map.of("discarded", path);
                        })
                        // Studio Drafts bulk actions: apply every clean draft (conflicts skipped) or
                        // discard them all — the batch management the Explorer drafts-in-tree can't do.
                        // Edit-gated like studio.apply/scaffold.apply; apply reloads routes after.
                        .register("studio.draftsApplyAll", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            io.tesseraql.studio.StudioService.BulkApplyResult result = studio
                                    .applyAllDrafts(actorOf(params));
                            reloader.reload();
                            return Map.of("applied", result.applied(), "skipped", result.skipped(),
                                    "needsRestart", result.needsRestart());
                        })
                        .register("studio.draftsDiscardAll", params -> {
                            studioAccess.requireEdit(params.get("roles"));
                            return Map.of("discarded", studio.discardAllDrafts());
                        })
                        .register("studio.drafts", params -> {
                            String q = params.get("q") == null
                                    ? null
                                    : String.valueOf(params.get("q"));
                            Map<String, Object> model = io.tesseraql.studio.StudioViews
                                    .drafts(studio.drafts(), q);
                            // The bulk apply/discard actions follow the caller's edit permission.
                            model.put("editable", studioAccess.canEdit(params.get("roles")));
                            return model;
                        })
                        .register("studio.audit", params -> {
                            // Filter the whole log, then sort + page it (H5 filter + I3 paging + I2 sort).
                            String q = params.get("q") == null
                                    ? null
                                    : String.valueOf(params.get("q"));
                            String sort = params.get("sort") == null
                                    ? null
                                    : String.valueOf(params.get("sort"));
                            String dir = params.get("dir") == null
                                    ? null
                                    : String.valueOf(params.get("dir"));
                            return io.tesseraql.studio.StudioViews.audit(
                                    studio.auditPage(q, sort, dir, parsePage(params.get("page")),
                                            io.tesseraql.studio.StudioViews.AUDIT_PAGE_SIZE),
                                    q, sort, dir);
                        });
                // Providers backing the bundled documentation portal (documentation portal v1/v2/v3):
                // they read the packaged spec.json, falling back to a live model from the manifest,
                // and overlay the optional run report.json (test results + coverage) and schema.json
                // (introspected table definitions) when present.
                io.tesseraql.studio.DocService doc = new io.tesseraql.studio.DocService(manifest);
                // Opt-in signed share links (F8, slice 3): off unless a signing secret is set.
                ShareLinks shareLinks = ShareLinks.from(manifest.config());
                serviceProviders
                        .register("docs.index", params -> io.tesseraql.studio.DocViews.index(
                                doc.appName(), doc.spec(), doc.report(),
                                params.get("sort") == null
                                        ? null
                                        : String.valueOf(params.get("sort")),
                                params.get("dir") == null
                                        ? null
                                        : String.valueOf(params.get("dir"))))
                        .register("docs.route", params -> {
                            String id = String.valueOf(params.get("id"));
                            io.tesseraql.studio.DocService.RouteEntry entry = doc.route(id);
                            if (entry == null) {
                                return Map.of("notFound", true, "id", id);
                            }
                            io.tesseraql.studio.ReportOverlay overlay = doc.report();
                            Map<String, Object> model = io.tesseraql.studio.DocViews.route(entry,
                                    overlay == null ? null : overlay.routeReport(id),
                                    doc.tableLinks());
                            // Offer a signed, expiring share link when sharing is configured.
                            if (shareLinks.enabled()) {
                                model.put("shareUrl", shareLinks.mintRoute(id));
                            }
                            return model;
                        })
                        .register("docs.search", params -> {
                            Object query = params.get("q");
                            String q = query == null ? "" : String.valueOf(query);
                            return io.tesseraql.studio.DocViews.searchResults(q, doc.search(q));
                        })
                        .register("docs.coverage", params -> {
                            io.tesseraql.studio.ReportOverlay overlay = doc.report();
                            Map<String, Object> model = io.tesseraql.studio.DocViews
                                    .coverage(doc.appName(), overlay, doc.history());
                            // Offer a signed share link for the dashboard when sharing is configured
                            // and there is a run report to share (F8 slice 3, extended).
                            if (shareLinks.enabled() && overlay != null) {
                                model.put("shareUrl", shareLinks.mintCoverage());
                            }
                            return model;
                        })
                        .register("docs.schema", params -> io.tesseraql.studio.DocViews.schema(
                                doc.appName(), doc.schema(),
                                params.get("sort") == null
                                        ? null
                                        : String.valueOf(params.get("sort")),
                                params.get("dir") == null
                                        ? null
                                        : String.valueOf(params.get("dir"))))
                        .register("docs.table", params -> {
                            String ds = String.valueOf(params.get("ds"));
                            String name = String.valueOf(params.get("name"));
                            io.tesseraql.yaml.scaffold.CatalogSchema.Table table = doc.table(ds,
                                    name);
                            if (table == null) {
                                return Map.of("notFound", true, "name", name, "datasource", ds);
                            }
                            Map<String, Object> model = io.tesseraql.studio.DocViews.table(ds,
                                    table, doc.routesForTable(name));
                            if (shareLinks.enabled()) {
                                model.put("shareUrl", shareLinks.mintTable(ds, name));
                            }
                            return model;
                        })
                        // Export/share (documentation portal F8): the OpenAPI document and the htmx
                        // contract, generated live from the manifest by the canonical generators and
                        // streamed as downloadable JSON (the download routes' response.file emits the
                        // provider's raw JSON string verbatim).
                        .register("docs.export", params -> io.tesseraql.studio.DocViews
                                .export(doc.appName(), doc.apiChangelog()))
                        // The release-diff page (roadmap Phase 46): one view consolidating
                        // what a promotion changes from the captured baselines — the API
                        // changelog (openapi.baseline.json), the schema DDL delta
                        // (schema.baseline.json), and the app's full migration list. The
                        // two-tree diff (routes/policies) is the CLI/Maven report; this page
                        // shows what the running app's baselines can prove.
                        .register("docs.releaseDiff", params -> {
                            Map<String, Object> model = new java.util.LinkedHashMap<>();
                            model.put("appName", doc.appName());
                            model.put("hasApiBaseline", doc.hasApiBaseline());
                            io.tesseraql.studio.DocViews.applyApiChangelog(model,
                                    doc.apiChangelog());
                            model.put("hasSchemaBaseline", doc.hasSchemaBaseline());
                            String ddl = doc.hasSchemaBaseline() ? doc.schemaDiffDdl() : null;
                            model.put("schemaDiff",
                                    ddl == null || ddl.isBlank() ? null : ddl);
                            java.util.List<Map<String, Object>> migrations = new java.util.ArrayList<>();
                            for (io.tesseraql.yaml.manifest.MigrationFile migration : manifest
                                    .migrations()) {
                                Map<String, Object> row = new java.util.LinkedHashMap<>();
                                row.put("datasource", migration.datasource());
                                row.put("vendor", migration.vendor());
                                row.put("file", String.valueOf(
                                        migration.path().getFileName()));
                                migrations.add(row);
                            }
                            model.put("migrations", migrations);
                            return model;
                        })
                        .register("docs.openapi", params -> doc.openApiJson())
                        .register("docs.htmx", params -> doc.htmxContractJson())
                        // Printable route catalog (F8, slice 2): render the route rows to a PDF
                        // table through the canonical PDF codec, shown as a data: URL (degrades to a
                        // note when the optional tesseraql-pdf module is absent, like the editor).
                        .register("docs.routesPdf", params -> {
                            byte[] pdf = renderRoutesPdf(doc.routeCatalog(), appHome);
                            return io.tesseraql.studio.DocViews.routesPdf(doc.appName(),
                                    pdf == null
                                            ? null
                                            : "data:application/pdf;base64,"
                                                    + java.util.Base64.getEncoder()
                                                            .encodeToString(pdf));
                        })
                        // Public share view (F8, slice 3): the unauthenticated route this provider
                        // backs verifies the signed, expiring token before rendering a reduced
                        // read-only contract (no SQL / tests / coverage). An invalid, tampered, or
                        // expired token — or sharing disabled — renders the invalid-link notice, so
                        // nothing leaks. The route overlay is deliberately not consulted here.
                        .register("docs.share", params -> {
                            String id = params.get("id") == null
                                    ? null
                                    : String.valueOf(params.get("id"));
                            String exp = params.get("exp") == null
                                    ? null
                                    : String.valueOf(params.get("exp"));
                            String sig = params.get("sig") == null
                                    ? null
                                    : String.valueOf(params.get("sig"));
                            io.tesseraql.studio.DocService.RouteEntry entry = shareLinks
                                    .verifyRoute(id, exp, sig) ? doc.route(id) : null;
                            if (entry == null) {
                                return Map.of("shared", true, "shareInvalid", true);
                            }
                            return io.tesseraql.studio.DocViews.share(entry);
                        })
                        // Public shared schema table (F8 slice 3, extended): verify the signed token,
                        // then render the table's read-only reference; invalid/expired -> notice.
                        .register("docs.shareTable", params -> {
                            String ds = params.get("ds") == null
                                    ? null
                                    : String.valueOf(params.get("ds"));
                            String name = params.get("name") == null
                                    ? null
                                    : String.valueOf(params.get("name"));
                            String exp = params.get("exp") == null
                                    ? null
                                    : String.valueOf(params.get("exp"));
                            String sig = params.get("sig") == null
                                    ? null
                                    : String.valueOf(params.get("sig"));
                            io.tesseraql.yaml.scaffold.CatalogSchema.Table table = shareLinks
                                    .verifyTable(ds, name, exp, sig) ? doc.table(ds, name) : null;
                            if (table == null) {
                                return Map.of("shared", true, "shareInvalid", true);
                            }
                            return io.tesseraql.studio.DocViews.shareTable(ds, table);
                        })
                        // Public shared coverage dashboard (F8 slice 3, extended): same verification;
                        // the public view withholds the per-test failure detail.
                        .register("docs.shareCoverage", params -> {
                            String exp = params.get("exp") == null
                                    ? null
                                    : String.valueOf(params.get("exp"));
                            String sig = params.get("sig") == null
                                    ? null
                                    : String.valueOf(params.get("sig"));
                            if (!shareLinks.verifyCoverage(exp, sig)) {
                                return Map.of("shared", true, "shareInvalid", true);
                            }
                            return io.tesseraql.studio.DocViews.shareCoverage(doc.appName(),
                                    doc.report(), doc.history());
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
            sseEndpoints.forEach(Runnable::run);
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

    /** A 1-based page number from a request param (Integer or String), defaulting to 1 (I3). */
    private static int parsePage(Object raw) {
        if (raw == null) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(raw).trim()));
        } catch (NumberFormatException ignored) {
            return 1;
        }
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

    /** The self-hosted sprite icon ids offered in the menu editor's icon picker (see icons.svg). */
    private static final List<String> MENU_ICON_OPTIONS = List.of(
            "compass", "book-open", "database", "shield-check", "share-2", "blocks", "wrench",
            "database-zap", "wand-sparkles", "file-pen", "scroll-text", "activity", "users",
            "layout-dashboard", "waypoints", "arrow-left-right", "send", "panel-left");

    /**
     * Rebuilds the {@link PolicyEngine} from the app's current (re-read) config and rebinds it, so a
     * Studio policy edit written to {@code config/overlay.yml} is authorized live on the next request
     * without a restart — the auth producer looks the engine up by name per request, so the rebind
     * takes effect immediately. Only the policy engine is rebound; the authenticators are unchanged.
     */
    private static void rebindPolicyEngine(CamelContext context, Path appHome) {
        SecurityConfig fresh = SecurityConfigFactory
                .build(new ManifestLoader().load(appHome).config());
        context.getRegistry().bind(TesseraqlProperties.POLICY_ENGINE_BEAN, new PolicyEngine(fresh));
    }

    /**
     * The API try-it console's loopback invocation: sends the requested method/path/body to the
     * app's own server on {@code 127.0.0.1:<port>} and returns a view model of the raw response.
     * The path must be app-relative (leading {@code /}, no {@code //} or {@code scheme://}), so the
     * call can only reach this app — never an arbitrary host.
     */
    private static Map<String, Object> tryInvoke(int port, Map<String, Object> params) {
        Map<String, Object> model = new java.util.LinkedHashMap<>();
        String method = params.get("method") == null
                ? "GET"
                : String.valueOf(params.get("method")).strip().toUpperCase(java.util.Locale.ROOT);
        if (method.isEmpty()) {
            method = "GET";
        }
        String path = str(params, "path");
        model.put("method", method);
        model.put("path", path);
        if (path == null || !path.startsWith("/") || path.startsWith("//")
                || path.contains("://")) {
            model.put("error", "Enter an app path beginning with '/' (for example /api/users).");
            return model;
        }
        if (port <= 0) {
            model.put("error", "The API console needs a fixed server.port (this app binds an "
                    + "ephemeral port).");
            return model;
        }
        String query = str(params, "query");
        String url = "http://127.0.0.1:" + port + path;
        if (query != null) {
            url += (path.contains("?") ? "&" : "?") + (query.startsWith("?")
                    ? query.substring(1)
                    : query);
        }
        String body = params.get("body") == null ? null : String.valueOf(params.get("body"));
        boolean hasBody = body != null && !body.isBlank()
                && !("GET".equals(method) || "HEAD".equals(method) || "DELETE".equals(method));
        String bearer = str(params, "bearer");
        String contentType = str(params, "contentType");
        // "Send my session": forward the caller's own session cookie (and its CSRF token) so the
        // loopback runs as the current Studio user — this is how browser-authenticated routes are
        // exercised. The cookie/csrf are bound from the caller's own request, used server-side only,
        // and never rendered. No escalation: the target route still enforces its own policy.
        boolean useSession = "true".equals(String.valueOf(params.get("useSession")));
        String cookie = str(params, "cookie");
        String sessionCsrf = str(params, "csrf");
        model.put("url", url);
        try {
            java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .method(method, hasBody
                            ? java.net.http.HttpRequest.BodyPublishers.ofString(body)
                            : java.net.http.HttpRequest.BodyPublishers.noBody());
            if (hasBody) {
                request.header("Content-Type",
                        contentType == null ? "application/json" : contentType);
            }
            if (bearer != null) {
                request.header("Authorization", "Bearer " + bearer);
            }
            if (useSession && cookie != null) {
                request.header("Cookie", cookie);
                if (sessionCsrf != null) {
                    request.header("X-CSRF-Token", sessionCsrf);
                }
            }
            long startedNs = System.nanoTime();
            java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                    .send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            model.put("ok", true);
            model.put("status", response.statusCode());
            model.put("durationMs", (System.nanoTime() - startedNs) / 1_000_000);
            java.util.List<Map<String, Object>> headers = new java.util.ArrayList<>();
            response.headers().map().forEach((name, values) -> {
                Map<String, Object> header = new java.util.LinkedHashMap<>();
                header.put("name", name);
                header.put("value", String.join(", ", values));
                headers.add(header);
            });
            model.put("headers", headers);
            model.put("body", prettyBody(response.body()));
        } catch (java.io.IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            model.put("error", "Request failed: " + ex.getMessage());
        }
        return model;
    }

    /** Pretty-prints a JSON response body for the try-it console; returns it unchanged otherwise. */
    private static String prettyBody(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        String trimmed = body.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                return mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(mapper.readTree(body));
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // Not valid JSON after all — show the raw body.
            }
        }
        return body;
    }

    /** The number of filter condition slots the data browser exposes. */
    private static final int DATA_FILTER_SLOTS = 5;
    private static final int ROUTE_FORM_INPUT_SLOTS = 10;
    private static final int DATA_EDIT_SLOTS = 20;

    /** Assembles the data browser's filter conditions from the indexed slot params {@code fcN/foN/fvN}. */
    private static java.util.List<StudioDataService.FilterCond> dataFilters(
            Map<String, Object> params) {
        java.util.List<StudioDataService.FilterCond> filters = new java.util.ArrayList<>();
        for (int i = 0; i < DATA_FILTER_SLOTS; i++) {
            String column = str(params, "fc" + i);
            if (column == null) {
                continue;
            }
            String op = str(params, "fo" + i) == null ? "contains" : str(params, "fo" + i);
            String value = params.get("fv" + i) == null ? "" : String.valueOf(params.get("fv" + i));
            filters.add(new StudioDataService.FilterCond(column, op, value));
        }
        return filters;
    }

    /** The URL-encoded query string (table + combinator + filter slots + sort) reused by the links. */
    private static String dataQueryBase(String table, String combinator, String sortColumn,
            String sortDir, java.util.List<Map<String, Object>> filterRows) {
        StringBuilder query = new StringBuilder("table=").append(urlEncode(table))
                .append("&combinator=").append(urlEncode(combinator));
        for (int i = 0; i < filterRows.size(); i++) {
            Map<String, Object> row = filterRows.get(i);
            query.append("&fc").append(i).append('=')
                    .append(urlEncode(String.valueOf(row.get("column"))))
                    .append("&fo").append(i).append('=')
                    .append(urlEncode(String.valueOf(row.get("op"))))
                    .append("&fv").append(i).append('=')
                    .append(urlEncode(String.valueOf(row.get("value"))));
        }
        return query.append("&sort=").append(urlEncode(sortColumn == null ? "" : sortColumn))
                .append("&dir=").append(sortDir).toString();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Rejects a Track J2 mutation posted without the explicit confirm acknowledgment. */
    private static void requireExplicitConfirm(Map<String, Object> params, String what) {
        if (!"true".equals(String.valueOf(params.get("confirm")))) {
            throw new io.tesseraql.core.error.TqlException(
                    new io.tesseraql.core.error.TqlErrorCode(
                            io.tesseraql.core.error.TqlDomain.STUDIO, 4232),
                    what + " need explicit confirmation");
        }
    }

    /**
     * The configured copilot endpoint, gated by the same deny-by-default egress allow-list an
     * {@code http-call} step obeys (docs/copilot.md): every turn ships app source to this
     * endpoint, so a host outside {@code tesseraql.http.outbound.allowedHosts} fails the boot
     * with {@code TQL-SEC-4085} — a chat must never become the one outbound call the egress
     * policy does not govern.
     */
    private static String copilotEndpoint(io.tesseraql.yaml.config.AppConfig config,
            io.tesseraql.yaml.http.HttpOutbound outbound) {
        String endpoint = config.requireString("tesseraql.copilot.endpoint");
        String host;
        try {
            host = java.net.URI.create(endpoint).getHost();
        } catch (IllegalArgumentException ex) {
            host = null;
        }
        if (host == null) {
            throw new io.tesseraql.core.error.TqlException(
                    new io.tesseraql.core.error.TqlErrorCode(
                            io.tesseraql.core.error.TqlDomain.SEC, 4085),
                    "tesseraql.copilot.endpoint '" + endpoint
                            + "' must be an absolute http or https URL");
        }
        if (!outbound.isHostAllowed(host)) {
            throw new io.tesseraql.core.error.TqlException(
                    new io.tesseraql.core.error.TqlErrorCode(
                            io.tesseraql.core.error.TqlDomain.SEC, 4085),
                    "Copilot endpoint host '" + host
                            + "' is not in tesseraql.http.outbound.allowedHosts (egress is"
                            + " deny by default); allow it:\n"
                            + "tesseraql:\n"
                            + "  http:\n"
                            + "    outbound:\n"
                            + "      allowedHosts:\n"
                            + "        - " + host);
        }
        return endpoint;
    }

    /** Rejects a copilot call when the operator has not configured the panel. */
    private static void requireCopilot(io.tesseraql.studio.CopilotService copilot) {
        if (copilot == null) {
            throw new io.tesseraql.core.error.TqlException(
                    new io.tesseraql.core.error.TqlErrorCode(
                            io.tesseraql.core.error.TqlDomain.STUDIO, 4235),
                    "The copilot is not configured (tesseraql.copilot.enabled/endpoint/"
                            + "model)");
        }
    }

    private static String requiredParam(Map<String, Object> params, String key) {
        String value = str(params, key);
        if (value == null || value.isBlank()) {
            throw new io.tesseraql.core.error.TqlException(
                    new io.tesseraql.core.error.TqlErrorCode(
                            io.tesseraql.core.error.TqlDomain.STUDIO, 4231),
                    "Missing required field: " + key);
        }
        return value.trim();
    }

    private static void putIfPresent(Map<String, Object> values, String dottedKey,
            Map<String, Object> params, String key) {
        String value = str(params, key);
        if (value != null && !value.isBlank()) {
            values.put(dottedKey, value.trim());
        }
    }

    /**
     * Live validity of every configured datasource (roadmap Phase 45): a short
     * {@link java.sql.Connection#isValid} round-trip per pool, by datasource name.
     */
    private static Map<String, Boolean> probeDatasources(javax.sql.DataSource main,
            Map<String, ? extends javax.sql.DataSource> named) {
        Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        out.put("main", datasourceValid(main));
        named.forEach((name, ds) -> {
            if (!"main".equals(name)) {
                out.put(name, datasourceValid(ds));
            }
        });
        return out;
    }

    private static boolean datasourceValid(javax.sql.DataSource dataSource) {
        try (java.sql.Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception ex) {
            return false;
        }
    }

    private static Map<String, String> parseQueryString(String query) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return out;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(java.net.URLDecoder.decode(pair.substring(0, eq),
                    java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(eq + 1),
                            java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonObject(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body,
                    Map.class);
            return parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
        } catch (java.io.IOException ex) {
            return Map.of();
        }
    }

    /** The k0/v0..k2/v2 primary-key slots of a data-browser row-edit request (Track J4). */
    private static Map<String, String> dataRowKey(Map<String, Object> params) {
        Map<String, String> key = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            String column = str(params, "k" + i);
            String value = str(params, "v" + i);
            if (column != null && value != null) {
                key.put(column, value);
            }
        }
        return key;
    }

    /** A request parameter as a trimmed string, or null when absent or blank. */
    private static String str(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        String trimmed = String.valueOf(value).strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Parses a menu-item index parameter, yielding -1 (a no-op index) when malformed. */
    private static int parseIndex(Object value) {
        try {
            return Integer.parseInt(String.valueOf(value).strip());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** A human-readable visibility summary for a menu item's editor row. */
    private static String menuVisibility(io.tesseraql.yaml.menu.MenuSpec.MenuItem item) {
        if (item.roles().isEmpty() && item.permissions().isEmpty()) {
            return "Public";
        }
        StringBuilder summary = new StringBuilder();
        if (!item.roles().isEmpty()) {
            summary.append("Roles: ").append(String.join(", ", item.roles()));
        }
        if (!item.permissions().isEmpty()) {
            if (summary.length() > 0) {
                summary.append("; ");
            }
            summary.append("Permissions: ").append(String.join(", ", item.permissions()));
        }
        return summary.toString();
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

    /**
     * Renders the documentation portal's route catalog (one row per route) to a PDF table through
     * the canonical PDF codec's built-in grid (no template), reusing the same {@code FileCodecs}
     * discovery the export routes use. Returns {@code null} when the optional {@code tesseraql-pdf}
     * module is absent so the portal degrades to a clear note rather than failing (F8, slice 2).
     */
    private static byte[] renderRoutesPdf(List<Map<String, Object>> rows, Path appHome) {
        io.tesseraql.core.files.FileCodec codec;
        try {
            codec = io.tesseraql.core.files.FileCodecs.discover().require("pdf");
        } catch (io.tesseraql.core.error.TqlException ex) {
            return null;
        }
        List<io.tesseraql.core.files.ColumnMapping> columns = List.of(
                new io.tesseraql.core.files.ColumnMapping("id", "Id", null),
                new io.tesseraql.core.files.ColumnMapping("method", "Method", null),
                new io.tesseraql.core.files.ColumnMapping("path", "Path", null),
                new io.tesseraql.core.files.ColumnMapping("recipe", "Recipe", null),
                new io.tesseraql.core.files.ColumnMapping("tests", "Tests", null));
        io.tesseraql.core.files.FileWriteSpec spec = new io.tesseraql.core.files.FileWriteSpec(
                columns, null, null, null, appHome, null, null);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            codec.write(out, spec, rows.iterator());
        } catch (Exception ex) {
            throw new IllegalStateException("Routes PDF render failed: " + ex.getMessage(), ex);
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

    /**
     * Starts the {@code serve --watch} file watcher: saves under the app's {@code web/} tree
     * hot-reload through the same content-diff reloader Studio's apply uses, reporting one
     * concise line per reload to {@code out}. A failed reload never kills the watcher or the
     * server — the broken route serves its compile error as a 500 stub until the file is
     * fixed. The watcher runs on a daemon thread and stops with {@link #close()}.
     */
    public AutoCloseable watchRoutes(java.util.function.Consumer<String> out) {
        RouteWatcher watcher = camelContext.getRegistry()
                .lookupByNameAndType(RouteWatcher.BEAN, RouteWatcher.class);
        watcher.start(out);
        return watcher;
    }

    public int port() {
        return port;
    }

    public CamelContext camelContext() {
        return camelContext;
    }

    @Override
    public void close() {
        // Stop the --watch file watcher first so no reload races the context shutdown.
        closeQuietly(camelContext.getRegistry()
                .lookupByNameAndType(RouteWatcher.BEAN, RouteWatcher.class));
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

    /** Builds and migrates the JDBC preference store, cached for the request path (Phase 48). */
    private static io.tesseraql.core.account.PreferenceStore accountPreferenceStore(
            javax.sql.DataSource dataSource) {
        io.tesseraql.operations.account.JdbcPreferenceStore store = new io.tesseraql.operations.account.JdbcPreferenceStore(
                dataSource);
        store.ensureSchema();
        return new io.tesseraql.core.account.CachingPreferenceStore(store);
    }
}
