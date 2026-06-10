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
        SessionStore sessionStore = new SessionStore();
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

        io.tesseraql.core.outbox.OutboxEventSink outboxSink = scimOutboundSink(manifest, dataSource);
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
            context.addService(new VertxPlatformHttpServer(httpConfig));
            context.addRoutes(new RouteCompiler().compile(manifest));
            context.addRoutes(new OperationsRouteBuilder(
                    jobRunner, jobRepository, List.copyOf(jobs.keySet()), opsDashboard));
            context.addRoutes(new SchedulingRouteBuilder(jobRunner, List.copyOf(jobs.values())));
            if (manifest.config().getString("tesseraql.scim.enabled")
                    .map(Boolean::parseBoolean).orElse(false)) {
                context.addRoutes(new ScimRouteBuilder(buildScimUserService(manifest, dataSource),
                        buildScimGroupService(manifest, dataSource)));
            }

            IdentityService identity = new IdentityService(name ->
                    context.getRegistry().lookupByNameAndType(name, javax.sql.DataSource.class));
            RealmConfig realm = IdentityConfigFactory.defaultRealm(manifest.config(), appHome);
            context.getRegistry().bind(TesseraqlProperties.IDENTITY_SERVICE_BEAN, identity);
            context.getRegistry().bind(TesseraqlProperties.IDENTITY_REALM_BEAN, realm);
            context.addRoutes(new LoginRouteBuilder(
                    new PasswordAuthenticator(identity), realm, sessionStore));
            if (manifest.config().getString("tesseraql.saml.enabled")
                    .map(Boolean::parseBoolean).orElse(false)) {
                context.addRoutes(buildSamlAcs(manifest, sessionStore, identity, realm));
            }
            if (manifest.config().getString("tesseraql.studio.enabled")
                    .map(Boolean::parseBoolean).orElse(true)) {
                boolean readOnly = manifest.config().getString("tesseraql.studio.readOnly")
                        .map(Boolean::parseBoolean).orElse(true);
                io.tesseraql.studio.StudioService studio =
                        new io.tesseraql.studio.StudioService(manifest, readOnly);
                context.addRoutes(new StudioRouteBuilder(studio,
                        new RouteReloader(context, appHome, manifest, studio)));
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

    /**
     * Builds the outbox sink: the logging sink, optionally composed with SCIM outbound sinks that
     * provision {@code USER_*}/{@code GROUP_*} events to a downstream provider (design ch. 10.15). The
     * user and group provisioners share one HTTP client and one resource-mapping table (group keys are
     * namespaced), so both resource types are provisioned from the same outbox. At-least-once retry is
     * preserved because a sink failure propagates.
     */
    private static io.tesseraql.core.outbox.OutboxEventSink scimOutboundSink(
            AppManifest manifest, HikariDataSource dataSource) {
        if (!manifest.config().getString("tesseraql.scim.outbound.enabled")
                .map(Boolean::parseBoolean).orElse(false)) {
            return LOGGING_SINK;
        }
        io.tesseraql.scim.ScimTarget target = new io.tesseraql.scim.ScimTarget(
                manifest.config().requireString("tesseraql.scim.outbound.target.url"),
                manifest.config().getString("tesseraql.scim.outbound.target.token").orElse(""));
        io.tesseraql.scim.JdbcScimResourceMapping mapping =
                new io.tesseraql.scim.JdbcScimResourceMapping(dataSource);
        mapping.ensureSchema();
        io.tesseraql.scim.ScimOutboundClient client = new io.tesseraql.scim.ScimOutboundClient(target);
        io.tesseraql.scim.ScimOutboundSink userSink = new io.tesseraql.scim.ScimOutboundSink(
                new io.tesseraql.scim.ScimProvisioner(client, mapping));
        io.tesseraql.scim.ScimGroupOutboundSink groupSink = new io.tesseraql.scim.ScimGroupOutboundSink(
                new io.tesseraql.scim.ScimGroupProvisioner(client, mapping));
        return event -> {
            LOGGING_SINK.send(event);
            userSink.send(event);
            groupSink.send(event);
        };
    }

    /**
     * Builds the SAML ACS route from configuration (design ch. 10.14): the SP audience and ACS URL,
     * the pinned IdP signing key loaded from {@code tesseraql.saml.idp.publicKey} (a certificate or
     * public-key file under the app home), and the assertion-to-principal attribute mapping.
     */
    private static SamlAcsRouteBuilder buildSamlAcs(AppManifest manifest,
            io.tesseraql.security.session.SessionStore sessions, IdentityService identity,
            RealmConfig realm) {
        var config = manifest.config();
        String audience = config.requireString("tesseraql.saml.sp.audience");
        String recipient = config.getString("tesseraql.saml.sp.acsUrl").orElse(null);
        // The pinned IdP signing key comes from IdP metadata when configured, else a key/cert file.
        java.security.PublicKey idpKey = config.getString("tesseraql.saml.idp.metadata")
                .map(path -> io.tesseraql.saml.IdpMetadata.signingKey(readSamlBytes(manifest, path)))
                .orElseGet(() -> io.tesseraql.saml.SamlKeys.publicKey(
                        readSamlBytes(manifest, config.requireString("tesseraql.saml.idp.publicKey"))));
        io.tesseraql.saml.SamlResponseValidator validator = new io.tesseraql.saml.SamlResponseValidator(
                new io.tesseraql.saml.SamlValidationConfig(audience, idpKey, recipient, null));
        io.tesseraql.saml.SamlAttributeMapping mapping = new io.tesseraql.saml.SamlAttributeMapping(
                config.getString("tesseraql.saml.attributes.loginId").orElse(null),
                config.getString("tesseraql.saml.attributes.displayName").orElse(null),
                config.getString("tesseraql.saml.attributes.email").orElse(null),
                config.getString("tesseraql.saml.attributes.roles").orElse(null),
                config.getString("tesseraql.saml.attributes.groups").orElse(null),
                config.getString("tesseraql.saml.attributes.tenant").orElse(null));
        // When link mode is on, resolve (and optionally provision) a local user so authorization uses
        // locally-managed roles instead of IdP-asserted ones (design ch. 10.14 userLink).
        boolean link = config.getString("tesseraql.saml.link.enabled")
                .map(Boolean::parseBoolean).orElse(false);
        SamlUserLinker linker = link ? new SamlUserLinker(identity, realm,
                config.getString("tesseraql.saml.link.provision")
                        .map(Boolean::parseBoolean).orElse(false)) : null;
        // Advertise SP metadata only when the ACS URL is known.
        io.tesseraql.saml.SpMetadata metadata = recipient == null ? null
                : new io.tesseraql.saml.SpMetadata(audience, recipient,
                        config.getString("tesseraql.saml.sp.nameIdFormat").orElse(null));
        SamlEndpoints endpoints = new SamlEndpoints(audience, recipient,
                config.getString("tesseraql.saml.idp.ssoUrl").orElse(null),
                config.getString("tesseraql.saml.idp.sloUrl").orElse(null));
        return new SamlAcsRouteBuilder(validator, mapping, sessions, linker, metadata, endpoints);
    }

    private static byte[] readSamlBytes(AppManifest manifest, String relative) {
        try {
            return java.nio.file.Files.readAllBytes(manifest.appHome().resolve(relative).normalize());
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Cannot read SAML key material: " + relative, ex);
        }
    }

    /** Builds the SCIM user service from the configured contract SQL files (design ch. 10.15). */
    private static io.tesseraql.scim.ScimUserService buildScimUserService(
            AppManifest manifest, HikariDataSource dataSource) {
        io.tesseraql.scim.ScimContract contract = new io.tesseraql.scim.ScimContract(
                readScimSql(manifest, "tesseraql.scim.users.create"),
                readScimSql(manifest, "tesseraql.scim.users.findById"),
                readScimSql(manifest, "tesseraql.scim.users.list"),
                readScimSql(manifest, "tesseraql.scim.users.replace"),
                readScimSql(manifest, "tesseraql.scim.users.delete"),
                readScimSql(manifest, "tesseraql.scim.users.findByUserName"),
                readScimSqlOptional(manifest, "tesseraql.scim.users.count"));
        return new io.tesseraql.scim.ScimUserService(dataSource, contract);
    }

    /**
     * Builds the SCIM group service from the configured contract SQL files, or {@code null} when
     * group provisioning is disabled (design ch. 10.15). Returning null leaves the {@code /Groups}
     * endpoints unmounted.
     */
    private static io.tesseraql.scim.ScimGroupService buildScimGroupService(
            AppManifest manifest, HikariDataSource dataSource) {
        if (!manifest.config().getString("tesseraql.scim.groups.enabled")
                .map(Boolean::parseBoolean).orElse(false)) {
            return null;
        }
        io.tesseraql.scim.ScimGroupContract contract = new io.tesseraql.scim.ScimGroupContract(
                readScimSql(manifest, "tesseraql.scim.groups.create"),
                readScimSql(manifest, "tesseraql.scim.groups.findById"),
                readScimSql(manifest, "tesseraql.scim.groups.list"),
                readScimSql(manifest, "tesseraql.scim.groups.replace"),
                readScimSql(manifest, "tesseraql.scim.groups.delete"),
                readScimSql(manifest, "tesseraql.scim.groups.listMembers"),
                readScimSql(manifest, "tesseraql.scim.groups.addMember"),
                readScimSql(manifest, "tesseraql.scim.groups.removeMember"),
                readScimSqlOptional(manifest, "tesseraql.scim.groups.count"));
        return new io.tesseraql.scim.ScimGroupService(dataSource, contract);
    }

    private static String readScimSql(AppManifest manifest, String configKey) {
        String relative = manifest.config().requireString(configKey);
        try {
            return java.nio.file.Files.readString(manifest.appHome().resolve(relative).normalize());
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Cannot read SCIM contract SQL: " + relative, ex);
        }
    }

    /** Reads an optional SCIM contract SQL file, returning {@code null} when the key is unset. */
    private static String readScimSqlOptional(AppManifest manifest, String configKey) {
        return manifest.config().getString(configKey).isPresent()
                ? readScimSql(manifest, configKey) : null;
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
