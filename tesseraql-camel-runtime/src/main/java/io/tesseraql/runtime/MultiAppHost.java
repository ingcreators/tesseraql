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

    private static final String CANARY_SLOT = "#canary";

    private final Map<String, TesseraqlRuntime> runtimes;
    private final Set<String> appNames;
    private final Map<String, Integer> canaryWeights;

    private MultiAppHost(Map<String, TesseraqlRuntime> runtimes, Set<String> appNames,
            Map<String, Integer> canaryWeights) {
        this.runtimes = runtimes;
        this.appNames = appNames;
        this.canaryWeights = canaryWeights;
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
        io.tesseraql.operations.app.AppUpgrader upgrader = new io.tesseraql.operations.app.AppUpgrader();
        Map<String, TesseraqlRuntime> started = new LinkedHashMap<>();
        Set<String> appNames = new java.util.LinkedHashSet<>();
        Map<String, Integer> canaryWeights = new LinkedHashMap<>();
        try {
            for (InstalledApp app : applications) {
                Path appHome = installRoot.resolve(app.path()).normalize();
                started.put(app.name(), TesseraqlRuntime.start(appHome, freePort(),
                        stack.forApplication(app.basePath())));
                appNames.add(app.name());
                LOG.info("Hosting app {} v{} from {}", app.name(), app.version(), appHome);

                upgrader.canary(app.name(), installRoot).ifPresent(canary -> {
                    Path candidateHome = installRoot.resolve(canary.candidate().path()).normalize();
                    // The candidate answers the same address as the app it may replace, so it
                    // serves the same base path.
                    started.put(app.name() + CANARY_SLOT,
                            TesseraqlRuntime.start(candidateHome, freePort(),
                                    stack.forApplication(app.basePath())));
                    canaryWeights.put(app.name(), canary.weightPercent());
                    LOG.info("Hosting canary {} v{} at {}% traffic",
                            app.name(), canary.candidate().version(), canary.weightPercent());
                });
            }
        } catch (RuntimeException ex) {
            started.values().forEach(MultiAppHost::closeQuietly);
            throw ex;
        }
        return new MultiAppHost(started, Set.copyOf(appNames), Map.copyOf(canaryWeights));
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
