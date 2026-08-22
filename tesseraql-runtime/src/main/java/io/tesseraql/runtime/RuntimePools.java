package io.tesseraql.runtime;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The boot's releasable substrate, built as one named phase (docs/boot-phases.md slice 4):
 * every datasource pool, the telemetry composition, the execution lanes, the diagnostics rings
 * and the tenant pools - exactly the resources the boot's failure path must release. Building
 * them behind one call is what retires the pre-{@code try} boot leak: a failure inside this
 * phase releases its own partial work before rethrowing, and a failure after it releases the
 * record through the boot's catch.
 *
 * <p>Extracted verbatim from {@code TesseraqlRuntime.start(...)}; construction and bind order
 * are unchanged.
 */
record RuntimePools(Map<String, HikariDataSource> dataSources, HikariDataSource dataSource,
        javax.sql.DataSource frameworkDataSource,
        io.tesseraql.core.telemetry.AggregatingMeter aggregatingMeter,
        io.tesseraql.core.telemetry.Tracer effectiveTracer,
        io.tesseraql.core.telemetry.Meter effectiveMeter, AutoCloseable otelSdk,
        io.tesseraql.core.threading.ExecutionLanes lanes,
        io.tesseraql.core.diag.RingSqlExecutionLog slowSqlLog,
        io.tesseraql.core.diag.PinningMonitor pinningMonitor,
        io.tesseraql.core.diag.JfrPinningSource pinningSource,
        TenantDataSources tenantDataSources) {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimePools.class);

    static RuntimePools build(RuntimeContext context, AppManifest manifest, Path appHome,
            DataSources.MainDatasourceOverride override,
            javax.sql.DataSource stackFrameworkDataSource, AppModules modules,
            io.tesseraql.core.telemetry.Tracer tracer, io.tesseraql.core.telemetry.Meter meter) {
        Map<String, HikariDataSource> dataSources = null;
        AutoCloseable otelSdk = null;
        io.tesseraql.core.diag.JfrPinningSource pinningSource = null;
        io.tesseraql.core.threading.ExecutionLanes lanes = null;
        TenantDataSources tenantDataSources = null;
        try {
            // Every datasource declared under tesseraql.datasources gets a pool, registered by name
            // so routes, contracts and per-datasource migrations can address it (design ch. 5.2).
            dataSources = DataSources.createAll(manifest.config(),
                    override, appHome, modules.present() ? modules.loader() : null);
            HikariDataSource dataSource = dataSources.get("main");
            dataSources.forEach((name, pool) -> context.bind(name, pool));
            // Ambient framework state - sessions, tokens, replay guards, rate leases, audit,
            // preferences - may ride its own pool or database (docs/framework-datasource.md).
            // Transactionally- and integrity-coupled stores (outbox, workflow, idempotency,
            // webhook replay) deliberately ignore this key: a config line must not be able
            // to break outbox atomicity. An unknown name refuses the boot - a typo that
            // silently fell back to main would defeat the isolation someone configured.
            javax.sql.DataSource frameworkDataSource;
            if (stackFrameworkDataSource != null) {
                // The stack supplies the connection (docs/stack-architecture.md decision 22): one
                // pool for every runtime, so signing in carries by construction. The name
                // indirection is not consulted — the host refused any explicit declaration before
                // this runtime started (TQL-APP-4212).
                frameworkDataSource = stackFrameworkDataSource;
            } else {
                String frameworkDataSourceName = manifest.config()
                        .getString("tesseraql.framework.datasource").orElse("main");
                frameworkDataSource = dataSources.get(frameworkDataSourceName);
                if (frameworkDataSource == null) {
                    throw new io.tesseraql.core.error.TqlException(
                            new io.tesseraql.core.error.TqlErrorCode(
                                    io.tesseraql.core.error.TqlDomain.APP, 5205),
                            "tesseraql.framework.datasource names '" + frameworkDataSourceName
                                    + "' but no such datasource is declared under"
                                    + " tesseraql.datasources");
                }
            }

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
            String activeProfile = io.tesseraql.yaml.manifest.ManifestLoader.activeProfile();
            if (activeProfile != null) {
                LOG.info("Environment profile active: {} (config/env/{}.yml)", activeProfile,
                        activeProfile);
            }
            context.bind(TesseraqlProperties.TRACER_BEAN, effectiveTracer);
            context.bind(TesseraqlProperties.METER_BEAN, effectiveMeter);
            // This runtime's function set, bound where the tracer and lanes bind so the SQL
            // producers parse against it (docs/module-scope.md).
            context.bind(TesseraqlProperties.FUNCTIONS_BEAN, modules.functions());

            lanes = LaneConfigs.load(manifest.config());
            context.bind(TesseraqlProperties.LANES_BEAN, lanes);
            for (io.tesseraql.core.threading.Lane lane : lanes.all()) {
                context.bind(
                        TesseraqlProperties.laneExecutorRef(lane.name()), lane.executor());
            }

            int slowSqlCapacity = manifest.config()
                    .getString("tesseraql.diagnostics.slowSqlCapacity")
                    .map(Integer::parseInt).orElse(100);
            long slowSqlMillis = manifest.config().getString("tesseraql.diagnostics.slowSqlMillis")
                    .map(Long::parseLong).orElse(200L);
            io.tesseraql.core.diag.RingSqlExecutionLog slowSqlLog = new io.tesseraql.core.diag.RingSqlExecutionLog(
                    slowSqlCapacity, slowSqlMillis);
            context.bind(TesseraqlProperties.SLOW_SQL_LOG_BEAN, slowSqlLog);

            io.tesseraql.core.diag.PinningMonitor pinningMonitor = new io.tesseraql.core.diag.PinningMonitor(
                    100);
            if (manifest.config().getString("tesseraql.diagnostics.pinning.enabled")
                    .map(Boolean::parseBoolean).orElse(false)) {
                long pinMs = manifest.config()
                        .getString("tesseraql.diagnostics.pinning.thresholdMillis")
                        .map(Long::parseLong).orElse(20L);
                pinningSource = new io.tesseraql.core.diag.JfrPinningSource(
                        pinningMonitor, java.time.Duration.ofMillis(pinMs));
            }

            tenantDataSources = TenantDataSources.load(manifest.config(),
                    modules.present() ? modules.loader() : null);
            if (!tenantDataSources.isEmpty()) {
                context.bind(
                        TesseraqlProperties.TENANT_DATASOURCE_RESOLVER_BEAN, tenantDataSources);
            }

            // Code catalogs (docs/lookups.md, decision 8): small, nearly static tables of codes and
            // names, loaded whole and resolved from memory wherever a code is rendered.
            io.tesseraql.yaml.catalog.Catalogs codeCatalogs = io.tesseraql.yaml.catalog.Catalogs
                    .load(appHome);
            if (!codeCatalogs.isEmpty()) {
                if (!tenantDataSources.isEmpty()) {
                    // A held catalog is app-wide; serving one tenant's codes to another is a data
                    // leak, so the combination is refused until catalogs key by tenant rather than
                    // being discovered in production (docs/lookups.md, decision 14).
                    throw new io.tesseraql.core.error.TqlException(
                            new io.tesseraql.core.error.TqlErrorCode(
                                    io.tesseraql.core.error.TqlDomain.APP, 4207),
                            "catalogs/ and per-tenant datasources are declared together; a catalog is"
                                    + " held app-wide and is not yet keyed by tenant");
                }
                io.tesseraql.operations.catalog.JdbcCatalogStore catalogStore = new io.tesseraql.operations.catalog.JdbcCatalogStore(
                        codeCatalogs.all(),
                        dataSources::get, TesseraqlRuntime.datasourceDialect(manifest.config()),
                        manifest.appHome(), io.tesseraql.yaml.i18n.I18nSettings
                                .from(manifest.config(), manifest.appHome()));
                // The version table carries an invalidation to the runtimes that did not serve the
                // command; failing to create it disables the stamp, never the catalogs.
                catalogStore.ensureSchema();
                context.bind(TesseraqlProperties.CATALOG_STORE_BEAN, catalogStore);
            }
            return new RuntimePools(dataSources, dataSource, frameworkDataSource,
                    aggregatingMeter, effectiveTracer, effectiveMeter, otelSdk, lanes,
                    slowSqlLog, pinningMonitor, pinningSource, tenantDataSources);
        } catch (RuntimeException | Error failure) {
            // This phase owns what it built until the record is handed back: released here in
            // the same order the boot's catch and close() release — executors before the pools
            // their work borrows connections from — so a refusal (an unknown framework
            // datasource name, catalogs beside tenant pools) leaves no pool, exporter or
            // recording behind.
            TesseraqlRuntime.closeQuietly(pinningSource);
            TesseraqlRuntime.closeQuietly(otelSdk);
            TesseraqlRuntime.closeQuietly(lanes);
            TesseraqlRuntime.closeQuietly(tenantDataSources);
            if (dataSources != null) {
                dataSources.values().forEach(TesseraqlRuntime::closeQuietly);
            }
            throw failure;
        }
    }
}
