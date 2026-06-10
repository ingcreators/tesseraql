package io.tesseraql.runtime;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.RouteCompiler;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.PasswordAuthenticator;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.security.SecurityConfig;
import io.tesseraql.security.jwt.JwtAuthenticator;
import io.tesseraql.security.policy.PolicyEngine;
import io.tesseraql.security.session.SessionStore;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobExecutor;
import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.operations.idempotency.JdbcIdempotencyStore;
import io.tesseraql.operations.outbox.JdbcOutboxStore;
import io.tesseraql.operations.outbox.OutboxDispatcher;
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
    private static final io.tesseraql.core.outbox.OutboxEventSink LOGGING_SINK =
            event -> LOG.info("Outbox delivered {} {}", event.eventType(), event.id());

    private final CamelContext camelContext;
    private final HikariDataSource mainDataSource;
    private final int port;
    private final JobRepository jobRepository;
    private final JobExecutor jobExecutor;
    private final JdbcOutboxStore outboxStore;
    private final Map<String, JobFile> jobs;
    private final String appName;
    private final io.tesseraql.core.threading.ExecutionLanes executionLanes;
    private final TenantDataSources tenantDataSources;
    private final io.tesseraql.yaml.config.AppConfig config;
    private final AutoCloseable pinningSource;
    private final AutoCloseable otelSdk;
    private final io.tesseraql.opsui.OpsDashboard opsDashboard;
    private final io.tesseraql.core.outbox.OutboxEventSink outboxSink;

    private TesseraqlRuntime(CamelContext camelContext, HikariDataSource mainDataSource, int port,
            JobRepository jobRepository, JobExecutor jobExecutor, JdbcOutboxStore outboxStore,
            Map<String, JobFile> jobs, String appName,
            io.tesseraql.core.threading.ExecutionLanes executionLanes,
            TenantDataSources tenantDataSources, io.tesseraql.yaml.config.AppConfig config,
            AutoCloseable pinningSource, AutoCloseable otelSdk,
            io.tesseraql.opsui.OpsDashboard opsDashboard,
            io.tesseraql.core.outbox.OutboxEventSink outboxSink) {
        this.camelContext = camelContext;
        this.mainDataSource = mainDataSource;
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
        AppManifest manifest = new ManifestLoader().load(appHome);
        int port = manifest.config().getString("server.port").map(Integer::parseInt).orElse(8080);
        return start(appHome, manifest, port, new io.tesseraql.core.telemetry.RingTracer(100),
                io.tesseraql.core.telemetry.NoopMeter.INSTANCE);
    }

    /** Starts the runtime against {@code appHome} on an explicit port (used by tests). */
    public static TesseraqlRuntime start(Path appHome, int port) {
        return start(appHome, new ManifestLoader().load(appHome), port,
                new io.tesseraql.core.telemetry.RingTracer(100),
                io.tesseraql.core.telemetry.NoopMeter.INSTANCE);
    }

    /** Starts the runtime with an explicit tracer (used to wire observability). */
    public static TesseraqlRuntime start(Path appHome, int port, io.tesseraql.core.telemetry.Tracer tracer) {
        return start(appHome, new ManifestLoader().load(appHome), port, tracer,
                io.tesseraql.core.telemetry.NoopMeter.INSTANCE);
    }

    /** Starts the runtime with an explicit tracer and meter (used to wire observability). */
    public static TesseraqlRuntime start(Path appHome, int port,
            io.tesseraql.core.telemetry.Tracer tracer, io.tesseraql.core.telemetry.Meter meter) {
        return start(appHome, new ManifestLoader().load(appHome), port, tracer, meter);
    }

    private static TesseraqlRuntime start(Path appHome, AppManifest manifest, int port,
            io.tesseraql.core.telemetry.Tracer tracer, io.tesseraql.core.telemetry.Meter meter) {
        DefaultCamelContext context = new DefaultCamelContext();
        HikariDataSource dataSource = DataSources.create(manifest.config(), "main");
        context.getRegistry().bind("main", dataSource);

        // OTLP export (design ch. 25.7): when an endpoint is configured, fan spans out to OTLP
        // alongside the in-process ring and export metrics via OpenTelemetry.
        io.tesseraql.core.telemetry.Tracer effectiveTracer = tracer;
        io.tesseraql.core.telemetry.Meter effectiveMeter = meter;
        AutoCloseable otelSdk = null;
        String otlpEndpoint = manifest.config().getString("tesseraql.otel.otlp.endpoint").orElse(null);
        if (otlpEndpoint != null && !otlpEndpoint.isBlank()) {
            String serviceName = manifest.config().getString("tesseraql.otel.serviceName")
                    .or(() -> manifest.config().getString("tesseraql.app.name")).orElse("tesseraql");
            io.opentelemetry.sdk.OpenTelemetrySdk sdk =
                    io.tesseraql.observability.OpenTelemetrySupport.otlp(otlpEndpoint, serviceName);
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
        io.tesseraql.core.diag.RingSqlExecutionLog slowSqlLog =
                new io.tesseraql.core.diag.RingSqlExecutionLog(slowSqlCapacity, slowSqlMillis);
        context.getRegistry().bind(TesseraqlProperties.SLOW_SQL_LOG_BEAN, slowSqlLog);

        io.tesseraql.core.diag.PinningMonitor pinningMonitor =
                new io.tesseraql.core.diag.PinningMonitor(100);
        io.tesseraql.core.diag.JfrPinningSource pinningSource = null;
        if (manifest.config().getString("tesseraql.diagnostics.pinning.enabled")
                .map(Boolean::parseBoolean).orElse(false)) {
            long pinMs = manifest.config().getString("tesseraql.diagnostics.pinning.thresholdMillis")
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
        context.getRegistry().bind(TesseraqlProperties.POLICY_ENGINE_BEAN, new PolicyEngine(security));
        if (security.jwt() != null) {
            context.getRegistry().bind(
                    TesseraqlProperties.JWT_AUTHENTICATOR_BEAN, new JwtAuthenticator(security.jwt()));
        }
        // Browser sessions: in-memory per node by default; "jdbc" shares tql_session across all
        // runtime nodes so a login made on one node resolves on every other (design ch. 11.2).
        SessionStore sessionStore;
        if ("jdbc".equalsIgnoreCase(
                manifest.config().getString("tesseraql.sessions.store").orElse("memory"))) {
            io.tesseraql.security.session.JdbcSessionStore jdbcSessions =
                    new io.tesseraql.security.session.JdbcSessionStore(dataSource,
                            java.time.Duration.ofMillis(io.tesseraql.core.util.Durations.toMillis(
                                    manifest.config().getString("tesseraql.sessions.ttl")
                                            .orElse("12h"))));
            jdbcSessions.ensureSchema();
            sessionStore = jdbcSessions;
        } else {
            sessionStore = new io.tesseraql.security.session.InMemorySessionStore();
        }
        context.getRegistry().bind(TesseraqlProperties.SESSION_STORE_BEAN, sessionStore);
        io.tesseraql.core.spool.FileTempStore tempStore =
                new io.tesseraql.core.spool.FileTempStore(appHome.resolve("work/tmp/tesseraql"));
        context.getRegistry().bind(TesseraqlProperties.TEMP_STORE_BEAN, tempStore);

        VertxPlatformHttpServerConfiguration httpConfig = new VertxPlatformHttpServerConfiguration();
        httpConfig.setBindHost("0.0.0.0");
        httpConfig.setBindPort(port);

        JobRepository jobRepository = new JobRepository(dataSource);
        jobRepository.ensureSchema();
        JdbcIdempotencyStore idempotencyStore = new JdbcIdempotencyStore(dataSource);
        idempotencyStore.ensureSchema();
        context.getRegistry().bind(TesseraqlProperties.IDEMPOTENCY_STORE_BEAN, idempotencyStore);
        JdbcOutboxStore outboxStore = new JdbcOutboxStore(dataSource);
        outboxStore.ensureSchema();
        context.getRegistry().bind(TesseraqlProperties.OUTBOX_STORE_BEAN, outboxStore);
        JobExecutor jobExecutor = new JobExecutor(jobRepository, tempStore, slowSqlLog, tracer);
        Map<String, JobFile> jobs = new LinkedHashMap<>();
        manifest.jobs().forEach(job -> jobs.put(job.definition().id(), job));
        String appName = manifest.config().getString("tesseraql.app.name").orElse("app");

        TenantDataSources tenantPools = tenantDataSources;
        AppConfig runtimeConfig = manifest.config();
        OperationsRouteBuilder.JobRunner jobRunner = (jobId, params) -> {
            JobFile jobFile = jobs.get(jobId);
            if (jobFile == null) {
                throw new IllegalArgumentException("Unknown job: " + jobId);
            }
            if (jobFile.definition().perTenant()) {
                List<String> tenants = TenantRegistry.tenantIds(runtimeConfig, dataSource, tenantPools);
                if (!tenants.isEmpty()) {
                    JobExecution last = null;
                    for (String tenantId : tenants) {
                        last = jobExecutor.run(jobFile, tenantPools.dataSourceFor(tenantId, dataSource),
                                io.tesseraql.core.tenant.TenantContext.of(tenantId),
                                appName, params, "manual");
                    }
                    return last;
                }
            }
            return jobExecutor.run(jobFile, dataSource, appName, params, "manual");
        };

        io.tesseraql.core.outbox.OutboxEventSink outboxSink;
        io.tesseraql.opsui.OpsDashboard opsDashboard;
        try {
            opsDashboard = new io.tesseraql.opsui.OpsDashboard(jobRepository, lanes, slowSqlLog,
                    tracer instanceof io.tesseraql.core.telemetry.TraceLog traceLog
                            ? traceLog : io.tesseraql.core.telemetry.TraceLog.empty(),
                    manifest.config().getString("tesseraql.diagnostics.slowSpanMillis")
                            .map(Long::parseLong).orElse(200L),
                    new io.tesseraql.opsui.OpsDashboard.AlertThresholds(
                            manifest.config().getString("tesseraql.diagnostics.errorRateWarnPercent")
                                    .map(Double::parseDouble).orElse(5.0),
                            manifest.config().getString("tesseraql.diagnostics.slowRateWarnPercent")
                                    .map(Double::parseDouble).orElse(20.0),
                            manifest.config().getString("tesseraql.diagnostics.batchFailureWarnPercent")
                                    .map(Double::parseDouble).orElse(10.0)),
                    pinningMonitor);
            // The app's Flyway migrations (db/migration) run before anything queries its schema:
            // fresh installs, upgrades and canary activations all converge here (design ch. 31, 32).
            AppMigrations.migrate(appName, appHome, manifest.config(), dataSource, tenantDataSources);
            context.addService(new VertxPlatformHttpServer(httpConfig));
            context.addRoutes(new RouteCompiler().compile(manifest));
            // Mounted apps (jar-bundled system apps and config-listed directories, design ch. 32)
            // are plain yaml/sql/template trees compiled exactly like the main app.
            List<SystemApps.MountedApp> mountedApps = SystemApps.load(manifest.config(), appHome);
            SystemApps.requireNoRouteConflicts(manifest, mountedApps);
            for (SystemApps.MountedApp mounted : mountedApps) {
                // Mounted apps migrate their own schema (per-app history table) before serving.
                AppMigrations.migrate(mounted.name(), mounted.manifest().appHome(),
                        manifest.config(), dataSource, tenantDataSources);
                context.addRoutes(new RouteCompiler().compile(mounted.manifest()));
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
            context.addRoutes(new AssetsRouteBuilder(appHome.resolve("assets"), appAssets));
            context.addRoutes(new OperationsRouteBuilder(
                    jobRunner, jobRepository, List.copyOf(jobs.keySet()), opsDashboard));
            // Service providers expose non-SQL runtime state to mounted yaml/template apps
            // (the bundled ops-console and studio apps render these, design ch. 26.11, 16, 47).
            io.tesseraql.opsui.OpsDashboard dashboardRef = opsDashboard;
            io.tesseraql.core.service.ServiceProviders serviceProviders =
                    new io.tesseraql.core.service.ServiceProviders()
                            .register("ops.overview", params ->
                                    io.tesseraql.opsui.OpsViews.overview(dashboardRef.overview(20)))
                            .register("ops.traces", params ->
                                    io.tesseraql.opsui.OpsViews.traces(dashboardRef.traceTree()))
                            .register("ops.execution", params -> {
                                String id = params.get("id") == null
                                        ? "" : String.valueOf(params.get("id"));
                                return io.tesseraql.opsui.OpsViews.execution(id,
                                        jobRepository.findExecution(id).orElse(null),
                                        jobRepository.findSteps(id));
                            });
            context.getRegistry().bind(TesseraqlProperties.SERVICE_PROVIDERS_BEAN, serviceProviders);
            context.addRoutes(new SchedulingRouteBuilder(
                    jobRunner, jobRepository, List.copyOf(jobs.values())));

            IdentityService identity = new IdentityService(name ->
                    context.getRegistry().lookupByNameAndType(name, javax.sql.DataSource.class),
                    datasourceDialect(manifest.config()));
            RealmConfig realm = IdentityConfigFactory.defaultRealm(manifest.config(), appHome);
            context.getRegistry().bind(TesseraqlProperties.IDENTITY_SERVICE_BEAN, identity);
            context.getRegistry().bind(TesseraqlProperties.IDENTITY_REALM_BEAN, realm);
            context.addRoutes(new LoginRouteBuilder(
                    new PasswordAuthenticator(identity), realm, sessionStore));
            // Optional feature modules (SCIM, SAML, ...) self-install via ServiceLoader, from the
            // classpath or from signature-verified plugin jars in isolated loaders (ch. 47).
            for (io.tesseraql.compiler.ext.RuntimeExtension extension
                    : RuntimeExtensions.discover(manifest.config(), appHome)) {
                if (extension.enabled(manifest.config())) {
                    extension.install(new io.tesseraql.compiler.ext.ExtensionContext(
                            context, manifest, dataSource));
                    LOG.info("Installed runtime extension '{}'", extension.name());
                }
            }
            // The outbox always logs deliveries; an extension-contributed sink (e.g. SCIM outbound
            // provisioning) is composed on top when bound.
            io.tesseraql.core.outbox.OutboxEventSink extensionSink =
                    context.getRegistry().lookupByNameAndType(
                            TesseraqlProperties.OUTBOX_EVENT_SINK_BEAN,
                            io.tesseraql.core.outbox.OutboxEventSink.class);
            outboxSink = extensionSink == null ? LOGGING_SINK : event -> {
                LOGGING_SINK.send(event);
                extensionSink.send(event);
            };
            if (manifest.config().getString("tesseraql.studio.enabled")
                    .map(Boolean::parseBoolean).orElse(true)) {
                boolean readOnly = manifest.config().getString("tesseraql.studio.readOnly")
                        .map(Boolean::parseBoolean).orElse(true);
                io.tesseraql.studio.StudioService studio =
                        new io.tesseraql.studio.StudioService(manifest, readOnly);
                RouteReloader reloader = new RouteReloader(context, appHome, manifest, studio);
                context.addRoutes(new StudioRouteBuilder(studio, reloader));
                // Providers backing the bundled studio app (design ch. 16, 47).
                serviceProviders
                        .register("studio.explorer", params ->
                                io.tesseraql.studio.StudioViews.explorer(studio.explorer()))
                        .register("studio.source", params -> {
                            String path = String.valueOf(params.get("path"));
                            String draft = studio.readDraft(path);
                            return io.tesseraql.studio.StudioViews.source(path,
                                    draft != null ? draft : studio.source(path),
                                    studio.isReadOnly());
                        })
                        .register("studio.save", params -> {
                            String path = String.valueOf(params.get("path"));
                            Object content = params.get("content");
                            studio.saveDraft(path, content == null ? "" : String.valueOf(content));
                            return Map.of("saved", path);
                        })
                        .register("studio.apply", params -> {
                            String path = String.valueOf(params.get("path"));
                            studio.applyDraft(path);
                            reloader.reload();
                            return Map.of("applied", path);
                        });
            }
            // Retention (design ch. 44): enabled by configuring the sweep interval.
            var retentionSweep = manifest.config().getString("tesseraql.retention.sweep");
            if (retentionSweep.isPresent()) {
                context.addRoutes(new RetentionRouteBuilder(
                        new io.tesseraql.operations.retention.RetentionSweeper(dataSource),
                        io.tesseraql.core.util.Durations.toMillis(retentionSweep.get()),
                        io.tesseraql.core.util.Durations.parse(
                                manifest.config().getString("tesseraql.retention.outbox").orElse("30d")),
                        io.tesseraql.core.util.Durations.parse(
                                manifest.config().getString("tesseraql.retention.jobs").orElse("90d"))));
            }
            var outboxDelay = manifest.config().getString("tesseraql.outbox.dispatch.fixedDelay");
            if (outboxDelay.isPresent()) {
                context.addRoutes(new OutboxDispatchRouteBuilder(outboxStore, outboxSink,
                        io.tesseraql.core.util.Durations.toMillis(outboxDelay.get())));
            }
            context.start();
        } catch (Exception ex) {
            tenantDataSources.close();
            lanes.close();
            dataSource.close();
            throw new IllegalStateException("Failed to start TesseraQL runtime", ex);
        }
        LOG.info("TesseraQL runtime started on port {} for app {}", port, appHome);
        return new TesseraqlRuntime(context, dataSource, port, jobRepository, jobExecutor,
                outboxStore, jobs, appName, lanes, tenantDataSources, manifest.config(),
                pinningSource, otelSdk, opsDashboard, outboxSink);
    }

    /** The configured dialect for the main datasource, or inferred from its JDBC URL (design ch. 42). */
    private static String datasourceDialect(AppConfig config) {
        String prefix = "tesseraql.datasources.main.";
        return config.getString(prefix + "dialect").orElseGet(() ->
                io.tesseraql.core.dialect.Dialect.fromJdbcUrl(config.getString(prefix + "jdbcUrl")
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
        return jobExecutor.run(jobFile, mainDataSource, appName, params, "manual");
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
        for (String tenantId : TenantRegistry.tenantIds(config, mainDataSource, tenantDataSources)) {
            executions.add(jobExecutor.run(jobFile,
                    tenantDataSources.dataSourceFor(tenantId, mainDataSource),
                    io.tesseraql.core.tenant.TenantContext.of(tenantId), appName, params, "manual"));
        }
        return executions;
    }

    public JobRepository jobRepository() {
        return jobRepository;
    }

    /** Dispatches pending outbox events once, returning the number delivered (design ch. 39.2). */
    public int dispatchOutboxOnce() {
        return new OutboxDispatcher(outboxStore, outboxSink).dispatch(100);
    }

    public JdbcOutboxStore outboxStore() {
        return outboxStore;
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
        try {
            camelContext.stop();
        } finally {
            try {
                executionLanes.close();
            } finally {
                try {
                    tenantDataSources.close();
                } finally {
                    mainDataSource.close();
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
