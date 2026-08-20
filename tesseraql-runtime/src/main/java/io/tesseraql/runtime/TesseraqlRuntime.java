package io.tesseraql.runtime;

import com.zaxxer.hikari.HikariDataSource;
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
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.TesseraqlProperties;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The TesseraQL runtime (design ch. 19.2).
 *
 * <p>Loads an external app home, wires datasources and the embedded HTTP server, compiles the
 * Simple YAML routes into pipelines, and starts the context. The SQL step is discovered from the
 * classpath service descriptor.
 */
public final class TesseraqlRuntime implements AutoCloseable {

    static final Logger LOG = LoggerFactory.getLogger(TesseraqlRuntime.class);
    private static final io.tesseraql.core.outbox.OutboxEventSink LOGGING_SINK = event -> LOG
            .info("Outbox delivered {} {}", event.eventType(), event.id());
    private static final io.tesseraql.core.error.TqlErrorCode DUPLICATE_JOB = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.APP, 4202);
    /** TQL-YAML-1112: a declared HTTP thread count that is not a positive integer. */
    private static final io.tesseraql.core.error.TqlErrorCode BAD_THREAD_COUNT = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.YAML, 1112);

    /** The declared drain bound, in milliseconds; Long.MAX_VALUE when forceOnTimeout is off. */
    static final String SHUTDOWN_TIMEOUT_BEAN = "tesseraqlShutdownTimeoutMillis";

    private final RuntimeContext runtimeContext;
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
    private final AppModules appModules;
    /**
     * The reason recorded on job runs the drain stops cooperatively (docs/runtime-replace.md):
     * {@code close()} asks every run this runtime owns to stop at its next checkpoint before the
     * drain begins, and this string is what the stopped execution says happened. The host
     * names the deploy here before retiring a replaced runtime; anything else drains under the
     * shutdown wording.
     */
    private volatile String drainReason = "stopped: the runtime is shutting down"
            + " (cooperative stop)";

    private TesseraqlRuntime(RuntimeContext runtimeContext,
            Map<String, HikariDataSource> dataSources,
            int port,
            JobRepository jobRepository, JobExecutor jobExecutor, JdbcOutboxStore outboxStore,
            Map<String, JobFile> jobs, Map<String, String> jobOwners, String appName,
            java.util.Set<String> hostedApps,
            io.tesseraql.core.threading.ExecutionLanes executionLanes,
            TenantDataSources tenantDataSources, io.tesseraql.yaml.config.AppConfig config,
            AutoCloseable pinningSource, AutoCloseable otelSdk,
            io.tesseraql.opsui.OpsDashboard opsDashboard,
            io.tesseraql.core.outbox.OutboxEventSink outboxSink, AppModules appModules) {
        this.runtimeContext = runtimeContext;
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
        this.appModules = appModules;
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
     * One application's modules and manifest, in the order decision 28 requires: the module
     * loader exists before the manifest loads, because manifest loading already parses
     * expressions (the decision-wiring root checks) and must resolve them against this
     * application's own functions (docs/module-scope.md).
     */
    private record LoadedApp(AppModules modules, AppManifest manifest) {

        static LoadedApp of(Path appHome, java.io.File extraModules) {
            AppModules modules = AppModules.load(appHome,
                    ManifestLoader.configOnly(appHome), extraModules);
            try {
                return new LoadedApp(modules,
                        new ManifestLoader().load(appHome, modules.functions()));
            } catch (RuntimeException | Error ex) {
                closeQuietly(modules);
                throw ex;
            }
        }

        /**
         * Runs one start path owning the loader this record just built: until the call reaches
         * the boot's own failure handling, a thrown refusal — a malformed {@code server.port},
         * the pools phase releasing itself before the hoisted {@code try} — would strand the
         * application's module classloader and its open jar handles. Closing twice is fine:
         * the boot's catch may already have released it, and a closed loader closes quietly.
         */
        TesseraqlRuntime startOwning(java.util.function.Supplier<TesseraqlRuntime> body) {
            try {
                return body.get();
            } catch (RuntimeException | Error ex) {
                closeQuietly(modules);
                throw ex;
            }
        }
    }

    /**
     * Starts the runtime against {@code appHome} on the configured port, pointing the {@code main}
     * datasource at {@code override} when non-null (the {@code serve --embedded-db} path).
     */
    public static TesseraqlRuntime start(Path appHome,
            DataSources.MainDatasourceOverride override) {
        LoadedApp app = LoadedApp.of(appHome, null);
        return app.startOwning(() -> {
            int port = app.manifest().config().getString("server.port").map(Integer::parseInt)
                    .orElse(8080);
            return start(appHome, app.manifest(), port,
                    new io.tesseraql.core.telemetry.RingTracer(ringCapacity(app.manifest())),
                    io.tesseraql.core.telemetry.NoopMeter.INSTANCE, override, null,
                    app.modules());
        });
    }

    /** Starts the runtime against {@code appHome} on an explicit port (used by tests). */
    public static TesseraqlRuntime start(Path appHome, int port) {
        return start(appHome, port, (DataSources.MainDatasourceOverride) null);
    }

    /** Starts the runtime on an explicit port, with the {@code main} datasource override applied. */
    public static TesseraqlRuntime start(Path appHome, int port,
            DataSources.MainDatasourceOverride override) {
        LoadedApp app = LoadedApp.of(appHome, null);
        return app.startOwning(() -> start(appHome, app.manifest(), port,
                new io.tesseraql.core.telemetry.RingTracer(ringCapacity(app.manifest())),
                io.tesseraql.core.telemetry.NoopMeter.INSTANCE, override, null, app.modules()));
    }

    /** Starts the runtime with an explicit tracer (used to wire observability). */
    public static TesseraqlRuntime start(Path appHome, int port,
            io.tesseraql.core.telemetry.Tracer tracer) {
        LoadedApp app = LoadedApp.of(appHome, null);
        return app.startOwning(() -> start(appHome, app.manifest(), port, tracer,
                io.tesseraql.core.telemetry.NoopMeter.INSTANCE, null, null, app.modules()));
    }

    /** Starts the runtime with an explicit tracer and meter (used to wire observability). */
    public static TesseraqlRuntime start(Path appHome, int port,
            io.tesseraql.core.telemetry.Tracer tracer, io.tesseraql.core.telemetry.Meter meter) {
        LoadedApp app = LoadedApp.of(appHome, null);
        return app.startOwning(() -> start(appHome, app.manifest(), port, tracer, meter, null,
                null, app.modules()));
    }

    /**
     * Starts with the settings a host decided, overriding whatever the app's own configuration
     * says about them (docs/base-path.md decisions 1 and 4, docs/stack-architecture.md decision
     * 16). A stack host passes the address the catalogue declares and a cookie path of {@code /}; the
     * values belong to the deployment, not to the application's files, so the same package mounts
     * at two prefixes in two places — and only the host knows whether these applications are one
     * stack sharing a sign-in.
     */
    static TesseraqlRuntime start(Path appHome, int port, HostContext host) {
        LoadedApp app = LoadedApp.of(appHome, host.extraModules());
        // Hosted (stack) mode had no tracing at all, so the console's trace pages behind `tesseraql host`
        // were permanently empty (docs/audit-hardening.md Decision 7). An app hosted in a stack is
        // the same app: it gets the same in-process ring every other start path gets.
        return app.startOwning(() -> start(appHome, withBasePath(app.manifest(), host.basePath()),
                port,
                new io.tesseraql.core.telemetry.RingTracer(ringCapacity(app.manifest())),
                io.tesseraql.core.telemetry.NoopMeter.INSTANCE, host.mainDataSourceOverride(),
                host.frameworkDataSource(), true, host.cookiePath(), host,
                app.modules()));
    }

    /**
     * Per-pool connection counts, read from Hikari's MXBean
     * (docs/audit-hardening.md Decision 9).
     *
     * <p>Kept here rather than in {@code tesseraql-ops-ui} because that module has no Hikari
     * dependency and this is not a reason to give it one: the ops module declares the shape it
     * wants and the runtime, which already owns the pools, fills it in.
     */
    private static Map<String, Map<String, Integer>> poolStats(
            Map<String, HikariDataSource> dataSources) {
        Map<String, Map<String, Integer>> stats = new LinkedHashMap<>();
        dataSources.forEach((name, dataSource) -> {
            com.zaxxer.hikari.HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
            if (pool != null) {
                stats.put(name, Map.of(
                        "active", pool.getActiveConnections(),
                        "idle", pool.getIdleConnections(),
                        "total", pool.getTotalConnections(),
                        "awaiting", pool.getThreadsAwaitingConnection()));
            }
        });
        return stats;
    }

    /**
     * The ring capacity a manifest asks for.
     *
     * <p>Read here as well as on the main start path because stack mode builds its own tracer
     * before that path runs.
     */
    private static int ringCapacity(AppManifest manifest) {
        return manifest.config().getString("tesseraql.diagnostics.traceRingCapacity")
                .map(Integer::parseInt).orElse(100);
    }

    /**
     * The Vert.x sizing for this runtime's HTTP server (docs/http-threading.md decision 1).
     *
     * <p>Route execution no longer sits here (docs/http-edge.md decision 1): the runtime's own
     * edge runs each request on a virtual thread. What the pool still bounds is the work that
     * genuinely hands off to it — the stack relay and the multi-app gateway — so it is sized for
     * those rather than for concurrent route execution.
     *
     * <p>It was the ceiling on route execution when every exchange went to
     * {@code executeBlocking}, and that is why the pairing below exists: Vert.x's own default of
     * 20 was chosen for a framework where blocking is the exception, and against the connection
     * pool's default of 10 it left half the workers able to do nothing but wait in
     * {@code getConnection()}. Both now default to 10 and are raised together.
     *
     * <p>The event loop count keeps Vert.x's default unless asked otherwise: loops are not where
     * blocking work sits, and a host running several runtimes is the case that wants them lowered.
     */
    static io.vertx.core.VertxOptions vertxOptions(AppConfig config) {
        io.vertx.core.VertxOptions options = new io.vertx.core.VertxOptions();
        // Vert.x resolves every path it is asked to send against the classpath first, copying
        // matches into a cache directory. Nothing here wants that: the classpath half of the
        // asset tree is read directly and held in memory, and the filesystem half is an absolute
        // path that can only ever miss — at the cost of a classpath scan per request, which
        // measured at over a second for a stylesheet on an idle runtime.
        options.setFileSystemOptions(new io.vertx.core.file.FileSystemOptions()
                .setClassPathResolvingEnabled(false));
        // Both keys are read with their literal spelled at the call site: docs/reference-config.md
        // is generated by scanning for exactly this shape, and a key read through a variable is a
        // key the configuration index cannot report.
        options.setWorkerPoolSize(threadCount("tesseraql.http.workerThreads",
                config.getString("tesseraql.http.workerThreads")).orElse(10));
        threadCount("tesseraql.http.eventLoopThreads",
                config.getString("tesseraql.http.eventLoopThreads"))
                .ifPresent(options::setEventLoopPoolSize);
        return options;
    }

    /**
     * A hosted member does not size the transport it shares (docs/http-threading.md decision 4).
     *
     * <p>The host builds one Vert.x for every runtime, so a member's own thread counts reach
     * nothing — and a setting that is read, parsed and then ignored is the shape this codebase
     * removes wherever it finds it. Said out loud rather than refused: the declaration is correct
     * for the same application run standalone, and an application is not wrong for having been
     * hosted. The in-flight bound is deliberately not on this list — that gate is per runtime, and
     * per-member bounds are what keep one application from consuming the shared pool.
     */
    private static void warnOnMemberThreadSizing(AppConfig config, String appName) {
        for (String key : java.util.List.of("tesseraql.http.workerThreads",
                "tesseraql.http.eventLoopThreads")) {
            if (config.getString(key).isPresent()) {
                LOG.warn("{} declares {}, which a hosted application does not decide: the host"
                        + " sizes the one Vert.x every runtime in the stack shares. Set it in"
                        + " {} instead.", appName, key,
                        io.tesseraql.operations.app.StackSettings.FILE_NAME);
            }
        }
    }

    /**
     * How many requests this runtime will hold in flight (docs/http-threading.md decision 3).
     *
     * <p>Four times the worker pool by default: enough room for the ordinary burst a queue exists
     * to absorb, while keeping the queue a number an operator can see rather than "however much
     * heap it takes". Beyond it the answer is an immediate 503, which is a slowdown a caller can
     * retry — where an unbounded queue is an outage that takes health and readiness with it.
     */
    private static int maxInFlight(AppConfig config) {
        return threadCount("tesseraql.http.maxInFlight",
                config.getString("tesseraql.http.maxInFlight"))
                .orElseGet(() -> threadCount("tesseraql.http.workerThreads",
                        config.getString("tesseraql.http.workerThreads")).orElse(10) * 4);
    }

    /**
     * A declared thread count: absent, or a positive integer.
     *
     * <p>A thread pool sized from a typo is worse than one left at its default, because the
     * runtime starts and only the load that needed the threads finds out.
     */
    private static java.util.OptionalInt threadCount(String key,
            java.util.Optional<String> declared) {
        if (declared.isEmpty()) {
            return java.util.OptionalInt.empty();
        }
        String text = declared.get().trim();
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException notANumber) {
            throw new io.tesseraql.core.error.TqlException(BAD_THREAD_COUNT,
                    key + " must be a positive integer, got '" + text + "'");
        }
        if (value < 1) {
            throw new io.tesseraql.core.error.TqlException(BAD_THREAD_COUNT,
                    key + " must be at least 1, got " + value);
        }
        return java.util.OptionalInt.of(value);
    }

    /**
     * The manifest with {@code tesseraql.http.basePath} replaced by the host's value.
     *
     * <p>Only {@code null} means "no host is speaking". The empty string is the origin root, which
     * a catalogue entry declares as {@code /} and {@link io.tesseraql.operations.app.InstalledApp}
     * normalises away — so treating it as absent would leave an application whose own
     * configuration names a prefix serving that prefix while the gateway forwards it the root's
     * paths, and every request would 404.
     */
    private static AppManifest withBasePath(AppManifest manifest, String basePath) {
        if (basePath == null) {
            return manifest;
        }
        Map<String, Object> root = SystemApps.deepCopy(manifest.config().root());
        SystemApps.childMap(SystemApps.childMap(root, "tesseraql"), "http")
                .put("basePath", basePath);
        return manifest.withConfig(new AppConfig(root));
    }

    /**
     * @param cookiePath the {@code Path} session cookies are issued with, supplied by whatever
     *                   starts the runtime (docs/base-path.md decision 4). Null means the
     *                   standalone answer: the application's own base path, so its cookie is not
     *                   offered to whatever else lives on that origin.
     */
    private static TesseraqlRuntime start(Path appHome, AppManifest manifest, int port,
            io.tesseraql.core.telemetry.Tracer tracer, io.tesseraql.core.telemetry.Meter meter,
            DataSources.MainDatasourceOverride override, String cookiePath, AppModules modules) {
        return start(appHome, manifest, port, tracer, meter, override, null, false, cookiePath,
                (HostContext) null, modules);
    }

    /**
     * @param stackFrameworkDataSource the stack's framework pool, when its file supplies one
     *                                 (docs/stack-architecture.md decision 22): host-built,
     *                                 host-owned, never closed here. Null means this runtime
     *                                 resolves its own {@code tesseraql.framework.datasource} —
     *                                 the host has already refused the explicit-declaration
     *                                 collision, so no name resolution happens when this is set
     * @param hostedValidatesFramework true when a host migrated the stack-wide {@code security}
     *                                 schema before this runtime started, so this runtime
     *                                 VALIDATES it instead of migrating and refuses to start on
     *                                 a mismatch — the wrong-framework-datasource guard
     *                                 (docs/stack-architecture.md decision 16)
     * @param hostContext              the host's settings record, when a host started this
     *                                 runtime; null for the unhosted boot (tests, embedding).
     *                                 The surface runtime's context carries the member list and
     *                                 the live member-origin lookup (docs/root-portal.md,
     *                                 docs/stack-shells.md), so the portal and ops-shell
     *                                 providers register only there; a hosted member's context
     *                                 carries neither, which is also what keys the mount skip
     *                                 for the surfaces that moved to the origin scope
     * @param modules                  this application's modules, loaded before its manifest
     *                                 (docs/module-scope.md): the function set its expressions
     *                                 parse with, the loader its codecs discover from, and a
     *                                 resource this runtime owns and closes after its pools
     */
    private static TesseraqlRuntime start(Path appHome, AppManifest loaded, int port,
            io.tesseraql.core.telemetry.Tracer tracer, io.tesseraql.core.telemetry.Meter meter,
            DataSources.MainDatasourceOverride override,
            javax.sql.DataSource stackFrameworkDataSource, boolean hostedValidatesFramework,
            String cookiePath,
            HostContext hostContext,
            AppModules modules) {
        // The stack file's security subtree arrives on the surface runtime's context and is
        // grafted over its configuration before anything reads it (docs/stack-shells.md, the
        // deploy surface): the origin's JWT validation, token issuing and deploy endpoint all
        // configure from the merged tree, through the same keys an application would declare.
        final AppManifest manifest = withStackContext(loaded, hostContext);
        java.util.List<io.tesseraql.operations.app.InstalledApp> stackMembers = hostContext == null
                ? null
                : hostContext.stackMembers();
        // A hosted member (a host is speaking and handed it no member list): the framework
        // surfaces that live at the stack's origin scope never mount into it
        // (docs/stack-shells.md structural decision 2).
        boolean hostedMember = hostContext != null && stackMembers == null;
        RuntimeContext context = new RuntimeContext();
        // The prefix this application is served under, published before anything mounts a route
        // or emits a URL (docs/base-path.md). The compiler sets it on the REST configuration;
        // the surfaces outside the REST DSL — static assets, the SSE streams — and the response
        // headers that carry a URL read it from here.
        String basePath = io.tesseraql.core.http.BasePaths.normalize(
                manifest.config().getString("tesseraql.http.basePath").orElse(null));
        io.tesseraql.pipeline.BasePath.bind(context, basePath);
        io.tesseraql.pipeline.CookiePath.bind(context,
                cookiePath != null ? cookiePath : basePath);
        // Pools, telemetry, lanes, diagnostics, tenant pools and catalogs build as one
        // named phase that releases its own partial work on failure
        // (docs/boot-phases.md slice 4) - the pre-try boot leak retired by ownership.
        RuntimePools pools = RuntimePools.build(context, manifest, appHome, override,
                stackFrameworkDataSource, modules, tracer, meter);
        Map<String, HikariDataSource> dataSources = pools.dataSources();
        HikariDataSource dataSource = pools.dataSource();
        javax.sql.DataSource frameworkDataSource = pools.frameworkDataSource();
        io.tesseraql.core.telemetry.AggregatingMeter aggregatingMeter = pools.aggregatingMeter();
        io.tesseraql.core.telemetry.Tracer effectiveTracer = pools.effectiveTracer();
        io.tesseraql.core.telemetry.Meter effectiveMeter = pools.effectiveMeter();
        AutoCloseable otelSdk = pools.otelSdk();
        io.tesseraql.core.threading.ExecutionLanes lanes = pools.lanes();
        io.tesseraql.core.diag.RingSqlExecutionLog slowSqlLog = pools.slowSqlLog();
        io.tesseraql.core.diag.PinningMonitor pinningMonitor = pools.pinningMonitor();
        io.tesseraql.core.diag.JfrPinningSource pinningSource = pools.pinningSource();
        TenantDataSources tenantDataSources = pools.tenantDataSources();
        // From here every failure releases the record above through the catch at the end -
        // the half of the boot leak that ownership inside RuntimePools does not cover
        // (docs/boot-phases.md slice 4): the boot has exactly two failure behaviours, the
        // phase's own and this one.
        try {

            SecurityConfig security = SecurityConfigFactory.build(manifest.config());
            context.bind(TesseraqlProperties.POLICY_ENGINE_BEAN,
                    new PolicyEngine(security));
            // Context conditions, both layers (docs/access-governance.md structural decision 8).
            // Layer A is bound only when the deployment names its networks, so an unconfigured
            // one looks up nothing and admits everybody. The zone is bound only when it differs
            // from the JVM's, which is the same "absent means the default" reading.
            io.tesseraql.security.net.SignInAllowList signInNetworks = io.tesseraql.security.net.SignInAllowList
                    .parse(manifest.config().getString("tesseraql.security.network.allow")
                            .orElse(null));
            if (signInNetworks.restricts()) {
                context.bind(TesseraqlProperties.SIGN_IN_ALLOW_LIST_BEAN,
                        signInNetworks);
            }
            manifest.config().getString("tesseraql.security.conditions.zone")
                    .map(String::trim).filter(zone -> !zone.isEmpty())
                    .ifPresent(zone -> context.bind(
                            TesseraqlProperties.CONDITION_ZONE_BEAN, java.time.ZoneId.of(zone)));
            // Organizational data scoping (roadmap Phase 29): the resolver expands /*%scope ... */
            // into principal-derived predicates. Bound only when the app declares scopes, so the SQL
            // producer falls back to its reject-any-scope default everywhere else.
            if (!manifest.scopes().isEmpty()) {
                context.bind(TesseraqlProperties.SCOPE_RESOLVER_BEAN,
                        new io.tesseraql.identity.scope.CompiledScopeResolver(
                                manifest.scopes(), datasourceDialect(manifest.config()),
                                modules.functions()));
            }
            // Analytics file scopes (docs/duckdb.md): ${scope.*} placeholders resolve only when a
            // duckdb datasource is declared; everywhere else the SQL producer's reject-any-placeholder
            // default applies.
            FileScopes fileScopes = FileScopes.fromConfig(appHome, manifest.config());
            if (fileScopes.anyDuckDbDatasource()) {
                context.bind(TesseraqlProperties.FILE_PATH_RESOLVER_BEAN, fileScopes);
            }
            if (security.jwt() != null) {
                context.bind(
                        TesseraqlProperties.JWT_AUTHENTICATOR_BEAN,
                        new JwtAuthenticator(security.jwt()));
            }
            if (security.apiKeys() != null) {
                context.bind(
                        TesseraqlProperties.API_KEY_AUTHENTICATOR_BEAN,
                        new io.tesseraql.security.apikey.ApiKeyAuthenticator(security.apiKeys()));
            }
            if (security.mtls() != null) {
                context.bind(
                        TesseraqlProperties.MTLS_AUTHENTICATOR_BEAN,
                        new io.tesseraql.security.mtls.MtlsAuthenticator(security.mtls()));
            }
            // Spooled exports and large rowsets (design ch. 28.4; docs/deployment.md "Shared
            // export files"): file (node-local default), db (the main database — any node serves
            // the download), or blob (the configured object store, for heavy volumes).
            String tempStoreKind = manifest.config().getString("tesseraql.temp.store")
                    .orElse("file");
            java.nio.file.Path tempScratch = appHome.resolve("work/tmp/tesseraql");
            io.tesseraql.core.spool.TempStore tempStore = switch (tempStoreKind) {
                case "file" -> new io.tesseraql.core.spool.FileTempStore(tempScratch);
                case "db" -> {
                    io.tesseraql.operations.spool.JdbcTempStore jdbcTemp = new io.tesseraql.operations.spool.JdbcTempStore(
                            dataSource, tempScratch,
                            manifest.config().getString("tesseraql.temp.maxBytes")
                                    .map(Long::parseLong)
                                    .orElse(io.tesseraql.operations.spool.JdbcTempStore.DEFAULT_MAX_BYTES));
                    jdbcTemp.ensureSchema();
                    yield jdbcTemp;
                }
                case "blob" -> {
                    io.tesseraql.core.blob.BlobStore blobStore = io.tesseraql.yaml.blob.BlobStores
                            .create(manifest.config(), appHome, modules.loader());
                    if (blobStore instanceof io.tesseraql.core.blob.FileBlobStore) {
                        LOG.warn("tesseraql.temp.store: blob with the local file provider is still"
                                + " node-local; configure tesseraql.object-storage.provider (or use"
                                + " store: db) for multi-node downloads");
                    }
                    yield new io.tesseraql.core.spool.BlobTempStore(blobStore,
                            manifest.config().getString("tesseraql.temp.bucket")
                                    .orElse("tesseraql-temp"));
                }
                default -> throw new io.tesseraql.core.error.TqlException(
                        new io.tesseraql.core.error.TqlErrorCode(
                                io.tesseraql.core.error.TqlDomain.YAML, 1024),
                        "tesseraql.temp.store must be 'file', 'db', or 'blob', got '"
                                + tempStoreKind + "'");
            };
            context.bind(TesseraqlProperties.TEMP_STORE_BEAN, tempStore);

            // The transport this runtime serves on (docs/http-threading.md decisions 1 and 4) is
            // handed to the server at construction below (docs/vertx-native.md decision 6): hosted,
            // the host's instance, ridden and never closed; standalone, the declared options, which
            // are what stop the server from inheriting a default sized for a framework where
            // blocking is the exception.
            final io.vertx.core.Vertx sharedTransport = hostContext == null
                    ? null
                    : hostContext.vertx();
            if (sharedTransport != null) {
                warnOnMemberThreadSizing(manifest.config(),
                        io.tesseraql.yaml.app.ApplicationName.of(manifest.config()));
            }
            // Resolved here, not at server construction: a declared thread count that cannot be a
            // pool refuses the boot while the failure still names the key that caused it.
            final io.vertx.core.VertxOptions standaloneTransportOptions = sharedTransport != null
                    ? null
                    : vertxOptions(manifest.config());

            // SSE endpoints register on the platform's Vert.x router, which exists only once
            // the context (and with it the HTTP server) has started — collected here, run
            // right after context.start() (see SseRoutes for why they are not compiled pipelines).
            java.util.List<Runnable> sseEndpoints = new java.util.ArrayList<>();

            // The framework's own migrations run before any store touches the schema (versioned
            // history per component, Flyway's lock serializing concurrent node startups); the
            // stores' direct bootstrap below stays as the idempotent fallback for embedders.
            // Hosted, the stack-wide security component was migrated once by the host before this
            // runtime started, so it is VALIDATED here instead — failing to start on a mismatch is
            // the wrong-framework-datasource guard, and what refuses a canary expecting a newer
            // schema than the host migrated (docs/stack-architecture.md decision 16).
            if (hostedValidatesFramework) {
                FrameworkMigrations.migrateOperations(dataSource);
                FrameworkMigrations.validateSecurity(frameworkDataSource);
            } else {
                FrameworkMigrations.migrate(dataSource, frameworkDataSource);
            }
            // Browser sessions: "jdbc" by default (docs/contract-bugfixes.md track G) — tql_session
            // shared across all runtime nodes, so a login made on one node resolves on every other
            // (design ch. 11.2) and survives a restart. "memory" is the explicit per-node opt-in
            // for embedders and tests.
            // Constructed after Flyway on purpose: the versioned history owns evolutions like the
            // V2 subject column, and the store's direct ensureSchema stays the tolerated,
            // idempotent fallback for embedders without it.
            SessionStore sessionStore;
            // One reading of the TTL for both stores. It used to be read inside the jdbc branch
            // only, so on the default (memory) store the key was silently inert and sessions never
            // expired at all.
            java.time.Duration sessionTtl = java.time.Duration.ofMillis(
                    io.tesseraql.core.util.Durations.toMillis(
                            manifest.config().getString("tesseraql.sessions.ttl").orElse("12h")));
            // Optional sliding idle window inside the absolute TTL (docs/session-visibility.md);
            // unset keeps the pre-existing absolute-only behavior.
            java.time.Duration sessionIdle = manifest.config()
                    .getString("tesseraql.sessions.idleTimeout")
                    .map(value -> java.time.Duration.ofMillis(
                            io.tesseraql.core.util.Durations.toMillis(value)))
                    .orElse(null);
            // Declared per-subject session cap, evict-oldest (docs/session-visibility.md
            // addendum); single-session policy is maxPerSubject: 1. Unset = unlimited.
            Integer sessionCap = manifest.config().getString("tesseraql.sessions.maxPerSubject")
                    .map(Integer::parseInt).orElse(null);
            if ("jdbc".equalsIgnoreCase(
                    manifest.config().getString("tesseraql.sessions.store").orElse("jdbc"))) {
                io.tesseraql.security.session.JdbcSessionStore jdbcSessions = new io.tesseraql.security.session.JdbcSessionStore(
                        frameworkDataSource, sessionTtl, sessionIdle, sessionCap,
                        io.tesseraql.security.session.SessionStore.DEFAULT_COOKIE_NAME);
                jdbcSessions.ensureSchema();
                sessionStore = jdbcSessions;
            } else {
                sessionStore = new io.tesseraql.security.session.InMemorySessionStore(
                        io.tesseraql.security.session.SessionStore.DEFAULT_COOKIE_NAME, sessionTtl,
                        sessionIdle, sessionCap);
            }
            context.bind(TesseraqlProperties.SESSION_STORE_BEAN, sessionStore);
            // Keyed credential throttle (docs/credential-throttle.md): on by default with
            // generous failures-only budgets; enabled: false is the visible test/dev escape.
            io.tesseraql.security.throttle.CredentialThrottle credentialThrottle = new io.tesseraql.security.throttle.CredentialThrottle(
                    new io.tesseraql.security.throttle.CredentialThrottle.Config(
                            manifest.config()
                                    .getBoolean("tesseraql.security.credentialThrottle.enabled",
                                            true),
                            manifest.config()
                                    .getString(
                                            "tesseraql.security.credentialThrottle.loginAttempts")
                                    .map(Integer::parseInt).orElse(10),
                            java.time.Duration.ofMillis(io.tesseraql.core.util.Durations.toMillis(
                                    manifest.config()
                                            .getString(
                                                    "tesseraql.security.credentialThrottle.loginWindow")
                                            .orElse("15m"))),
                            manifest.config()
                                    .getString(
                                            "tesseraql.security.credentialThrottle.addressAttempts")
                                    .map(Integer::parseInt).orElse(100),
                            java.time.Duration.ofMillis(io.tesseraql.core.util.Durations.toMillis(
                                    manifest.config()
                                            .getString(
                                                    "tesseraql.security.credentialThrottle.addressWindow")
                                            .orElse("15m")))),
                    effectiveMeter);
            context.bind(TesseraqlProperties.CREDENTIAL_THROTTLE_BEAN,
                    credentialThrottle);
            // A run is stamped with the node that owns it (docs/audit-hardening.md Decision 6). The
            // default is derived from host and pid so two replicas of one image are distinguishable
            // without anybody configuring anything.
            JobRepository jobRepository = new JobRepository(dataSource,
                    io.tesseraql.operations.batch.NodeIdentity.resolve(manifest.config()
                            .getString("tesseraql.batch.nodeId").orElse(null)));
            jobRepository.ensureSchema();
            JdbcIdempotencyStore idempotencyStore = new JdbcIdempotencyStore(dataSource);
            idempotencyStore.ensureSchema();
            context.bind(TesseraqlProperties.IDEMPOTENCY_STORE_BEAN, idempotencyStore);
            JdbcOutboxStore outboxStore = new JdbcOutboxStore(dataSource);
            outboxStore.ensureSchema();
            context.bind(TesseraqlProperties.OUTBOX_STORE_BEAN, outboxStore);
            // The opt-in business-route audit log (roadmap Phase 45): who called what, with the
            // declared decision-relevant params, per-app scoped like every other ops table.
            //
            // On the BUSINESS datasource, not tesseraql.framework.datasource
            // (docs/app-isolation-model.md): this store writes once per audited request — business
            // request rate — and that key exists to keep a long-running business query from
            // starving login of a connection. Business-rate writes on the login pool defeat it.
            // It also keeps the ops console reading one database: every other page it serves
            // (jobs, executions, outbox, events) is bucket-1 and pinned here.
            io.tesseraql.operations.audit.JdbcRouteAuditStore routeAuditStore = null;
            if (manifest.config().getString("tesseraql.audit.routes.enabled")
                    .map(Boolean::parseBoolean).orElse(false)) {
                routeAuditStore = new io.tesseraql.operations.audit.JdbcRouteAuditStore(
                        dataSource);
                routeAuditStore.ensureSchema();
                context.bind(TesseraqlProperties.ROUTE_AUDIT_SINK_BEAN,
                        routeAuditStore);
            }
            // The account surface (roadmap Phase 48): the managed per-user preference store, plus
            // the marker bean the shared shell keys the settings link off. Mounted with the bundled
            // account app (the auth-ui precedent) — AccountAppProvider.enabled is the one source of
            // truth for both the app mount and this wiring. One final reference, so the account
            // service providers registered below can capture it.
            final io.tesseraql.core.account.PreferenceStore preferences = AccountAppProvider
                    .enabled(manifest.config())
                            ? accountPreferenceStore(frameworkDataSource)
                            : null;
            final io.tesseraql.core.account.ShortcutStore shortcuts;
            if (preferences != null) {
                context.bind(TesseraqlProperties.PREFERENCE_STORE_BEAN, preferences);
                context.bind(TesseraqlProperties.ACCOUNT_SURFACE_BEAN, Boolean.TRUE);
                // Pins and recents (roadmap Phase 51) ride the account surface: the sidebar's
                // Pinned group reads through the same wrapper the mutations refresh.
                io.tesseraql.operations.account.JdbcShortcutStore jdbcShortcuts = new io.tesseraql.operations.account.JdbcShortcutStore(
                        frameworkDataSource);
                jdbcShortcuts.ensureSchema();
                shortcuts = new io.tesseraql.core.account.CachingShortcutStore(jdbcShortcuts);
                context.bind(TesseraqlProperties.SHORTCUT_STORE_BEAN, shortcuts);
            } else {
                shortcuts = null;
            }
            // The operator's default page theme (roadmap Phase 48): the shell's fallback when the
            // user has no stored or cookie choice. Values outside the enum are ignored.
            String uiTheme = manifest.config().getString("tesseraql.ui.theme").orElse(null);
            if ("light".equals(uiTheme) || "dark".equals(uiTheme)) {
                context.bind(TesseraqlProperties.UI_THEME_BEAN, uiTheme);
            }
            // The app's UI defaults (docs/hypermedia-ui.md "UI defaults"): the neutral ramp and
            // control density every shell renders. The renderer defaults to slate + compact; only
            // a validated operator override is bound here (values outside the kit's enums are
            // ignored, like the theme).
            String uiNeutral = manifest.config().getString("tesseraql.ui.neutral").orElse(null);
            if (uiNeutral != null
                    && java.util.Set.of("neutral", "slate", "zinc", "stone").contains(uiNeutral)) {
                context.bind(TesseraqlProperties.UI_NEUTRAL_BEAN, uiNeutral);
            }
            String uiDensity = manifest.config().getString("tesseraql.ui.density").orElse(null);
            if (uiDensity != null
                    && java.util.Set.of("comfortable", "compact", "dense").contains(uiDensity)) {
                context.bind(TesseraqlProperties.UI_DENSITY_BEAN, uiDensity);
            }
            // Whether the password form (and so self-service password change) is on: the same
            // flag the bundled login page reads (roadmap Phase 48 slice 4).
            final boolean passwordLoginEnabled = manifest.config()
                    .getString("tesseraql.console.login.password.enabled")
                    .map(Boolean::parseBoolean).orElse(true);
            // The locales the account surface's language picker offers — the same negotiated set
            // every route resolves against (Phase 22 semantics, one source of truth).
            final List<String> accountLocales = io.tesseraql.yaml.i18n.I18nSettings
                    .from(manifest.config(), appHome).supportedTags();
            // Inbound-webhook replay protection (roadmap Phase 26): a delivery is processed at most
            // once on any node sharing this database.
            io.tesseraql.operations.webhook.JdbcWebhookReplayStore webhookReplayStore = new io.tesseraql.operations.webhook.JdbcWebhookReplayStore(
                    dataSource);
            webhookReplayStore.ensureSchema();
            context.bind(TesseraqlProperties.WEBHOOK_REPLAY_STORE_BEAN,
                    webhookReplayStore);
            // Messaging channel event log backing the built-in pg-notify transport (roadmap Phase 27):
            // the durable bus a publish: relay writes to and a queue-consume route claims from.
            io.tesseraql.operations.messaging.JdbcEventChannelStore eventChannelStore = new io.tesseraql.operations.messaging.JdbcEventChannelStore(
                    dataSource);
            eventChannelStore.ensureSchema();
            context.bind(TesseraqlProperties.EVENT_CHANNEL_STORE_BEAN, eventChannelStore);
            // Managed org-unit hierarchy for data scoping (roadmap Phase 29 slice 2): provisioned and
            // bound only in `managed` mode, so an app that owns its own org tables (the `app` default)
            // gets no managed schema. A subtree scope joins tql_org_closure; this store maintains it.
            if (io.tesseraql.yaml.org.OrgUnitSettings.from(manifest.config()).managed()) {
                io.tesseraql.operations.org.JdbcOrgUnitStore orgUnitStore = new io.tesseraql.operations.org.JdbcOrgUnitStore(
                        dataSource);
                orgUnitStore.ensureSchema();
                context.bind(TesseraqlProperties.ORG_UNIT_STORE_BEAN, orgUnitStore);
            }
            // Managed approval-workflow state (roadmap Phase 28 slice 1): provisioned and bound when any
            // declared workflow runs in `managed` mode (the app-wide default or a per-workflow
            // override); `app` mode keeps state in the business table's column and binds no store (the
            // transition route carries its own).
            if (workflowsNeedManagedStore(manifest)) {
                io.tesseraql.operations.workflow.JdbcWorkflowStore workflowStore = new io.tesseraql.operations.workflow.JdbcWorkflowStore(
                        dataSource);
                workflowStore.ensureSchema();
                context.bind(TesseraqlProperties.WORKFLOW_STORE_BEAN, workflowStore);
            }
            // Managed approval-workflow task inbox (roadmap Phase 28 slice 2): provisioned and bound when
            // any transition assigns a task, independent of where the workflow keeps its state, so one
            // inbox spans managed-state and app-state workflows alike.
            WorkflowSweeper workflowSweeper = null;
            if (workflowsAssignTasks(manifest)) {
                io.tesseraql.operations.workflow.JdbcWorkflowTaskStore taskStore = new io.tesseraql.operations.workflow.JdbcWorkflowTaskStore(
                        dataSource);
                taskStore.ensureSchema();
                context.bind(TesseraqlProperties.WORKFLOW_TASK_STORE_BEAN, taskStore);
                // Standing absence rules (roadmap Phase 52): built wherever the task inbox is -
                // every assignee funnel resolves through this one store, one hop, never a chain.
                io.tesseraql.operations.workflow.JdbcDelegationStore delegationStore = new io.tesseraql.operations.workflow.JdbcDelegationStore(
                        dataSource);
                delegationStore.ensureSchema();
                context.bind(TesseraqlProperties.DELEGATION_STORE_BEAN,
                        delegationStore);
                // Deadline escalation (roadmap Phase 28 slice 3): a sweeper reassigns overdue tasks per
                // each state's onBreach.reassign resolver, recording history through the managed store.
                List<WorkflowSweeper.Rule> rules = buildSweeperRules(manifest,
                        datasourceDialect(manifest.config()), modules.functions());
                if (!rules.isEmpty()) {
                    io.tesseraql.core.workflow.WorkflowStore historyStore = context.lookup(
                            TesseraqlProperties.WORKFLOW_STORE_BEAN,
                            io.tesseraql.core.workflow.WorkflowStore.class);
                    workflowSweeper = new WorkflowSweeper(rules, taskStore, historyStore,
                            outboxStore,
                            io.tesseraql.yaml.app.ApplicationName.of(manifest.config()),
                            dataSource, delegationStore);
                    context.bind(TesseraqlProperties.WORKFLOW_SWEEPER_BEAN,
                            workflowSweeper);
                }
            }
            io.tesseraql.yaml.messaging.MessagingChannels messagingChannels = io.tesseraql.yaml.messaging.MessagingChannels
                    .load(manifest.config());
            // Managed document-number sequences for command steps (roadmap Phase 18).
            io.tesseraql.operations.sequence.JdbcDocumentSequences documentSequences = new io.tesseraql.operations.sequence.JdbcDocumentSequences(
                    dataSource);
            documentSequences.ensureSchema();
            context.bind(TesseraqlProperties.DOCUMENT_SEQUENCES_BEAN, documentSequences);
            // Asynchronous file imports/exports (design ch. 28); codecs arrive via ServiceLoader, so
            // adding the optional tesseraql-excel module to the classpath is the whole install.
            io.tesseraql.operations.files.JdbcFileTransferService fileTransfers = new io.tesseraql.operations.files.JdbcFileTransferService(
                    jobRepository,
                    tempStore, dataSource,
                    io.tesseraql.core.files.FileCodecs.discover(modules.loader()),
                    modules.functions());
            // The same bound routes and commands run under: an export query or an after-SQL
            // statement held a pooled connection for as long as the driver allowed.
            fileTransfers
                    .sqlTimeoutSeconds(manifest.config().getString("tesseraql.sql.timeoutSeconds")
                            .map(Integer::parseInt).orElse(30));
            fileTransfers.ensureSchema();
            context.bind(TesseraqlProperties.FILE_TRANSFER_BEAN, fileTransfers);
            // Transfer retention (docs/file-transfers.md): opt-in, because nothing expires by
            // default — the DuckLake stance, retention policy belongs to the app. When set,
            // produced files older than retentionDays are reclaimed on a periodic sweep.
            int transferRetentionDays = manifest.config()
                    .getString("tesseraql.transfers.retentionDays")
                    .map(Integer::parseInt).orElse(0);
            if (transferRetentionDays > 0) {
                try {
                    new TransferRetentionSweep(fileTransfers,
                            transferRetentionDays,
                            io.tesseraql.core.util.Durations.toMillis(manifest.config()
                                    .getString("tesseraql.transfers.sweepInterval").orElse("1h")),
                            java.time.Clock.systemDefaultZone())
                            .schedule(Schedules.of(context));
                } catch (Exception ex) {
                    throw new IllegalStateException(
                            "Failed to wire transfer retention: " + ex.getMessage(), ex);
                }
            }
            // Managed attachments (roadmap Phase 30): provisioned and bound when the app declares
            // attachment documents in `managed` mode (the default). The blob store is selected by
            // tesseraql.object-storage.provider — the local file store by default, or S3 from the opt-in
            // tesseraql-s3 module (slice 2) — and the metadata table backs the synthesized
            // upload/list/download routes; an app with no attachments gets neither.
            if (attachmentsNeedManagedStore(manifest)) {
                io.tesseraql.core.blob.BlobStore blobStore = io.tesseraql.yaml.blob.BlobStores
                        .create(
                                manifest.config(), appHome, modules.loader());
                context.bind(TesseraqlProperties.BLOB_STORE_BEAN, blobStore);
                io.tesseraql.operations.attachment.JdbcAttachmentStore attachmentStore = new io.tesseraql.operations.attachment.JdbcAttachmentStore(
                        dataSource);
                attachmentStore.ensureSchema();
                context.bind(TesseraqlProperties.ATTACHMENT_STORE_BEAN, attachmentStore);
                // Scan-passed attachments become owner-gated ${dataset.*} references on duckdb
                // datasources, bridged into the fence's one spool directory (docs/duckdb.md).
                fileScopes.wireDatasets(attachmentStore, new DatasetSpool(blobStore,
                        DuckDbDatasources.spoolDirectory(manifest.config(), appHome)));
                // Malware scanning (roadmap Phase 30 slice 3): the installed AttachmentScanner (the
                // no-op default unless a scanner module is on the classpath) runs on upload; an infected
                // object is quarantined or deleted per tesseraql.attachments.scan.onInfected and is never
                // served (the download gate refuses a non-clean object).
                String onInfected = io.tesseraql.yaml.attachment.AttachmentSettings
                        .from(manifest.config()).onInfected();
                io.tesseraql.core.scan.AttachmentScanner scanner = io.tesseraql.core.scan.AttachmentScanners
                        .discover();
                // Asynchronous scanning (docs/attachments.md): uploads record pending and return
                // immediately; the sweep claims, scans, and records the verdict — the existing
                // non-clean download gate holds pending objects back, so fail-closed is intact.
                boolean asyncScan = "async".equalsIgnoreCase(manifest.config()
                        .getString("tesseraql.attachments.scan.mode").orElse("sync"));
                context.bind(TesseraqlProperties.ATTACHMENT_SERVICE_BEAN,
                        new io.tesseraql.operations.attachment.DefaultAttachmentService(blobStore,
                                attachmentStore, scanner, onInfected, asyncScan));
                if (asyncScan) {
                    io.tesseraql.operations.attachment.AttachmentScanSweeper scanSweeper = new io.tesseraql.operations.attachment.AttachmentScanSweeper(
                            blobStore, attachmentStore, scanner, onInfected,
                            manifest.config().getString("tesseraql.attachments.scan.maxAttempts")
                                    .map(Integer::parseInt).orElse(5),
                            io.tesseraql.core.util.Durations.parse(manifest.config()
                                    .getString("tesseraql.attachments.scan.lease").orElse("5m")),
                            100);
                    long scanPeriod = io.tesseraql.core.util.Durations.toMillis(manifest.config()
                            .getString("tesseraql.attachments.scan.interval").orElse("10s"));
                    try {
                        Schedules.of(context).every("system.attachments.scan", scanPeriod,
                                scanSweeper::sweep);
                    } catch (RuntimeException ex) {
                        // Without the sweep, async uploads would stay pending forever - fail the
                        // boot loudly rather than hold every attachment back silently.
                        throw new IllegalStateException(
                                "Failed to start the attachment scan sweep", ex);
                    }
                }
            }
            // The outbound egress policy (roadmap Phase 26): deny-by-default allow-list, named
            // credentials, timeouts. One instance gates every framework-issued outbound call —
            // httpCall steps here and the Studio copilot endpoint below.
            final io.tesseraql.yaml.http.HttpOutbound httpOutbound = io.tesseraql.yaml.http.HttpOutbound
                    .load(manifest.config());
            // One outbound HTTP client gates every framework-issued call: httpCall job steps
            // and query routes' http: sources (docs/connectors.md) share the allow-list, the
            // named credentials, the timeouts, and the per-host circuit breaker.
            io.tesseraql.operations.http.HttpCallClient httpCallClient = new io.tesseraql.operations.http.HttpCallClient(
                    httpOutbound, manifest.config(), tracer, effectiveMeter);
            // push: pipeline steps deliver a produced transfer to a partner drop — local, or
            // SFTP/FTPS under the push policy block's deny-by-default allow-list
            // (docs/analytics-experience.md).
            @SuppressWarnings("resource") // holds nothing between deliveries; closed with the runtime
            FilePushService filePush = new FilePushService(
                    io.tesseraql.yaml.connectors.FileConnectors.push(manifest.config()), appHome);
            JobExecutor jobExecutor = new JobExecutor(jobRepository, tempStore, slowSqlLog, tracer,
                    modules.functions())
                    // A running job says so on a clock, and overlap: skip believes a previous run
                    // only while its owner keeps saying it (docs/audit-hardening.md Decision 6).
                    .heartbeatInterval(io.tesseraql.core.util.Durations.parse(manifest.config()
                            .getString("tesseraql.batch.heartbeat.interval").orElse("30s")))
                    .livenessWindow(io.tesseraql.core.util.Durations.parse(manifest.config()
                            .getString("tesseraql.batch.heartbeat.livenessWindow").orElse("5m")))
                    // Every finished run counts on the exposition (docs/jobs.md "Observing
                    // runs"): tesseraql.job.runs by job/app/status + a duration histogram.
                    .meter(effectiveMeter)
                    // The same bound routes and commands run under: a batch statement held a pooled
                    // connection for as long as the driver would let it, which on a job is the
                    // longest anything goes unnoticed — nobody is waiting for the response.
                    .sqlTimeoutSeconds(manifest.config().getString("tesseraql.sql.timeoutSeconds")
                            .map(Integer::parseInt).orElse(30))
                    // A job has no request to read configuration from, so a step's default row
                    // ceiling arrives the same way its timeout does (docs/export-pipeline.md, dec. 7).
                    .resultBounds(
                            manifest.config().getString("tesseraql.resultMaterialization.maxRows")
                                    .map(Integer::parseInt).orElse(10_000),
                            manifest.config()
                                    .getString("tesseraql.resultMaterialization.onOverflow")
                                    .orElse("fail"))
                    // A batch read step may extract from another declared connector and load into
                    // the job's (docs/unified-sources.md decision 19).
                    .connectors(dataSources::get)
                    .notificationOutbox(outboxStore)
                    // Recipient-aware notify steps honor per-user opt-outs (roadmap Phase 48).
                    .preferenceStore(preferences)
                    // Outbound REST for httpCall pipeline steps (roadmap Phase 26): deny-by-default
                    // egress, secret-managed credentials, timeouts, and circuit breaking from config.
                    .httpCall(httpCallClient)
                    // export: pipeline steps write through the same transfer machinery HTTP
                    // file-export routes use (docs/analytics-experience.md track 3).
                    .fileTransfers(fileTransfers, appHome)
                    .filePush(filePush::push)
                    // ETL job SQL on a duckdb datasource resolves ${scope.*} placeholders through the
                    // same declared file scopes as routes (docs/duckdb.md).
                    .filePathResolvers(datasourceName -> datasourceName != null
                            && DuckDbDatasources.isDuckDb(manifest.config(), datasourceName)
                                    ? (channel, scopeName, suffix, ctx) -> fileScopes.resolve(
                                            datasourceName, channel, scopeName, suffix, ctx)
                                    : io.tesseraql.core.sql.FilePathResolver.UNSUPPORTED);
            // Cluster-scoped rate limits (docs/deployment.md): the lease ledger exists exactly
            // when a route declares rateLimit.scope: cluster; limiters reach it via the registry.
            if (routeShaped(manifest).anyMatch(definition -> definition.admission() != null
                    && definition.admission().rateLimit() != null
                    && definition.admission().rateLimit().isCluster())) {
                io.tesseraql.operations.rate.JdbcRateLeaseStore rateLeases = new io.tesseraql.operations.rate.JdbcRateLeaseStore(
                        frameworkDataSource);
                rateLeases.ensureSchema();
                context.bind(TesseraqlProperties.RATE_BUDGET_BEAN, rateLeases);
            }
            // An enrichment's http: reference calls through the same gateway, so it counts toward
            // binding it — otherwise the reference fails at request time with no route-level http:
            // anywhere in the app (docs/lookups.md).
            if (routeShaped(manifest).anyMatch(definition -> definition.sources().values().stream()
                    .anyMatch(binding -> binding.isHttp()
                            || binding.enrich().values().stream()
                                    .anyMatch(enrich -> enrich.http() != null)))) {
                context.bind(TesseraqlProperties.OUTBOUND_GATEWAY_BEAN,
                        outboundGateway(httpCallClient));
            }
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
                    ? new LiveStreams(
                            manifest.config().getString("tesseraql.live.maxPerSubject")
                                    .map(Integer::parseInt)
                                    .orElse(LiveStreams.DEFAULT_MAX_PER_SUBJECT),
                            manifest.config().getString("tesseraql.live.maxTotal")
                                    .map(Integer::parseInt).orElse(LiveStreams.DEFAULT_MAX_TOTAL))
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
                context.bind(TesseraqlProperties.INBOX_STORE_BEAN, inboxStore);
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
                    context.bind(TesseraqlProperties.TOPIC_BUS_BEAN, postgres
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
                sseEndpoints.add(() -> LiveEvents.register(context, liveStreams,
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
                        frameworkDataSource);
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
                            alertChannel, "ops.jobFailure", payload,
                            jobApp == null ? "app" : jobApp));
                });
            }
            Map<String, JobFile> jobs = new LinkedHashMap<>();
            manifest.jobs().forEach(job -> jobs.put(job.definition().id(), job));
            // Required, not defaulted: the name scopes outbox claims, job ownership and
            // tql.ops.view.<name>, so a shared fallback is a shared identity (io.tesseraql.yaml.app
            // .ApplicationName).
            String appName = io.tesseraql.yaml.app.ApplicationName.of(manifest.config());
            // The owning app per job id (main app jobs default), so execution records are tagged with
            // the app that declared the job, not just the hosting runtime (design ch. 26, 32).
            Map<String, String> jobOwners = new LinkedHashMap<>();
            // Every app this runtime hosts (main + mounted), scoping outbox claims (design ch. 39).
            java.util.Set<String> hostedApps = new java.util.LinkedHashSet<>();
            hostedApps.add(appName);

            // The manual/scheduled runner, with light after-chaining
            // (docs/batch-platform.md track D; docs/boot-phases.md slice 3).
            OpsActions.JobRunner jobRunner = JobRunners.chained(jobs, jobOwners, appName,
                    dataSource, dataSources, manifest.config(), tenantDataSources, jobExecutor);

            // Business-day calendars (docs/batch-platform.md track B): loaded at startup so a
            // broken calendars/ dir fails fast. One decision helper answers both the scheduling
            // gate and the console's next-counting preview, so the two can never drift.
            io.tesseraql.yaml.calendar.Calendars calendars = io.tesseraql.yaml.calendar.Calendars
                    .load(appHome, new io.tesseraql.yaml.SimpleYamlParser());
            // A calendar that cannot be resolved fails open at fire time, so the skip is recorded
            // here and alerted by the dashboard (docs/silent-tolerance.md O5).
            io.tesseraql.opsui.CalendarStatus calendarStatus = new io.tesseraql.opsui.CalendarStatus();
            CalendarDecisions calendarDecisions = new CalendarDecisions(calendars, dataSource,
                    dataSources).status(calendarStatus);

            // Per-node poll-source health (docs/poll-source-status.md): fed by the polling
            // wiring below, read by the dashboard's alerts and the console's jobs page.
            io.tesseraql.opsui.PollSourceStatus pollSourceStatus = new io.tesseraql.opsui.PollSourceStatus();
            context.bind("tesseraqlPollSourceStatus", pollSourceStatus);
            // The port actually bound, learned after the server starts: the requested one, or the
            // kernel's pick when 0 was asked for.
            int boundPort = port;
            // The effective tracer, not the supplied one: with OTLP configured the supplied tracer
            // is wrapped in a composite, and reading past it left the console's trace pages empty
            // in exactly the deployments that had the most telemetry (docs/audit-hardening.md
            // Decision 7).
            io.tesseraql.opsui.OpsDashboard opsDashboard = OpsDashboards.assemble(manifest.config(),
                    jobRepository, lanes, slowSqlLog, effectiveTracer,
                    pinningMonitor, outboxStore, eventChannelStore,
                    pollSourceStatus, calendarStatus, dataSource, dataSources);
            // The app's db/migration runs before anything queries its schema: fresh installs,
            // upgrades and canary activations all converge here (design ch. 31, 32).
            // The history key is the application's own declaration, not this runtime's idea of its
            // name, so `tesseraql migrate` and the Maven goal converge on the same table.
            // Mounted apps below keep passing their mount name: they share this config object, and
            // resolving from it would key all five bundled surfaces to one history table.
            AppMigrations.migrate(
                    io.tesseraql.yaml.migration.SchemaHistoryName.of(manifest.config()),
                    appHome, manifest.config(), dataSource, tenantDataSources, dataSources::get);
            TesseraqlHttpServer httpServer = new TesseraqlHttpServer(context, "0.0.0.0", port,
                    sharedTransport, standaloneTransportOptions);
            context.addService(httpServer);
            new RouteCompiler().appName(appName)
                    .functions(modules.functions()).compile(context, manifest);
            // Mounted apps (jar-bundled system apps and config-listed directories, design ch. 32)
            // are plain yaml/sql/template trees compiled exactly like the main app. They load before
            // the MCP endpoint is wired so their MCP surface joins the main app's on one endpoint and
            // the conflict check spans every hosted app.
            List<SystemApps.MountedApp> mountedApps = SystemApps.load(manifest.config(), appHome,
                    hostedMember
                            ? java.util.Set.of("ops-console", "auth-ui", "account", "iam-admin",
                                    "studio")
                            : java.util.Set.of());
            SystemApps.requireNoRouteConflicts(manifest, mountedApps);
            for (SystemApps.MountedApp mounted : mountedApps) {
                // Mounted apps migrate their own schema (per-app history table) before serving.
                AppMigrations.migrate(mounted.name(), mounted.manifest().appHome(),
                        manifest.config(), dataSource, tenantDataSources, dataSources::get);
                new RouteCompiler().appName(mounted.name())
                        .functions(modules.functions()).compile(context, mounted.manifest());
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
            // Where this runtime's pages link the system surfaces (docs/stack-shells.md
            // structural decision 2): the ops console is the stack's, so a hosted member links
            // the origin scope — the one origin-absolute URL a member page carries — while the
            // unhosted boot links its own mounted copy. Studio and IAM Admin link only where
            // they are mounted, so the shell never links a 404.
            java.util.Map<String, String> systemNav = new LinkedHashMap<>();
            if (hostedMember) {
                systemNav.put("consoleHref", "/_tesseraql/ops/console");
            } else if (hostedApps.contains("ops-console")) {
                systemNav.put("consoleHref", basePath + "/_tesseraql/ops/console");
            }
            if (hostedMember) {
                // The workshop is the stack's, at the origin scope — and only where the host
                // says a workshop exists at all (docs/studio-shell.md structural decision 1),
                // so a production member's shell carries no Studio link.
                if (hostContext.workshop()) {
                    systemNav.put("studioHref", "/_tesseraql/studio");
                }
            } else if (hostedApps.contains("studio")) {
                systemNav.put("studioHref", basePath + "/_tesseraql/studio");
            }
            if (hostedMember) {
                // IAM Admin is the stack's, mounted once at the origin scope (docs/stack-shells.md
                // structural decision 3) — the same origin-absolute shape as the console link.
                systemNav.put("iamHref", "/_tesseraql/admin/users");
            } else if (hostedApps.contains("iam-admin")) {
                systemNav.put("iamHref", basePath + "/_tesseraql/admin/users");
            }
            context.bind(TesseraqlProperties.SYSTEM_NAV_BEAN,
                    java.util.Collections.unmodifiableMap(systemNav));
            if (hostedMember) {
                // The one topology signal a hosted member's request handling reads: the login
                // bounce and the account-surface links switch to the origin scope on its
                // presence, and the tql.app.use fence refuses on its value
                // (docs/stack-shells.md structural decision 3).
                context.bind(TesseraqlProperties.STACK_MEMBER_BEAN, appName);
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
                        RoutePipelines.of(context));
                // The MCP transport gate (docs/audit-hardening.md decision 2, delivered with
                // the authorization-server campaign's resource slice): tesseraql.mcp.auth is
                // public by default — nothing changes without opting in — and `bearer` demands
                // a token whose audience is THIS surface's canonical resource identifier,
                // derived from the address unless declared. The 401 carries the RFC 9728
                // resource_metadata challenge whenever the resource is absolute, because the
                // measured clients discover through the challenge first.
                String mcpAuth = manifest.config().getString("tesseraql.mcp.auth")
                        .orElse("public");
                io.tesseraql.mcp.McpAuthenticator mcpGate = null;
                String mcpChallenge = "Bearer";
                if ("bearer".equals(mcpAuth)) {
                    if (security.jwt() == null) {
                        throw new io.tesseraql.core.error.TqlException(
                                new io.tesseraql.core.error.TqlErrorCode(
                                        io.tesseraql.core.error.TqlDomain.MCP, 4262),
                                "tesseraql.mcp.auth is bearer, but no JWT validation is"
                                        + " configured — the gate has nothing to verify a"
                                        + " token against");
                    }
                    String origin = hostContext == null ? null : hostContext.externalOrigin();
                    String mcpResource = manifest.config()
                            .getString("tesseraql.mcp.resource")
                            .orElse((origin == null ? "" : origin)
                                    + (basePath == null ? "" : basePath) + "/_tesseraql/mcp");
                    io.tesseraql.security.SecurityConfig.JwtConfig gate = withAudience(
                            security.jwt(), mcpResource);
                    io.tesseraql.security.jwt.JwtAuthenticator mcpJwt = new io.tesseraql.security.jwt.JwtAuthenticator(
                            gate);
                    mcpGate = mcpJwt::authenticate;
                    if (origin != null) {
                        mcpChallenge = "Bearer resource_metadata=\"" + origin
                                + "/.well-known/oauth-protected-resource"
                                + (basePath == null ? "" : basePath) + "/_tesseraql/mcp\"";
                    }
                } else if (!"public".equals(mcpAuth)) {
                    throw new io.tesseraql.core.error.TqlException(
                            new io.tesseraql.core.error.TqlErrorCode(
                                    io.tesseraql.core.error.TqlDomain.MCP, 4262),
                            "tesseraql.mcp.auth '" + mcpAuth + "' is not served at the"
                                    + " transport gate yet — public and bearer are; the"
                                    + " per-primitive auth:/policy: continue underneath"
                                    + " either");
                }
                new McpRoutes(
                        new io.tesseraql.mcp.McpHttpHandler(mcpServer, mcpGate,
                                mcpChallenge))
                        .install(context);
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
            // The surfaces that write their own responses — static assets and the SSE streams —
            // are the ones no compiled route covers, so they read the block from the registry.
            context.bind(TesseraqlProperties.RESPONSE_HEADERS_BEAN,
                    io.tesseraql.yaml.config.ResponseHeaderDefaults.from(manifest.config())
                            .headers());
            // Served on the router rather than as a Camel route (docs/http-threading.md
            // decision 6): an asset no longer takes a worker, and a file is streamed rather than
            // read into the heap. Installed after the context starts, like the SSE endpoints,
            // because the router does not exist before then.
            java.nio.file.Path assetRoot = appHome.resolve("assets");
            java.util.Map<String, java.nio.file.Path> assetTrees = Map.copyOf(appAssets);
            ClientMessages clientMessages = new ClientMessages(appHome,
                    manifest.config().getString("tesseraql.i18n.defaultLocale").orElse("en"));
            sseEndpoints.add(() -> AssetRoutes.install(context, assetRoot, assetTrees,
                    clientMessages));
            // Liveness and readiness, answered on the router off the roll-up the dashboard holds
            // (docs/http-threading.md decision 3): the surface that has to be answerable when
            // nothing else is must not need the resource everything else is waiting for.
            io.tesseraql.opsui.OpsDashboard health = opsDashboard;
            sseEndpoints.add(() -> HealthRoutes.install(context, health));
            // Compiled routes, served on the router off the worker pool (docs/http-edge.md
            // decision 1). Mounted ahead of the remaining hand-written surfaces rather than
            // instead of them: a
            // request this adapter does not reproduce faithfully is handed back, and the route
            // model is unchanged either way.
            sseEndpoints.add(() -> context.bind(RouteEdge.BEAN,
                    RouteEdge.install(context)));
            // The ops API needs each job's owning app so per-app scope can gate listing and runs.
            Map<String, String> ownedJobs = new LinkedHashMap<>();
            jobs.keySet().forEach(id -> ownedJobs.put(id, jobOwners.getOrDefault(id, appName)));
            // The Prometheus scrape endpoint is opt-in and bearer-gated by default; a
            // cluster-internal scraper may opt out of auth explicitly (roadmap Phase 45).
            OperationsRoutes.MetricsSettings metricsSettings = new OperationsRoutes.MetricsSettings(
                    manifest.config().getString("tesseraql.metrics.enabled")
                            .map(Boolean::parseBoolean).orElse(false),
                    manifest.config().getString("tesseraql.metrics.unauthenticated")
                            .map(Boolean::parseBoolean).orElse(false),
                    aggregatingMeter, pollSourceStatus,
                    new io.tesseraql.opsui.RuntimeMetrics(() -> poolStats(dataSources)));
            Map<String, io.tesseraql.yaml.model.JobDefinition> jobDefinitions = new LinkedHashMap<>();
            jobs.forEach((id, jobFile) -> jobDefinitions.put(id, jobFile.definition()));
            // What this runtime serves: the host app plus anything mounted into it. The ops
            // tables live in a business database several runtimes may share, so the ops surface
            // scopes to its own apps before the caller's grants narrow it further.
            java.util.Set<String> servedApps = new java.util.LinkedHashSet<>();
            servedApps.add(appName);
            mountedApps.forEach(mounted -> servedApps.add(mounted.name()));
            // The find/scope/act cores both operations faces call: the JSON routes below and
            // the console's ops.* providers shape the same actions differently.
            OpsActions opsActions = new OpsActions(outboxStore, eventChannelStore,
                    jobRepository, jobRunner, ownedJobs, appName, servedApps);
            new OperationsRoutes(
                    opsActions, jobRepository, ownedJobs, jobDefinitions, opsDashboard,
                    metricsSettings, routeAuditStore, fileTransfers).install(context);
            // Service providers expose non-SQL runtime state to mounted yaml/template apps
            // (the bundled ops-console and studio apps render these, design ch. 26.11, 16,
            // 47). The ops.*, account.* and session/credential providers register from
            // their own class (docs/boot-phases.md slice 2).
            io.tesseraql.core.service.ServiceProviders serviceProviders = OpsAccountProviders
                    .register(new OpsAccountProviders.Deps(opsActions, opsDashboard,
                            routeAuditStore, manifest, preferences, shortcuts,
                            optOutChannels, accountLocales, inboxStore, sessionStore,
                            credentialTokens, inviteTtl, inviteEnabled,
                            passwordLoginEnabled, context, appHome, appName, inviteUrl,
                            inviteChannel, jobs, jobOwners, jobRepository, outboxStore,
                            fileTransfers, calendarDecisions, pollSourceStatus));
            // The portal's provider, only where the host handed this runtime the member list —
            // i.e. only on the stack surface runtime (docs/root-portal.md).
            if (stackMembers != null) {
                PortalProviders.register(serviceProviders, stackMembers, context);
            }
            // The per-application grant views and the whole iam.* administration
            // surface (docs/boot-phases.md slice 1) - registered wherever iam-admin
            // mounts: the surface runtime (the member list) or the unhosted boot.
            IamAdminProviders.register(serviceProviders, context, manifest, appName,
                    stackMembers, hostContext == null);
            // The ops shell's delegating providers (docs/stack-shells.md structural decision 2).
            // On the surface runtime the members and their live ports come from the host; on the
            // unhosted boot (tests, embedding — no host, no origin) the console mounts locally
            // as a fallback and the shell delegates to this runtime itself, a stack of one.
            OpsShellProviders.Targets shellTargets = null;
            if (stackMembers != null) {
                shellTargets = OpsShellProviders.Targets.of(stackMembers,
                        hostContext.memberOrigins());
            } else if (hostContext == null) {
                io.tesseraql.core.service.ServiceProviders selfProviders = serviceProviders;
                shellTargets = OpsShellProviders.Targets.self(appName, () -> selfProviders);
            }
            if (shellTargets != null) {
                OpsShellProviders.register(serviceProviders, shellTargets);
                new OpsShellRoutes(shellTargets).install(context);
            }
            context.bind(TesseraqlProperties.SERVICE_PROVIDERS_BEAN,
                    serviceProviders);
            Map<String, String> claimKeys = new LinkedHashMap<>();
            jobs.keySet().forEach(
                    id -> claimKeys.put(id, jobOwners.getOrDefault(id, appName) + ":" + id));
            // The daily-consider gate (docs/batch-platform.md track B), evaluated after the
            // cluster claim; the decision arithmetic is shared with the console preview.
            JobSchedules.CalendarGate calendarGate = (jobId, fireDate) -> {
                JobFile jobFile = jobs.get(jobId);
                return jobFile == null
                        ? JobSchedules.CalendarGate.Decision.RUNS
                        : calendarDecisions.decide(jobFile, fireDate);
            };
            new JobSchedules(jobRunner, jobRepository, List.copyOf(jobs.values()), claimKeys,
                    calendarGate).schedule(Schedules.of(context));
            // Batch SLA alerts (docs/batch-platform.md track E): jobs declaring sla: get a
            // periodic check that pages through the configured alerts channel — alert-only,
            // deduplicated per execution / per business date via the claim table.
            if (alertChannel != null
                    && jobs.values().stream().anyMatch(job -> job.definition().sla() != null)) {
                JobSlaSweeper slaSweeper = new JobSlaSweeper(List.copyOf(jobs.values()),
                        jobOwners, appName, jobRepository, (payload, jobApp) -> outboxStore
                                .insert(io.tesseraql.yaml.notify.NotifyEvents.event(
                                        alertChannel, "ops.jobSla", payload, jobApp)),
                        java.time.Clock.systemDefaultZone());
                long slaPeriod = io.tesseraql.core.util.Durations.toMillis(manifest.config()
                        .getString("tesseraql.batch.slaSweepInterval").orElse("60s"));
                new JobSlaSweep(slaSweeper, slaPeriod).schedule(Schedules.of(context));
            }
            // The reaper (docs/audit-hardening.md Decision 6, slice 9): a RUNNING row whose owner
            // stopped reporting is finished with a reason of its own, so the console stops showing
            // a run that ended when its node did.
            if (!jobs.isEmpty()) {
                new JobReaperSweep(jobRepository,
                        List.copyOf(jobs.keySet()),
                        io.tesseraql.core.util.Durations.parse(manifest.config()
                                .getString("tesseraql.batch.heartbeat.livenessWindow")
                                .orElse("5m")),
                        io.tesseraql.core.util.Durations.toMillis(manifest.config()
                                .getString("tesseraql.batch.reaperInterval").orElse("60s")))
                        .schedule(Schedules.of(context));
            }
            // Approval-workflow deadline sweeper (roadmap Phase 28 slice 3): a cluster-safe timer
            // escalates overdue tasks, so exactly one node sweeps per interval.
            if (workflowSweeper != null) {
                new WorkflowSweep(workflowSweeper, jobRepository,
                        io.tesseraql.yaml.workflow.WorkflowSettings
                                .sweepIntervalMillis(manifest.config()),
                        appName).schedule(Schedules.of(context));
            }
            // Directory polling for poll-triggered file-import jobs (roadmap Phase 26):
            // local/SFTP/FTPS sources feed the file-import pipeline, under a deny-by-default host
            // allow-list. How a directory is reached is an implementation detail.
            new PollSources(List.copyOf(jobs.values()),
                    io.tesseraql.yaml.connectors.FileConnectors.poll(manifest.config()), appName,
                    jobOwners, appHome,
                    io.tesseraql.yaml.config.WorkHome.resolve(appHome, manifest.config()),
                    pollSourceStatus,
                    new io.tesseraql.operations.poll.JdbcPollConsumedStore(dataSource,
                            io.tesseraql.core.util.Durations.parse(manifest.config()
                                    .getString("tesseraql.connectors.poll.consumedRetention")
                                    .orElse("30d"))))
                    .install(context);
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
                                    messagingMaxAttempts).meter(effectiveMeter),
                            backstop));
                } else {
                    LOG.warn("{} pg-notify consumer(s) declared but the main datasource is not"
                            + " PostgreSQL; LISTEN/NOTIFY will not run — use transport: db-poll",
                            pgNotifySubs.size());
                }
            }
            if (!dbPollSubs.isEmpty()) {
                new QueuePollSweep(new QueueConsumer(context,
                        eventChannelStore, dbPollSubs, messagingMaxAttempts)
                        .meter(effectiveMeter), backstop).schedule(Schedules.of(context));
            }

            IdentityService identity = new IdentityService(
                    name -> context.lookup(name,
                            javax.sql.DataSource.class),
                    datasourceDialect(manifest.config()))
                    // A sign-in's contract now runs under the same bound a page's query does
                    // (docs/contract-sql-execution.md structural decision 3): it ran unbounded,
                    // holding a pooled connection, on the one path nobody can work around.
                    .sqlTimeoutSeconds(manifest.config()
                            .getString("tesseraql.sql.timeoutSeconds")
                            .map(Integer::parseInt)
                            .orElse(io.tesseraql.core.sql.ContractStatement.DEFAULT_TIMEOUT_SECONDS));
            RealmConfig realm = IdentityConfigFactory.defaultRealm(manifest.config(), appHome);
            context.bind(TesseraqlProperties.IDENTITY_SERVICE_BEAN, identity);
            context.bind(TesseraqlProperties.IDENTITY_REALM_BEAN, realm);
            // Declared application roles converge into the store at boot
            // (docs/application-roles.md slice 3): managed realm only, no-op without a
            // declaration and without previously declared rows.
            io.tesseraql.identity.DeclaredRoleReconciler.reconcile(identity, realm, appName,
                    io.tesseraql.yaml.app.DeclaredRoles.require(appName,
                            manifest.config().navigate("tesseraql.security.roles")));
            // TOTP enrollments (roadmap Phase 50 slice 3): available wherever password
            // login is - the account page enrolls, the login route enforces.
            io.tesseraql.operations.credential.JdbcTotpStore totpStore = new io.tesseraql.operations.credential.JdbcTotpStore(
                    dataSource);
            totpStore.ensureSchema();
            context.bind(TesseraqlProperties.TOTP_STORE_BEAN, totpStore);
            new LoginRoutes(
                    new PasswordAuthenticator(identity), realm, sessionStore, totpStore,
                    credentialThrottle, identity).install(context);
            // A session buys a short-lived bearer (docs/session-token-exchange.md). Off by
            // default: an endpoint that turns a session into a credential should exist because
            // somebody decided it should, not because they upgraded.
            boolean tokenIssuing = manifest.config()
                    .getBoolean("tesseraql.security.token.enabled", false);
            String tokenTtl = manifest.config()
                    .getString("tesseraql.security.token.ttl").orElse("15m");
            // One issuer per stack (docs/token-issuance.md decision 9): with the authorization
            // server enabled on this runtime, the exchange signs RS256 through the extension's
            // signer instead of an HS256 secret — two doors, one issuer.
            boolean stackIssuer = manifest.config()
                    .getBoolean("tesseraql.security.oauth.enabled", false);
            // The member axis (docs/token-issuance.md decision 9): only the stack surface
            // holds the member list, so only its exchange can mint a member-scoped audience.
            java.util.Map<String, String> memberAddresses = null;
            if (stackIssuer && hostContext != null && hostContext.stackMembers() != null) {
                memberAddresses = new java.util.LinkedHashMap<>();
                for (io.tesseraql.operations.app.InstalledApp member : hostContext.stackMembers()) {
                    memberAddresses.put(member.name(), member.basePath());
                }
                if (hostContext.externalOrigin() != null) {
                    // The authorize surface resolves resources against these; bound before the
                    // extensions install, so the oauth extension finds them
                    // (docs/token-issuance.md decision 4).
                    context.bind(
                            io.tesseraql.oauth.OAuthRuntimeExtension.MEMBER_ADDRESSES_BEAN,
                            memberAddresses);
                    context.bind(
                            io.tesseraql.oauth.OAuthRuntimeExtension.EXTERNAL_ORIGIN_BEAN,
                            hostContext.externalOrigin());
                }
            }
            SessionTokens sessionTokens = new SessionTokens(security.jwt(),
                    io.tesseraql.core.util.Durations.parse(tokenTtl), tokenTtl, tokenIssuing,
                    stackIssuer
                            ? () -> context.lookup(
                                    io.tesseraql.oauth.OAuthRuntimeExtension.TOKEN_SIGNER_BEAN,
                                    io.tesseraql.oauth.AccessTokenSigner.class)
                            : null,
                    memberAddresses,
                    hostContext == null ? null : hostContext.externalOrigin());
            if (tokenIssuing) {
                if (!stackIssuer && !TokenExchangeRoutes.canIssue(security.jwt())) {
                    throw TokenExchangeRoutes.noSigningKey();
                }
                new TokenExchangeRoutes(sessionStore, sessionTokens).install(context);
            }
            // The stack's authenticated deploy endpoint (docs/stack-shells.md, the deploy
            // surface): mounted only where the host handed a pen — the surface runtime — so a
            // member, an unhosted boot, and every other runtime shape simply have no endpoint.
            if (hostContext != null && hostContext.deployPen() != null) {
                new DeployRoutes(hostContext.deployPen(), sessionStore).install(context);
            }
            // The console's issue-token page (docs/stack-architecture.md Decision 20), so
            // acquiring a token stops meaning "read a cookie and a meta tag out of developer
            // tools". Registered whether or not issuing is on, because the page has to be able to
            // name the key that turns it on rather than answer a 500.
            // The account surface's authorised-applications providers (docs/token-issuance.md
            // decision 4): registered unconditionally, answering enabled:false wherever the
            // oauth extension bound no store, so the unhosted account app renders the honest
            // state instead of meeting a missing provider.
            OAuthAccountProviders.register(serviceProviders,
                    name -> context.lookup(name, Object.class));
            serviceProviders
                    .register("ops.token.status", params -> sessionTokens.status())
                    .register("ops.token.issue", sessionTokens::issue)
                    // The token page's role selector (docs/application-roles.md): the caller's
                    // application-scoped grants, from the route-resolved principal.roleGrants —
                    // never caller-writable, so the page can only offer what is held.
                    .register("ops.token.roles", params -> {
                        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
                        if (params.get("roleGrants") instanceof java.util.List<?> grants) {
                            for (Object element : grants) {
                                if (element instanceof io.tesseraql.security.Principal.RoleGrant grant
                                        && grant.application() != null) {
                                    rows.add(java.util.Map.of("role", grant.role(),
                                            "application", grant.application()));
                                }
                            }
                        }
                        return rows;
                    });
            // The IAM Admin bulk endpoint (docs/hypermedia-ui.md "Bulk actions"): Java
            // because the form posts repeated ids fields, which the Simple-YAML input
            // surface deliberately does not model. Gated by the store-wide write atom like
            // the per-user routes the iam-admin app compiles, and mounted only where the app
            // itself is — a hosted member serves no /_tesseraql/admin of its own
            // (docs/stack-shells.md structural decision 3).
            if (hostedApps.contains("iam-admin")) {
                new IamAdminRoutes().install(context);
            }
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
                new RecoveryRoutes(credentialTokens, identity, realm,
                        sessionStore, outboxStore, recoveryChannel, recoveryUrl,
                        java.time.Duration.ofMinutes(manifest.config()
                                .getString("tesseraql.identity.recovery.ttlMinutes")
                                .map(Long::parseLong).orElse(30L)),
                        appName, inviteEnabled, credentialThrottle).install(context);
            }
            // The serve --watch file watcher and Studio's apply drive one reloader; bound
            // unstarted (independent of any extension) so watchRoutes() can start it on
            // demand without threading it through the runtime constructor.
            RouteReloader reloader = new RouteReloader(context, appHome, manifest, appName,
                    mountedApps, modules.functions());
            context.bind(RouteWatcher.BEAN, new RouteWatcher(appHome, reloader));
            // The boot facts a runtime extension may need beyond its ExtensionContext
            // (docs/studio-shell.md structural decision 3): published as one bean, before the
            // extensions install, so the workshop extension can assemble what the inlined
            // Studio wiring used to reach as locals. Runtime types only — no Studio type is
            // named here.
            context.bind(TesseraqlProperties.RUNTIME_SEAMS_BEAN, new RuntimeSeams(
                    httpServer::actualPort, appName,
                    java.util.Map.copyOf(dataSources), tenantDataSources,
                    calendarDecisions, notificationChannels, reloader, sseEndpoints::add,
                    httpOutbound, modules.loader(), datasourceDialect(manifest.config()),
                    hostContext != null,
                    hostContext != null && hostContext.workshop(),
                    hostContext == null || hostContext.stackMembers() == null
                            ? null
                            : hostContext.stackMembers().stream()
                                    .map(io.tesseraql.operations.app.InstalledApp::name)
                                    .toList(),
                    hostContext == null ? null : hostContext.memberOrigins()));
            // Optional feature modules (SCIM, SAML, ...) self-install via ServiceLoader, from the
            // classpath or from signature-verified plugin jars in isolated loaders (ch. 47).
            for (io.tesseraql.compiler.ext.RuntimeExtension extension : RuntimeExtensions
                    .discover(manifest.config(), appHome, modules.loader())) {
                if (extension.enabled(manifest.config())) {
                    extension.install(new io.tesseraql.compiler.ext.ExtensionContext(
                            context, manifest, dataSource, frameworkDataSource));
                    LOG.info("Installed runtime extension '{}'", extension.name());
                }
            }
            // The outbox always logs deliveries; the notification sink (mail/webhooks, roadmap
            // Phase 20) and an extension-contributed sink (e.g. SCIM outbound provisioning) are
            // composed on top when configured/bound.
            io.tesseraql.core.outbox.OutboxEventSink extensionSink = context.lookup(
                    TesseraqlProperties.OUTBOX_EVENT_SINK_BEAN,
                    io.tesseraql.core.outbox.OutboxEventSink.class);
            io.tesseraql.core.outbox.OutboxEventSink notificationSink = notificationChannels
                    .isEmpty()
                            ? null
                            : new NotificationSink(notificationChannels, appHome,
                                    inboxStore, fileTransfers,
                                    outboundGateway(httpCallClient));
            // The channel-publish sink relays publish: EVENT events onto messaging channels
            // (roadmap Phase 27), composed alongside the notification sink on the same outbox.
            io.tesseraql.core.outbox.OutboxEventSink channelSink = messagingChannels.isEmpty()
                    ? null
                    : new ChannelPublishSink(messagingChannels, eventChannelStore);
            io.tesseraql.core.outbox.OutboxEventSink outboxSink = event -> {
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
            // Retention (design ch. 44): enabled by configuring the sweep interval. When
            // tesseraql.retention.attachments is set and the managed attachment store is bound, the
            // sweep also reclaims aged attachment rows and their blobs (roadmap Phase 30 slice 3).
            var retentionSweep = manifest.config().getString("tesseraql.retention.sweep");
            if (retentionSweep.isPresent()) {
                io.tesseraql.core.attachment.AttachmentStore attachmentStore = context.lookup(
                        TesseraqlProperties.ATTACHMENT_STORE_BEAN,
                        io.tesseraql.core.attachment.AttachmentStore.class);
                io.tesseraql.core.blob.BlobStore blobStore = context.lookup(
                        TesseraqlProperties.BLOB_STORE_BEAN,
                        io.tesseraql.core.blob.BlobStore.class);
                java.time.Duration attachmentRetention = manifest.config()
                        .getString("tesseraql.retention.attachments")
                        .map(io.tesseraql.core.util.Durations::parse).orElse(null);
                new RetentionSweep(
                        new io.tesseraql.operations.retention.RetentionSweeper(dataSource,
                                attachmentStore, blobStore),
                        io.tesseraql.core.util.Durations.toMillis(retentionSweep.get()),
                        io.tesseraql.core.util.Durations.parse(
                                manifest.config().getString("tesseraql.retention.outbox")
                                        .orElse("30d")),
                        io.tesseraql.core.util.Durations.parse(
                                manifest.config().getString("tesseraql.retention.jobs")
                                        .orElse("90d")),
                        attachmentRetention).schedule(Schedules.of(context));
            }
            var outboxDelay = manifest.config().getString("tesseraql.outbox.dispatch.fixedDelay");
            if (outboxDelay.isPresent()) {
                new OutboxDispatchSweep(outboxStore, outboxSink,
                        io.tesseraql.core.util.Durations.toMillis(outboxDelay.get()), hostedApps,
                        outboxMaxAttempts(manifest.config()))
                        .schedule(Schedules.of(context));
            }
            if (alertChannel != null) {
                // Threshold-breach alerts from the dashboard notify through the same channel
                // (roadmap Phase 20).
                long alertPeriod = io.tesseraql.core.util.Durations.toMillis(manifest.config()
                        .getString("tesseraql.notifications.alerts.checkInterval").orElse("60s"));
                new AlertNotifySweep(opsDashboard, outboxStore,
                        alertChannel, alertPeriod, appName).schedule(Schedules.of(context));
            }
            // The drain is configured rather than inherited (docs/audit-hardening.md Decision 6).
            // Nothing referenced ShutdownStrategy anywhere, so Camel's 45-second default with
            // hard-stop-on-timeout applied unread — an in-flight batch step was cut off at a
            // number nobody had chosen, and no declared key said so. Configuring it does not make
            // a stop safe: SIGKILL, OOM and node loss strand rows at any timeout, which is why the
            // reaper exists. It makes the bound deliberate and visible.
            // The drain bound is a value the runtime holds now rather than a setting on an
            // engine's strategy, and forceOnTimeout keeps its meaning: false waits for the last
            // request however long it takes, which is what "do not force" always said.
            context.bind(SHUTDOWN_TIMEOUT_BEAN, manifest.config()
                    .getBoolean("tesseraql.shutdown.forceOnTimeout", true)
                            ? io.tesseraql.core.util.Durations
                                    .parse(manifest.config().getString("tesseraql.shutdown.timeout")
                                            .orElse("45s"))
                                    .toMillis()
                            : Long.MAX_VALUE);
            context.start();
            // The port the server actually bound: asking for 0 binds an ephemeral one, and
            // the published number — runtime.port(), the boot log — must carry the real port,
            // not the request. This is what lets a test boot on "any port" instead of racing
            // pick-a-free-port-then-bind across suite forks.
            boundPort = httpServer.actualPort();
            // Unicode route paths match their percent-encoded requests (UnicodePaths).
            UnicodePaths.install(context);
            // The runtime-wide in-flight bound (docs/http-threading.md decision 3). Installed here
            // because the platform router does not exist until the HTTP server service has
            // started, and ordered ahead of every route registered before it.
            HttpAdmission.install(context, maxInFlight(manifest.config()));
            sseEndpoints.forEach(Runnable::run);
            LOG.info("TesseraQL runtime started on port {} for app {}", boundPort, appHome);
            return new TesseraqlRuntime(context, dataSources, boundPort, jobRepository, jobExecutor,
                    outboxStore, jobs, jobOwners, appName, hostedApps, lanes, tenantDataSources,
                    manifest.config(), pinningSource, otelSdk, opsDashboard, outboxSink, modules);
        } catch (Exception | Error ex) {
            // A failed boot releases what it took (docs/audit-hardening.md Decision 5). Closing
            // the TesseraQL objects is not enough: everything registered through addService above
            // — the HTTP server, the notify bridge, the LISTEN connection — is started and stopped
            // by the context, so a boot that fails after context.start() left a bound port behind
            // and the next attempt failed on an address already in use. Errors release the same
            // way: ServiceLoader over an application's module jars throws
            // ServiceConfigurationError for a broken descriptor, and a catch that let it through
            // stranded every pool for the rest of the process.
            //
            // Each step is best-effort for the same reason the ordering matters: on this path one
            // failing close must not strand the resources after it, and the exception that
            // actually explains the boot failure is the one being rethrown. Executors close
            // before the pools their work borrows connections from — close()'s order.
            closeQuietly(context::close);
            closeQuietly(pinningSource);
            closeQuietly(otelSdk);
            closeQuietly(lanes);
            closeQuietly(tenantDataSources);
            dataSources.values().forEach(TesseraqlRuntime::closeQuietly);
            closeQuietly(modules);
            // A refusal keeps its code and its key-naming message on every path — the contract
            // the pools phase already pins (BootFailureReleaseTest) — and an Error is not this
            // method's to relabel; the wrapper marks a failure the boot did not anticipate.
            if (ex instanceof io.tesseraql.core.error.TqlException refusal) {
                throw refusal;
            }
            if (ex instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Failed to start TesseraQL runtime: "
                    + (ex.getMessage() != null ? ex.getMessage() : ex), ex);
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

    /** The one gateway, over the job pipeline's client (docs/lookups.md, decision 15). */
    private static io.tesseraql.yaml.http.OutboundGateway outboundGateway(
            io.tesseraql.operations.http.HttpCallClient client) {
        return new io.tesseraql.yaml.http.OutboundGateway() {
            @Override
            public java.util.Map<String, Object> call(io.tesseraql.yaml.model.HttpCallSpec spec,
                    java.util.Map<String, Object> context) {
                return client.call(spec, context, null);
            }

            @Override
            public java.util.Map<String, Object> call(io.tesseraql.yaml.model.HttpCallSpec spec,
                    byte[] body, java.util.Map<String, String> headers) {
                return client.call(spec, body, headers);
            }
        };
    }

    /**
     * Every route-shaped definition an application declares — HTTP routes, queue consumers, and
     * the three MCP surfaces. A capability is bound when <em>any</em> of them needs it: a
     * predicate that reads {@code manifest.routes()} alone leaves the same declaration on an MCP
     * tool or a consumer with an unbound bean and an uncoded failure at request time.
     */
    private static java.util.stream.Stream<io.tesseraql.yaml.model.RouteDefinition> routeShaped(
            io.tesseraql.yaml.manifest.AppManifest manifest) {
        return java.util.stream.Stream.of(
                manifest.routes().stream().map(
                        io.tesseraql.yaml.manifest.RouteFile::definition),
                manifest.consumers().stream().map(
                        io.tesseraql.yaml.manifest.RouteFile::definition),
                manifest.tools().stream().map(
                        io.tesseraql.yaml.manifest.ToolFile::definition),
                manifest.resources().stream().map(
                        io.tesseraql.yaml.manifest.ResourceFile::definition),
                manifest.uiResources().stream().map(
                        io.tesseraql.yaml.manifest.UiResourceFile::definition))
                .flatMap(java.util.function.Function.identity());
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
            io.tesseraql.yaml.manifest.AppManifest manifest, String dialect,
            io.tesseraql.core.expr.ExpressionFunctions functions) {
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
                    def, functions);
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
                } else if (onBreach.reassign() != null && onBreach.reassign().file() != null
                        && !onBreach.reassign().file().isBlank()) {
                    // The assign: shape (docs/vocabulary-cleanup.md slice 1); the sweeper binds
                    // the SQL from its own sweep context, so declared params: stay unused here.
                    java.nio.file.Path file = io.tesseraql.core.dialect.DialectSqlResolver.resolve(
                            dir.resolve(onBreach.reassign().file()).normalize(), dialect);
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
            if (transition.commandFile() != null) {
                java.nio.file.Path file = io.tesseraql.core.dialect.DialectSqlResolver.resolve(
                        dir.resolve(transition.commandFile()).normalize(), dialect);
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
            io.tesseraql.yaml.model.WorkflowDefinition def,
            io.tesseraql.core.expr.ExpressionFunctions functions) {
        if (def.reminders() == null || def.reminders().escalated() == null) {
            return null;
        }
        return io.tesseraql.yaml.notify.NotifyEvents.compile(def.id(), "escalated",
                def.reminders().escalated(), functions);
    }

    /** The configured dialect for the main datasource, or inferred from its JDBC URL (design ch. 42). */
    static String datasourceDialect(AppConfig config) {
        String prefix = "tesseraql.datasources.main.";
        return config.getString(prefix + "dialect")
                .orElseGet(() -> io.tesseraql.core.dialect.Dialect
                        .fromJdbcUrl(config.getString(prefix + "jdbcUrl")
                                .orElse(""))
                        .map(io.tesseraql.core.dialect.Dialect::id)
                        .orElse(null));
    }

    /**
     * Declared job parameters, bound the way a route binds its {@code input:}.
     *
     * <p>{@code params:} was accepted, documented with a shipped example, and never read, so
     * whatever the caller sent reached the job's SQL uncoerced — a {@code count} arrived as the
     * string {@code "10"}, and a required parameter nobody sent was simply absent until the SQL
     * failed on an unbound one.
     *
     * <p>Shared because there are three ways to start a job — the ops API, this method, and the
     * per-tenant variant — and binding in one of them is the "two spellings, one working" shape
     * this codebase has spent the day removing.
     */
    static Map<String, Object> bindJobParams(JobFile jobFile, Map<String, Object> params) {
        Map<String, io.tesseraql.yaml.model.InputField> declared = jobFile.definition().input();
        if (declared.isEmpty()) {
            return params;
        }
        return io.tesseraql.compiler.binding.InputBinder.bind(declared,
                name -> params.get(name) == null ? null : String.valueOf(params.get(name)),
                params::get, java.util.Locale.ENGLISH);
    }

    /**
     * Runs a batch job by id and returns its final execution record (design ch. 26) — the same
     * execution the ops console and the scheduler run, so the job's declaration is honoured
     * here too: a {@code perTenant} job runs once per configured tenant and this returns the
     * last execution. (It used to run once with no tenant context on this path only, which made
     * the same job behave differently via the API than via the console.)
     */
    public JobExecution runJob(String jobId, Map<String, Object> params) {
        JobFile jobFile = JobRunners.require(jobs, jobId);
        return JobRunners.runOne(jobFile, jobOwners.getOrDefault(jobId, appName),
                bindJobParams(jobFile, params), "manual", null,
                mainDataSource, dataSources, config, tenantDataSources, jobExecutor);
    }

    /**
     * Runs a batch job once per configured tenant regardless of its {@code perTenant}
     * declaration, each on its own datasource and tenant context (design ch. 30.3), returning
     * every execution record.
     */
    public List<JobExecution> runJobForAllTenants(String jobId, Map<String, Object> params) {
        JobFile jobFile = JobRunners.require(jobs, jobId);
        List<JobExecution> executions = new java.util.ArrayList<>();
        javax.sql.DataSource jobPool = JobRunners.jobDataSource(jobFile, mainDataSource,
                dataSources);
        for (String tenantId : TenantRegistry.tenantIds(config, mainDataSource,
                tenantDataSources)) {
            executions.add(jobExecutor.run(jobFile,
                    jobPool == mainDataSource
                            ? tenantDataSources.dataSourceFor(tenantId, mainDataSource)
                            : jobPool,
                    io.tesseraql.core.tenant.TenantContext.of(tenantId),
                    jobOwners.getOrDefault(jobId, appName), bindJobParams(jobFile, params),
                    "manual", null));
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
        return runtimeContext.lookup(
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
        RouteWatcher watcher = runtimeContext.lookup(RouteWatcher.BEAN, RouteWatcher.class);
        watcher.start(out);
        return watcher;
    }

    public int port() {
        return port;
    }

    public RuntimeContext context() {
        return runtimeContext;
    }

    /** Names what the drain is for on the runs it stops; see {@link #drainReason}. */
    void drainReason(String reason) {
        this.drainReason = reason;
    }

    /** The job executor, for tests that exercise the drain's cooperative stop directly. */
    JobExecutor jobExecutor() {
        return jobExecutor;
    }

    @Override
    public void close() {
        // Stop the --watch file watcher first so no reload races the context shutdown.
        closeQuietly(runtimeContext.lookup(RouteWatcher.BEAN, RouteWatcher.class));
        // A running job is drained by asking, not only by waiting (docs/runtime-replace.md):
        // before the drain starts waiting on the requests that carry job runs, every run
        // this runtime owns is asked to stop at its next step or chunk boundary — a committed
        // checkpoint and an exact resume point, comfortably inside a bound that would otherwise
        // force-cut it. The force timeout stays, unchanged, as the last resort for a run that
        // ignores the flag.
        closeQuietly(() -> jobExecutor.requestDrainStop(drainReason));
        // The requests the edge is serving, drained before the context stops (docs/runtime-replace.md,
        // docs/camel-removal.md decision 1). A shutdown strategy drains what it can count, and
        // nothing counts a compiled pipeline but the edge — so the edge counts its own and is
        // asked here, under the same declared bound that strategy used.
        RouteEdge edge = runtimeContext.lookup(RouteEdge.BEAN, RouteEdge.class);
        if (edge != null) {
            Long bound = runtimeContext.lookup(SHUTDOWN_TIMEOUT_BEAN, Long.class);
            closeQuietly(() -> edge.drain(bound == null ? 45_000L : bound));
        }
        try {
            runtimeContext.close();
        } finally {
            // Everything below outlives the drain, because the drain is what it observes and
            // serves (docs/audit-hardening.md Decision 5). The tracer and meter bound into the
            // registry wrap this SDK, so closing it before runtimeContext.stop() dropped every span
            // and metric produced while the drain finished its in-flight requests — the one window
            // the drain exists to make visible. The transfer executor is the same shape: shutting
            // it down first rejects a transfer a draining route submits.
            // The heartbeat thread outlives the drain for the same reason the tracer does: a run
            // still finishing during the drain is still a run that must say so.
            closeQuietly(jobExecutor::close);
            closeQuietly(pinningSource);
            closeQuietly(otelSdk);
            io.tesseraql.operations.files.JdbcFileTransferService fileTransfers = runtimeContext
                    .lookup(
                            TesseraqlProperties.FILE_TRANSFER_BEAN,
                            io.tesseraql.operations.files.JdbcFileTransferService.class);
            if (fileTransfers != null) {
                fileTransfers.close();
            }
            try {
                executionLanes.close();
            } finally {
                try {
                    tenantDataSources.close();
                } finally {
                    try {
                        dataSources.values().forEach(HikariDataSource::close);
                    } finally {
                        // After the pools: a pool's driver may live in this loader.
                        closeQuietly(appModules);
                    }
                }
            }
        }
    }

    static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best-effort: on both shutdown paths one resource failing to close must not strand
            // the ones after it, and on the boot-failure path the exception worth reporting is
            // the one that failed the boot.
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

    /**
     * The application's validation block with the audience swapped for the MCP surface's
     * canonical resource identifier — audit-hardening decision 2's binding: a token at the MCP
     * endpoint must be issued for THIS surface, not merely for the application beside it.
     */
    private static io.tesseraql.security.SecurityConfig.JwtConfig withAudience(
            io.tesseraql.security.SecurityConfig.JwtConfig jwt, String audience) {
        return new io.tesseraql.security.SecurityConfig.JwtConfig(jwt.algorithm(), jwt.secret(),
                jwt.publicKey(), jwt.jwksUri(), jwt.jwks(), jwt.issuer(),
                java.util.List.of(audience), jwt.clockSkew(), jwt.requireExpiration(),
                jwt.rolesClaim(), jwt.permissionsClaim(), jwt.groupsClaim(), jwt.tenantClaim(),
                jwt.loginClaim(), jwt.nameClaim());
    }

    /**
     * The stack's grafts, in order: the surface runtime receives the stack file's whole
     * {@code security:} subtree, and — with the authorization server enabled — every hosted
     * runtime receives the derived stack-issuer validation block on top
     * (docs/token-issuance.md decision 9), its audience defaulting to its own address.
     */
    private static AppManifest withStackContext(AppManifest loaded, HostContext hostContext) {
        if (hostContext == null) {
            return loaded;
        }
        AppManifest manifest = hostContext.surfaceSecurity() != null
                ? loaded.withConfig(withStackSecurity(loaded.config(),
                        hostContext.surfaceSecurity()))
                : loaded;
        if (hostContext.workshop() && hostContext.stackMembers() != null) {
            // The workshop's shell mounts on the surface runtime when the host said a workshop
            // exists (docs/studio-shell.md structural decision 2) — a topology graft over the
            // portal's static disable, through the same merge the stack file's security rides.
            // No configuration reaches here under host, so nothing can turn it on there.
            manifest = manifest.withConfig(withStackSecurityPath(manifest.config(),
                    java.util.List.of("apps", "studio"),
                    java.util.Map.of("enabled", "true")));
        }
        if (hostContext.stackIssuerJwt() == null) {
            return manifest;
        }
        return manifest.withConfig(StackIssuer.apply(manifest.config(),
                hostContext.stackIssuerJwt(), hostContext.externalOrigin(),
                hostContext.basePath(),
                hostContext.stackMembers() != null
                        ? "the stack file"
                        : "this application's configuration"));
    }

    /**
     * The surface runtime's configuration with the stack file's {@code security:} subtree
     * deep-merged over {@code tesseraql.security} (docs/stack-shells.md, the deploy surface).
     * Stack values win per leaf; everything the portal app declares and the stack does not —
     * the security defaults, the response headers — stands untouched.
     */
    private static io.tesseraql.yaml.config.AppConfig withStackSecurity(
            io.tesseraql.yaml.config.AppConfig config,
            java.util.Map<String, Object> security) {
        return withStackSecurityPath(config, java.util.List.of("security"), security);
    }

    /** As {@link #withStackSecurity}, merging {@code values} under any {@code tesseraql.*} path. */
    private static io.tesseraql.yaml.config.AppConfig withStackSecurityPath(
            io.tesseraql.yaml.config.AppConfig config, java.util.List<String> path,
            java.util.Map<String, Object> values) {
        java.util.Map<String, Object> root = SystemApps.deepCopy(config.root());
        java.util.Map<String, Object> target = SystemApps.childMap(root, "tesseraql");
        for (String segment : path) {
            target = SystemApps.childMap(target, segment);
        }
        mergeOver(target, values);
        return new io.tesseraql.yaml.config.AppConfig(root);
    }

    @SuppressWarnings("unchecked")
    private static void mergeOver(java.util.Map<String, Object> target,
            java.util.Map<String, Object> values) {
        values.forEach((key, value) -> {
            if (value instanceof java.util.Map<?, ?> nested
                    && target.get(key) instanceof java.util.Map<?, ?>) {
                mergeOver(SystemApps.childMap(target, String.valueOf(key)),
                        (java.util.Map<String, Object>) nested);
            } else {
                target.put(String.valueOf(key), value);
            }
        });
    }
}
