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
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hosts every app a directory holds at once (design ch. 32.7): an install root's catalogue, or
 * application homes with no catalogue at all (docs/cli-surface.md Decision 2).
 *
 * <p>Each installed app runs in its own isolated {@link TesseraqlRuntime} — a separate CamelContext,
 * datasource set, and HTTP port — so apps share a process without sharing route paths or data. If
 * any app fails to start, the apps already started are shut down and the failure is propagated.
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

    private final Map<String, TesseraqlRuntime> runtimes;
    private final Set<String> appNames;
    private final Map<String, Integer> canaryWeights;
    /** The stack's framework pool, when its file supplies one — host-built, host-closed. */
    private final AutoCloseable stackFrameworkPool;

    private MultiAppHost(Map<String, TesseraqlRuntime> runtimes, Set<String> appNames,
            Map<String, Integer> canaryWeights, AutoCloseable stackFrameworkPool) {
        this.runtimes = runtimes;
        this.appNames = appNames;
        this.canaryWeights = canaryWeights;
        this.stackFrameworkPool = stackFrameworkPool;
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
        // The stack's own settings — tesseraql-stack.yml in the directory being hosted
        // (docs/stack-architecture.md decision 22). Loaded before any runtime starts, because
        // both of its guards are start-time refusals.
        io.tesseraql.operations.app.StackSettings settings = io.tesseraql.operations.app.StackSettings
                .load(installRoot);
        Map<String, io.tesseraql.yaml.config.AppConfig> configs = loadConfigs(installRoot,
                applications);
        com.zaxxer.hikari.HikariDataSource frameworkPool = frameworkPool(configs, settings);
        HostContext context = stack.withStackSettings(
                settings.externalOrigin().orElse(null), frameworkPool);
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

        io.tesseraql.operations.app.AppUpgrader upgrader = new io.tesseraql.operations.app.AppUpgrader();
        Map<String, TesseraqlRuntime> started = new LinkedHashMap<>();
        Set<String> appNames = new java.util.LinkedHashSet<>();
        Map<String, Integer> canaryWeights = new LinkedHashMap<>();
        try {
            for (InstalledApp app : applications) {
                Path appHome = installRoot.resolve(app.path()).normalize();
                started.put(app.name(), TesseraqlRuntime.start(appHome, freePort(),
                        context.forApplication(app.basePath())));
                appNames.add(app.name());
                LOG.info("Hosting app {} v{} from {}", app.name(), app.version(), appHome);

                upgrader.canary(app.name(), installRoot).ifPresent(canary -> {
                    Path candidateHome = installRoot.resolve(canary.candidate().path()).normalize();
                    // The candidate answers the same address as the app it may replace, so it
                    // serves the same base path.
                    started.put(app.name() + CANARY_SLOT,
                            TesseraqlRuntime.start(candidateHome, freePort(),
                                    context.forApplication(app.basePath())));
                    canaryWeights.put(app.name(), canary.weightPercent());
                    LOG.info("Hosting canary {} v{} at {}% traffic",
                            app.name(), canary.candidate().version(), canary.weightPercent());
                });
            }
        } catch (RuntimeException ex) {
            started.values().forEach(MultiAppHost::closeQuietly);
            if (frameworkPool != null) {
                frameworkPool.close();
            }
            throw ex;
        }
        return new MultiAppHost(started, Set.copyOf(appNames), Map.copyOf(canaryWeights),
                frameworkPool);
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
            configs.forEach((name, config) -> {
                String datasource = config.getString("tesseraql.framework.datasource")
                        .orElse("main");
                String prefix = "tesseraql.datasources." + datasource + ".";
                coordinates.put(name, config.getString(prefix + "jdbcUrl").orElse("<undeclared>")
                        + " as " + config.getString(prefix + "username").orElse("<no username>"));
            });
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
        TesseraqlRuntime runtime = runtimes.get(appName);
        if (runtime == null) {
            throw new TqlException(UNKNOWN_APP, "App is not hosted: " + appName);
        }
        return runtime;
    }

    /** The HTTP port the given app's active version is listening on. */
    public int port(String appName) {
        return app(appName).port();
    }

    public Set<String> appNames() {
        return appNames;
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
    public void close() {
        runtimes.values().forEach(MultiAppHost::closeQuietly);
        // After the runtimes: they may still be draining work that rides the stack's pool.
        if (stackFrameworkPool != null) {
            try {
                stackFrameworkPool.close();
            } catch (Exception ex) {
                LOG.warn("Failed to close the stack's framework pool: {}", ex.getMessage());
            }
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
