package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.operations.app.InstalledApp;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hosts every app a directory holds at once (design ch. 32.7): an install root's catalogue, or
 * application homes with no catalogue at all (docs/cli-surface.md Decision 2).
 *
 * <p>Each installed app runs in its own isolated {@link TesseraqlRuntime} — a separate CamelContext,
 * datasource set, and HTTP port — so apps share a process without sharing route paths or data. If
 * any app fails to start, the apps already started are shut down and the failure is propagated.
 *
 * <h2>The slots are live</h2>
 *
 * <p>Deploying an application replaces its runtime, not the stack (docs/runtime-replace.md). Each
 * member's slot holds the catalogue entry it runs beside its runtime, and the host carries the
 * operation set that moves a slot while the stack serves: {@link #replace} (the whole move),
 * {@link #stageCanary} / {@link #setCanaryWeight} / {@link #promoteCanary} /
 * {@link #discardCanary} (the same move with the ramp held open). Every operation admits the
 * candidate with the checks the stack ran at boot, probes its readiness, and only then swaps —
 * <b>a failed replace is a no-op</b>: the serving runtime is untouched by anything that goes
 * wrong before the swap. Membership itself never changes here: a new or removed application
 * recomposes the stack and is a stack deploy, Decision 29's boundary.
 */
public final class MultiAppHost implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MultiAppHost.class);
    // Shared with StackRelay, which answers it as a 404 for the same condition one layer out.
    // One number, one meaning, one declaration — the error registry reads a single source, and the
    // javadoc below is the reference page's wording for it, so it stays a meaning rather than a
    // rationale.
    /** TQL-APP-4040: no app is hosted under this name. */
    static final TqlErrorCode UNKNOWN_APP = new TqlErrorCode(TqlDomain.APP, 4040);

    /**
     * TQL-APP-4211: the stack supplies no framework datasource and the applications disagree
     * about theirs.
     *
     * <p>Divergence here presents as "signing in does not carry between applications", which
     * reads as a framework defect; the comparison is on the resolved strings, exactly, so a
     * {@code localhost} vs {@code 127.0.0.1} pair is refused too — a false refusal is loud and
     * one edit from fixed, a false pass is a stack where one sign-in silently is not one.
     */
    static final TqlErrorCode FRAMEWORK_DIVERGENCE = new TqlErrorCode(TqlDomain.APP, 4211);

    /**
     * TQL-APP-4212: an application explicitly declares {@code tesseraql.framework.datasource}
     * while the stack supplies the connection.
     *
     * <p>The application asked for framework state on a particular pool and the host is replacing
     * that pool; ignoring the request would be the silent divergence decision 22 exists to
     * remove. The unstated {@code main} default is not a request, so the host simply wins.
     */
    static final TqlErrorCode FRAMEWORK_OVERRIDDEN = new TqlErrorCode(TqlDomain.APP, 4212);

    private static final String CANARY_SLOT = "#canary";

    /**
     * The stack surface runtime's slot in the runtime map (docs/root-portal.md). Not a member —
     * never in {@link #appNames}, so nothing narrows it away, nothing watches it, and no
     * catalogue entry addresses it. {@code #} keeps it out of any legal application name's way,
     * like the canary slot.
     */
    private static final String SURFACE_SLOT = "#portal";

    /** How long the ready probe keeps asking before the replace fails as a no-op. */
    private static final int READY_ATTEMPTS = 10;
    private static final long READY_INTERVAL_MILLIS = 300;

    /**
     * One member's live state: the catalogue entry it was started from — which is what a
     * reconciliation diffs against — its runtime, and the ingress-strip set its version's
     * configuration declares. The surface slot carries a {@code null} entry: it is not a member
     * and nothing addresses or replaces it.
     */
    private record Slot(InstalledApp entry, TesseraqlRuntime runtime, Set<String> ingressStrip) {
    }

    private final Path installRoot;
    /** The stack-level settings every runtime start shares, settled once at boot. */
    private final HostContext context;
    private final DevMode dev;
    private final boolean embedded;
    /** Whether the stack's file supplies the framework coordinate (decision 22's 4212 scope). */
    private final boolean stackSuppliesFramework;
    /**
     * The framework coordinate the members agreed on at boot when the stack supplies none —
     * what a candidate's own resolution is compared against at admission ({@code TQL-APP-4211}'s
     * comparison, re-run for one application). {@code null} when supplied or unknowable.
     */
    private final String agreedFrameworkCoordinate;
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private final Set<String> appNames;
    private final Map<String, Integer> canaryWeights = new ConcurrentHashMap<>();
    /** The stack's framework pool, when its file supplies one — host-built, host-closed. */
    private final AutoCloseable stackFrameworkPool;
    /**
     * The bound the stack's own stop drains under, derived from the members' declared
     * {@code tesseraql.shutdown.timeout}s — their maximum, because the stop cannot need longer
     * than its slowest member is allowed to take, and a second knob would be two numbers for one
     * bound (docs/runtime-replace.md, the stack's own stop).
     */
    private final java.time.Duration drainBound;

    private MultiAppHost(Path installRoot, HostContext context, DevMode dev, boolean embedded,
            boolean stackSuppliesFramework, String agreedFrameworkCoordinate,
            Set<String> appNames, AutoCloseable stackFrameworkPool,
            java.time.Duration drainBound) {
        this.installRoot = installRoot;
        this.context = context;
        this.dev = dev;
        this.embedded = embedded;
        this.stackSuppliesFramework = stackSuppliesFramework;
        this.agreedFrameworkCoordinate = agreedFrameworkCoordinate;
        this.appNames = appNames;
        this.stackFrameworkPool = stackFrameworkPool;
        this.drainBound = drainBound;
    }

    /**
     * Starts every app the directory holds, each on its own ephemeral port and under the address
     * its catalogue entry declares. Any app with a staged canary candidate is also hosted in a
     * separate runtime for traffic splitting.
     */
    public static MultiAppHost start(Path installRoot) {
        return start(installRoot, HostContext.stack());
    }

    /**
     * Hosts every app the directory holds, each started with {@code stack}'s settings and its own
     * declared address (docs/stack-architecture.md decision 16).
     *
     * <p>The address is read from the catalogue entry rather than supplied by the caller. The host
     * used to take a function from app id to prefix, which left two sources able to disagree about
     * where an application answers — and the one the gateway routes by is the catalogue. There is
     * now one source, so an app is started serving exactly the prefix it is fronted under
     * (docs/base-path.md decision 5).
     */
    public static MultiAppHost start(Path installRoot, HostContext stack) {
        // Catalogued or not — a workspace of source trees hosts exactly like an install root
        // (docs/cli-surface.md Decision 2).
        return start(installRoot, stack, io.tesseraql.operations.app.AppDirectory.applications(
                io.tesseraql.operations.app.AppDirectory.resolve(installRoot)));
    }

    /**
     * Hosts exactly {@code applications}, resolved by the caller — the gateway passes the same
     * list it routes by, so the two cannot disagree about what is hosted, and a narrowed
     * {@code host --app-name} hosts one member without a second resolution pass.
     */
    public static MultiAppHost start(Path installRoot, HostContext stack,
            List<InstalledApp> applications) {
        return start(installRoot, stack, applications, null);
    }

    /**
     * As {@link #start(Path, HostContext, List)}, with the development loop's decisions
     * (docs/cli-surface.md decision 4b). With an embedded database, the framework datasource is
     * the embedded server's shared database — supplied by the CLI that started it, not derived
     * from any application — so decision 22's guards have nothing to check: TQL-APP-4211 is moot
     * (supplied) and TQL-APP-4212 is deliberately scoped to stack-file supply, because
     * "override everything" must not be the one place an override is refused.
     */
    public static MultiAppHost start(Path installRoot, HostContext stack,
            List<InstalledApp> applications, DevMode dev) {
        // The stack's own settings — tesseraql-stack.yml in the directory being hosted
        // (docs/stack-architecture.md decision 22). Loaded before any runtime starts, because
        // both of its guards are start-time refusals.
        return start(installRoot, stack, applications, dev,
                io.tesseraql.operations.app.StackSettings.load(installRoot));
    }

    /**
     * As {@link #start(Path, HostContext, List, DevMode)}, with the stack's settings already
     * loaded — the gateway reads the file first (it validates {@code root.redirect} against the
     * membership it routes by) and hands the same object down, so the file is read once.
     */
    public static MultiAppHost start(Path installRoot, HostContext stack,
            List<InstalledApp> applications, DevMode dev,
            io.tesseraql.operations.app.StackSettings settings) {
        Map<String, io.tesseraql.yaml.config.AppConfig> configs = loadConfigs(installRoot,
                applications);
        // Declared modules must be resolved on disk before anything boots: the host runs
        // offline, and an application silently missing its modules is the fail-open shape
        // decision 28 removes. dev resolves before starting, so it never meets these refusals.
        ModulesGuard.requireResolved(installRoot, applications, configs);
        boolean embedded = dev != null && dev.embeddedDb() != null;
        com.zaxxer.hikari.HikariDataSource frameworkPool = embedded
                ? DataSources.create("tesseraql-stack-framework", dev.embeddedDb())
                : frameworkPool(configs, settings);
        HostContext settled = stack.withStackSettings(
                settings.externalOrigin().orElse(
                        dev != null ? dev.defaultExternalOrigin() : null),
                frameworkPool);
        HostContext context = dev != null && dev.extraModules() != null
                ? settled.withExtraModules(dev.extraModules())
                : settled;
        // The stack-wide security schema is migrated ONCE, here, before any runtime starts;
        // the runtimes validate instead of migrating and refuse to start on a mismatch
        // (docs/stack-architecture.md decision 16). On the stack's own pool when the file
        // supplies one; otherwise on the coordinate the applications agree on — TQL-APP-4211
        // above is what makes "the first application's coordinate" the stack's.
        if (!applications.isEmpty()) {
            if (frameworkPool != null) {
                FrameworkMigrations.migrateSecurity(frameworkPool);
            } else {
                migrateSecurityOnTheAgreedCoordinate(configs);
            }
        }

        boolean stackSupplies = !embedded && settings.frameworkDatasource().isPresent();
        String agreed = frameworkPool != null || configs.isEmpty()
                ? null
                : frameworkCoordinateOf(configs.values().iterator().next());
        Set<String> appNames = applications.stream().map(InstalledApp::name)
                .collect(java.util.stream.Collectors
                        .toCollection(java.util.LinkedHashSet::new));
        MultiAppHost host = new MultiAppHost(installRoot, context, dev, embedded, stackSupplies,
                agreed, java.util.Collections.unmodifiableSet(appNames), frameworkPool,
                drainBound(configs));

        io.tesseraql.operations.app.AppUpgrader upgrader = new io.tesseraql.operations.app.AppUpgrader();
        try {
            for (InstalledApp app : applications) {
                Path appHome = installRoot.resolve(app.path()).normalize();
                io.tesseraql.yaml.config.AppConfig config = configs.get(app.name());
                // A declared server.port is honoured as the application's INTERNAL port
                // (docs/cli-surface.md decision 4a) — the key keeps its one meaning, the port
                // this application binds; the front door is the gateway's --port. Undeclared
                // stays ephemeral, and a collision between two declared ports fails loudly at
                // bind, which is a flag-grade failure rather than a silent one.
                host.slots.put(app.name(), new Slot(app,
                        host.startRuntime(app, config,
                                declaredPort(config).orElseGet(MultiAppHost::freePort)),
                        ingressStrip(config)));
                LOG.info("Hosting app {} v{} from {}", app.name(), app.version(), appHome);

                upgrader.canary(app.name(), installRoot).ifPresent(canary -> {
                    io.tesseraql.yaml.config.AppConfig candidateConfig = new io.tesseraql.yaml.manifest.ManifestLoader()
                            .load(installRoot.resolve(canary.candidate().path()).normalize())
                            .config();
                    // The candidate answers the same address as the app it may replace, so it
                    // serves the same base path.
                    host.slots.put(app.name() + CANARY_SLOT, new Slot(canary.candidate(),
                            host.startRuntime(canary.candidate(), candidateConfig, freePort()),
                            ingressStrip(candidateConfig)));
                    host.canaryWeights.put(app.name(), canary.weightPercent());
                    LOG.info("Hosting canary {} v{} at {}% traffic",
                            app.name(), canary.candidate().version(), canary.weightPercent());
                });
            }
            // The stack surface runtime, after the members so a datasource misconfiguration
            // fails with a member's own message first (docs/root-portal.md). Its main
            // application is the bundled portal app, its main pool is the stack's framework
            // coordinate, and its failure fails the stack — it is the stack's sign-in, and a
            // stack that comes up without it would be a silent degradation.
            if (!applications.isEmpty()) {
                Path surfaceHome = new io.tesseraql.yaml.apps.ClasspathAppSource("portal",
                        "tesseraql/apps/portal", MultiAppHost.class.getClassLoader())
                        .materialize(installRoot.resolve("work"));
                host.slots.put(SURFACE_SLOT, new Slot(null,
                        TesseraqlRuntime.start(surfaceHome, freePort(),
                                context.forSurface(
                                        surfaceMainOverride(settings, configs, dev, embedded),
                                        applications)),
                        Set.of()));
                LOG.info("Hosting the stack surface (sign-in, account, portal) at the origin"
                        + " scope from {}", surfaceHome);
            }
        } catch (RuntimeException ex) {
            host.slots.values().forEach(slot -> closeQuietly(slot.runtime()));
            if (frameworkPool != null) {
                frameworkPool.close();
            }
            throw ex;
        }
        return host;
    }

    /**
     * Replaces {@code entry.name()}'s runtime with {@code entry}'s version — the whole move, for
     * a direct deploy or a post-promote rollback (docs/runtime-replace.md structural decision 1):
     * admission, start beside the serving version, the ready probe, the swap, then the retiring
     * runtime's drain. Every failure before the swap abandons the candidate and leaves the old
     * runtime serving, untouched — <b>a failed replace is a no-op</b>.
     *
     * <p>The candidate always takes an ephemeral port: a declared {@code server.port} is held by
     * the runtime being replaced until it closes, which is after the candidate binds. The number
     * moves back at the next stack start — recorded in {@code hosting.md} rather than engineered
     * around.
     */
    public synchronized void replace(InstalledApp entry) {
        Slot candidate = admitAndStart(entry);
        Slot retired = slots.put(entry.name(), candidate);
        LOG.info("Replaced {}: v{} -> v{} (the retiring runtime drains now)",
                entry.name(), retired.entry().version(), entry.version());
        retire(retired, "stopped: a deploy replaced " + entry.name() + " with v"
                + entry.version() + " (cooperative stop)");
    }

    /**
     * Starts {@code entry} as a canary beside the serving version — same admission and ready
     * probe as {@link #replace}, same address, an ephemeral port — taking {@code weightPercent}
     * of the member's HTTP traffic. The weight gates HTTP only: the candidate's jobs, pollers
     * and outbox work from its start, claim-arbitrated, exactly as a node that joined a cluster
     * (docs/runtime-replace.md, the overlap window).
     */
    public synchronized void stageCanary(InstalledApp entry, int weightPercent) {
        String name = entry.name();
        if (slots.containsKey(name + CANARY_SLOT)) {
            throw new IllegalStateException("A canary is already staged for " + name
                    + "; promote or discard it first.");
        }
        Slot candidate = admitAndStart(entry);
        slots.put(name + CANARY_SLOT, candidate);
        canaryWeights.put(name, clampWeight(weightPercent));
        LOG.info("Staged canary {} v{} at {}% traffic", name, entry.version(),
                canaryWeights.get(name));
    }

    /**
     * Moves the staged canary's traffic share — live, which is the point: the weight used to be
     * a start-time snapshot, so adjusting a canary's ramp meant restarting the whole stack,
     * which defeats the canary (the measured defect docs/runtime-replace.md opens with).
     */
    public synchronized void setCanaryWeight(String appName, int weightPercent) {
        requireCanary(appName);
        canaryWeights.put(appName, clampWeight(weightPercent));
        LOG.info("Canary {} now takes {}% of traffic", appName, canaryWeights.get(appName));
    }

    /**
     * The staged candidate becomes the member's serving runtime and the old stable drains.
     * Nothing starts: the promoted runtime has been serving its weight share already, which is
     * the strongest health check available.
     */
    public synchronized void promoteCanary(String appName) {
        Slot candidate = requireCanary(appName);
        slots.remove(appName + CANARY_SLOT);
        canaryWeights.remove(appName);
        Slot retired = slots.put(appName, candidate);
        LOG.info("Promoted canary {}: v{} -> v{} (the retiring runtime drains now)", appName,
                retired.entry().version(), candidate.entry().version());
        retire(retired, "stopped: a deploy promoted " + appName + " to v"
                + candidate.entry().version() + " (cooperative stop)");
    }

    /** Drains and closes the staged candidate; the serving runtime is untouched. */
    public synchronized void discardCanary(String appName) {
        Slot candidate = requireCanary(appName);
        slots.remove(appName + CANARY_SLOT);
        canaryWeights.remove(appName);
        LOG.info("Discarding canary {} v{}", appName, candidate.entry().version());
        retire(candidate, "stopped: the canary for " + appName
                + " was discarded (cooperative stop)");
    }

    /**
     * Admission and start for one candidate: the boot guards re-run for it — the modules guard
     * and decision 22's framework guards ({@code TQL-APP-4212} against a stack supply,
     * {@code TQL-APP-4211}'s comparison against the running agreement) — then the runtime start,
     * where the candidate's own framework schema validation surfaces its refusal, then the
     * ready probe. Same codes, same messages, same meaning: refused at admission instead of at
     * boot. Any refusal leaves the host's state untouched.
     */
    private Slot admitAndStart(InstalledApp entry) {
        String name = entry.name();
        if (!appNames.contains(name)) {
            throw new TqlException(UNKNOWN_APP, "App is not hosted: " + name
                    + ". Adding an application to the stack is a stack deploy, not a replace.");
        }
        io.tesseraql.yaml.config.AppConfig config = new io.tesseraql.yaml.manifest.ManifestLoader()
                .load(installRoot.resolve(entry.path()).normalize()).config();
        ModulesGuard.requireResolved(installRoot, List.of(entry), Map.of(name, config));
        if (stackSuppliesFramework
                && config.getString("tesseraql.framework.datasource").isPresent()) {
            throw new TqlException(FRAMEWORK_OVERRIDDEN, "The stack supplies the framework"
                    + " datasource, and the candidate for " + name + " explicitly declares"
                    + " tesseraql.framework.datasource. The host would replace the pool that"
                    + " declaration asked for, so it refuses instead: remove the declaration to"
                    + " ride the stack's connection, or remove framework.datasource from "
                    + io.tesseraql.operations.app.StackSettings.FILE_NAME + ".");
        }
        if (!stackSuppliesFramework && !embedded && agreedFrameworkCoordinate != null) {
            String candidateCoordinate = frameworkCoordinateOf(config);
            if (!agreedFrameworkCoordinate.equals(candidateCoordinate)) {
                throw new TqlException(FRAMEWORK_DIVERGENCE, "The stack supplies no framework"
                        + " datasource and the candidate for " + name + " resolves a framework"
                        + " coordinate that disagrees with the running stack's, so one sign-in"
                        + " would silently not be one:\n  running: " + agreedFrameworkCoordinate
                        + "\n  candidate: " + candidateCoordinate + "\nPoint the candidate's"
                        + " configuration at the stack's connection, or declare"
                        + " framework.datasource in "
                        + io.tesseraql.operations.app.StackSettings.FILE_NAME + ".");
            }
        }
        TesseraqlRuntime runtime = startRuntime(entry, config, freePort());
        try {
            awaitReady(entry, runtime);
        } catch (RuntimeException notReady) {
            closeQuietly(runtime);
            throw notReady;
        }
        return new Slot(entry, runtime, ingressStrip(config));
    }

    /**
     * The one check that exercises the datasource roll-up end to end before any traffic moves:
     * the swap only ever installs a runtime that answered ready. A bounded handful of retries —
     * {@code TesseraqlRuntime.start} returning is already a strong gate, so the first answer is
     * normally the one — then the replace fails as a no-op.
     */
    private void awaitReady(InstalledApp entry, TesseraqlRuntime runtime) {
        String url = "http://localhost:" + runtime.port() + entry.basePath()
                + "/_tesseraql/health/ready";
        java.net.http.HttpClient probe = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(2)).build();
        String lastAnswer = "no answer";
        for (int attempt = 0; attempt < READY_ATTEMPTS; attempt++) {
            try {
                java.net.http.HttpResponse<String> response = probe.send(
                        java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                                .timeout(java.time.Duration.ofSeconds(2)).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
                lastAnswer = "HTTP " + response.statusCode();
            } catch (IOException notYet) {
                lastAnswer = notYet.getMessage();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                Thread.sleep(READY_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("Replace of " + entry.name() + " abandoned: the"
                + " candidate v" + entry.version() + " never answered ready at " + url
                + " (last answer: " + lastAnswer + "). The serving runtime is untouched.");
    }

    /**
     * Drains and closes a runtime whose slot has already moved on. The drain is asked for, not
     * only waited out: the runtime requests the cooperative stop of every job run it owns, with
     * {@code reason} recorded on the stopped executions so the operator reads the deploy, not a
     * phantom operator action (docs/runtime-replace.md, the job drain). Close failures are
     * logged, never propagated — the swap already happened, and the new runtime serving is the
     * property that matters.
     */
    private static void retire(Slot slot, String reason) {
        slot.runtime().drainReason(reason);
        closeQuietly(slot.runtime());
    }

    private Slot requireCanary(String appName) {
        Slot candidate = slots.get(appName + CANARY_SLOT);
        if (candidate == null) {
            throw new IllegalStateException("No canary is staged for " + appName + ".");
        }
        return candidate;
    }

    private static int clampWeight(int weightPercent) {
        return Math.max(0, Math.min(100, weightPercent));
    }

    /** One member's runtime, started with the settings this stack settled at boot. */
    private TesseraqlRuntime startRuntime(InstalledApp entry,
            io.tesseraql.yaml.config.AppConfig config, int port) {
        Path appHome = installRoot.resolve(entry.path()).normalize();
        return TesseraqlRuntime.start(appHome, port,
                context.forApplication(entry.basePath(), embedded
                        ? carryingDeclaredQuery(dev.embeddedDb(), config)
                        : null));
    }

    /**
     * The forwarded header this version's configuration tells the gateway to strip on ingress —
     * read from the same configuration admission already loaded, so a replace's change takes
     * effect with the swap (docs/runtime-replace.md, relay upkeep).
     */
    private static Set<String> ingressStrip(io.tesseraql.yaml.config.AppConfig config) {
        return config == null
                ? Set.of()
                : config.getString("tesseraql.security.mtls.forwardedHeader")
                        .map(header -> Set.of(header.toLowerCase(Locale.ROOT)))
                        .orElseGet(Set::of);
    }

    /**
     * The resolved framework coordinate one application's configuration reaches — the string
     * {@code TQL-APP-4211}'s agreement is compared on.
     */
    private static String frameworkCoordinateOf(io.tesseraql.yaml.config.AppConfig config) {
        String datasource = config.getString("tesseraql.framework.datasource").orElse("main");
        String prefix = "tesseraql.datasources." + datasource + ".";
        return config.getString(prefix + "jdbcUrl").orElse("<undeclared>")
                + " as " + config.getString(prefix + "username").orElse("<no username>");
    }

    /**
     * The stack stop's drain bound: the maximum of the members' own declared
     * {@code tesseraql.shutdown.timeout}s (45s default apiece) — no stack-level knob.
     */
    private static java.time.Duration drainBound(
            Map<String, io.tesseraql.yaml.config.AppConfig> configs) {
        return configs.values().stream()
                .map(config -> io.tesseraql.core.util.Durations.parse(
                        config.getString("tesseraql.shutdown.timeout").orElse("45s")))
                .max(java.util.Comparator.naturalOrder())
                .orElse(java.time.Duration.ofSeconds(45));
    }

    /**
     * The embedded coordinate, carrying the application's own declared {@code main} query
     * string — so {@code ?currentSchema=a} declared in the application's URL keeps isolating it
     * inside the shared embedded database (docs/cli-surface.md decision 4b: the application's
     * {@code currentSchema} stays in its own URL, and nothing derives it).
     */
    private static DataSources.MainDatasourceOverride carryingDeclaredQuery(
            DataSources.MainDatasourceOverride embedded,
            io.tesseraql.yaml.config.AppConfig config) {
        String declared = config == null
                ? null
                : config.getString("tesseraql.datasources.main.jdbcUrl").orElse(null);
        int query = declared == null ? -1 : declared.indexOf('?');
        if (query < 0) {
            return embedded;
        }
        String base = embedded.jdbcUrl();
        return new DataSources.MainDatasourceOverride(
                base + (base.indexOf('?') >= 0 ? "&" : "?") + declared.substring(query + 1),
                embedded.username(), embedded.password());
    }

    /**
     * The coordinate the stack surface runtime's {@code main} pool is built from: the stack's
     * framework coordinate, however this start resolved it (docs/root-portal.md). The portal
     * application declares no datasources of its own, so this override is its whole answer —
     * and its {@code security} component then validates against the schema the host migrated
     * on the same coordinate.
     */
    private static DataSources.MainDatasourceOverride surfaceMainOverride(
            io.tesseraql.operations.app.StackSettings settings,
            Map<String, io.tesseraql.yaml.config.AppConfig> configs,
            DevMode dev, boolean embedded) {
        if (embedded) {
            return dev.embeddedDb();
        }
        java.util.Optional<io.tesseraql.operations.app.StackSettings.Coordinate> supplied = settings
                .frameworkDatasource();
        if (supplied.isPresent()) {
            io.tesseraql.operations.app.StackSettings.Coordinate coordinate = supplied.get();
            return new DataSources.MainDatasourceOverride(coordinate.jdbcUrl(),
                    coordinate.username(), coordinate.password());
        }
        // The coordinate the applications agree on — TQL-APP-4211 has already refused
        // disagreement, so the first application's resolved coordinate is the stack's. Nothing
        // declared at all cannot be reached here: a member with no main jdbcUrl has already
        // failed its own start with the established error.
        io.tesseraql.yaml.config.AppConfig config = configs.values().iterator().next();
        String datasource = config.getString("tesseraql.framework.datasource").orElse("main");
        String prefix = "tesseraql.datasources." + datasource + ".";
        return config.getString(prefix + "jdbcUrl")
                .map(jdbcUrl -> new DataSources.MainDatasourceOverride(jdbcUrl,
                        config.getString(prefix + "username").orElse(null),
                        config.getString(prefix + "password").orElse(null)))
                .orElse(null);
    }

    /**
     * The application's declared internal port, when it declares a fixed one. {@code 0} and
     * absence both mean "ephemeral" — the test fixtures' {@code server.port: 0} idiom predates
     * hosting and keeps its meaning — and the canary slot always takes an ephemeral port, since
     * the candidate runs beside the stable version that holds the declared one.
     */
    private static java.util.Optional<Integer> declaredPort(
            io.tesseraql.yaml.config.AppConfig config) {
        return config == null
                ? java.util.Optional.empty()
                : config.getString("server.port").map(Integer::parseInt)
                        .filter(port -> port > 0);
    }

    /** Each application's placeholder-resolved configuration, keyed by name, load order kept. */
    private static Map<String, io.tesseraql.yaml.config.AppConfig> loadConfigs(Path installRoot,
            List<InstalledApp> applications) {
        Map<String, io.tesseraql.yaml.config.AppConfig> configs = new LinkedHashMap<>();
        for (InstalledApp app : applications) {
            Path appHome = installRoot.resolve(app.path()).normalize();
            configs.put(app.name(),
                    new io.tesseraql.yaml.manifest.ManifestLoader().load(appHome).config());
        }
        return configs;
    }

    /**
     * Migrates the stack-wide {@code security} schema on the coordinate the applications agree
     * on, through a pool that exists only for the migration. TQL-APP-4211 has already refused
     * disagreement, so the first application's resolved coordinate <em>is</em> the stack's; the
     * runtimes then build their own pools on that same coordinate and validate.
     */
    private static void migrateSecurityOnTheAgreedCoordinate(
            Map<String, io.tesseraql.yaml.config.AppConfig> configs) {
        io.tesseraql.yaml.config.AppConfig config = configs.values().iterator().next();
        String datasource = config.getString("tesseraql.framework.datasource").orElse("main");
        String prefix = "tesseraql.datasources." + datasource + ".";
        String jdbcUrl = config.getString(prefix + "jdbcUrl").orElse(null);
        if (jdbcUrl == null) {
            // Nothing declared: the runtime's own datasource loading will refuse with the
            // established error, which is the better message than anything this layer could say.
            return;
        }
        try (com.zaxxer.hikari.HikariDataSource migrationPool = DataSources.create(
                "tesseraql-stack-framework-migration",
                new DataSources.MainDatasourceOverride(jdbcUrl,
                        config.getString(prefix + "username").orElse(null),
                        config.getString(prefix + "password").orElse(null)))) {
            FrameworkMigrations.migrateSecurity(migrationPool);
        }
    }

    /**
     * The stack's framework pool when its file supplies a coordinate, after both of decision
     * 22's guards — or {@code null} with the applications checked for agreement.
     *
     * <p>Supplied: one pool for the whole stack, so signing in carries by construction, and an
     * application that <em>explicitly</em> declared {@code tesseraql.framework.datasource} is
     * refused ({@code TQL-APP-4212}) rather than silently repointed. Absent: with more than one
     * application, each one's resolved framework coordinate is compared and disagreement refuses
     * the start ({@code TQL-APP-4211}) — absence is a check, never a silence.
     */
    private static com.zaxxer.hikari.HikariDataSource frameworkPool(
            Map<String, io.tesseraql.yaml.config.AppConfig> configs,
            io.tesseraql.operations.app.StackSettings settings) {
        java.util.Optional<io.tesseraql.operations.app.StackSettings.Coordinate> supplied = settings
                .frameworkDatasource();
        if (supplied.isPresent()) {
            List<String> explicit = configs.entrySet().stream()
                    .filter(entry -> entry.getValue()
                            .getString("tesseraql.framework.datasource").isPresent())
                    .map(Map.Entry::getKey)
                    .toList();
            if (!explicit.isEmpty()) {
                throw new TqlException(FRAMEWORK_OVERRIDDEN, "The stack supplies the framework"
                        + " datasource, and " + String.join(", ", explicit) + " explicitly"
                        + " declares tesseraql.framework.datasource. The host would replace the"
                        + " pool that declaration asked for, so it refuses instead: remove the"
                        + " declaration to ride the stack's connection, or remove"
                        + " framework.datasource from "
                        + io.tesseraql.operations.app.StackSettings.FILE_NAME
                        + ".");
            }
            io.tesseraql.operations.app.StackSettings.Coordinate coordinate = supplied.get();
            return DataSources.create("tesseraql-stack-framework",
                    new DataSources.MainDatasourceOverride(coordinate.jdbcUrl(),
                            coordinate.username(), coordinate.password()));
        }

        if (configs.size() > 1) {
            Map<String, String> coordinates = new LinkedHashMap<>();
            configs.forEach((name, config) -> coordinates.put(name,
                    frameworkCoordinateOf(config)));
            if (new java.util.HashSet<>(coordinates.values()).size() > 1) {
                StringBuilder each = new StringBuilder();
                coordinates.forEach((name, coordinate) -> each.append("\n  ").append(name)
                        .append(": ").append(coordinate));
                throw new TqlException(FRAMEWORK_DIVERGENCE, "The stack supplies no framework"
                        + " datasource and its applications disagree about theirs, so one sign-in"
                        + " would silently not be one:" + each + "\nDeclare framework.datasource"
                        + " in " + io.tesseraql.operations.app.StackSettings.FILE_NAME
                        + " beside the applications, or point their configurations at one"
                        + " connection.");
            }
        }
        return null;
    }

    /** The hosted runtime for {@code appName}, or throws {@code TQL-APP-4040} if it is not hosted. */
    public TesseraqlRuntime app(String appName) {
        Slot slot = slots.get(appName);
        if (slot == null) {
            throw new TqlException(UNKNOWN_APP, "App is not hosted: " + appName);
        }
        return slot.runtime();
    }

    /** The HTTP port the given app's active version is listening on. */
    public int port(String appName) {
        return app(appName).port();
    }

    public Set<String> appNames() {
        return appNames;
    }

    /**
     * The catalogue entry {@code appName}'s serving runtime was started from — live, so after a
     * replace it is the new version's. {@code null} for a name the stack does not hold; the
     * relay treats that as unaddressed.
     */
    InstalledApp entry(String appName) {
        Slot slot = slots.get(appName);
        return slot == null ? null : slot.entry();
    }

    /**
     * The ingress-strip set of {@code appName}'s <em>serving</em> version — live for the same
     * reason as {@link #entry}: a replaced version's changed {@code forwardedHeader} takes
     * effect with the swap.
     */
    Set<String> ingressStrip(String appName) {
        Slot slot = slots.get(appName);
        return slot == null ? Set.of() : slot.ingressStrip();
    }

    /** The bound the stack's own stop drains under; see {@link #drainBound}. */
    java.time.Duration drainBound() {
        return drainBound;
    }

    /**
     * The internal port the stack surface runtime answers on — the origin scope's sign-in,
     * account surface and portal (docs/root-portal.md). Throws {@code TQL-APP-4040} when this
     * host started no applications and therefore no surface.
     */
    public int surfacePort() {
        return app(SURFACE_SLOT).port();
    }

    /** Whether the app has a staged canary candidate receiving a share of traffic. */
    public boolean hasCanary(String appName) {
        return canaryWeights.containsKey(appName);
    }

    /** The percentage of traffic the app's canary candidate should receive (0 if none). */
    public int canaryWeight(String appName) {
        return canaryWeights.getOrDefault(appName, 0);
    }

    /** The HTTP port of the app's canary candidate; only valid when {@link #hasCanary} is true. */
    public int canaryPort(String appName) {
        return app(appName + CANARY_SLOT).port();
    }

    @Override
    public synchronized void close() {
        // Members first, in start order — canary beside its stable — then the surface: a member
        // draining may still authenticate against the surface's sign-in state, and both may
        // still ride the stack's pool, which is why the pool goes last.
        for (String name : appNames) {
            closeSlot(name + CANARY_SLOT);
            closeSlot(name);
        }
        closeSlot(SURFACE_SLOT);
        slots.values().forEach(slot -> closeQuietly(slot.runtime()));
        slots.clear();
        if (stackFrameworkPool != null) {
            try {
                stackFrameworkPool.close();
            } catch (Exception ex) {
                LOG.warn("Failed to close the stack's framework pool: {}", ex.getMessage());
            }
        }
    }

    private void closeSlot(String slotName) {
        Slot slot = slots.remove(slotName);
        if (slot != null) {
            closeQuietly(slot.runtime());
        }
    }

    private static void closeQuietly(TesseraqlRuntime runtime) {
        try {
            runtime.close();
        } catch (RuntimeException ex) {
            LOG.warn("Failed to stop hosted app: {}", ex.getMessage());
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
