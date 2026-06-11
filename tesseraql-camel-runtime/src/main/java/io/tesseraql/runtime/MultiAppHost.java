package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.operations.app.AppCatalog;
import io.tesseraql.operations.app.InstalledApp;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hosts every app recorded in an install root's {@link AppCatalog} at once (design ch. 32.7).
 *
 * <p>Each installed app runs in its own isolated {@link TesseraqlRuntime} — a separate CamelContext,
 * datasource set, and HTTP port — so apps share a process without sharing route paths or data. If
 * any app fails to start, the apps already started are shut down and the failure is propagated.
 */
public final class MultiAppHost implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MultiAppHost.class);
    private static final TqlErrorCode UNKNOWN_APP = new TqlErrorCode(TqlDomain.APP, 4040);

    private static final String CANARY_SLOT = "#canary";

    private final Map<String, TesseraqlRuntime> runtimes;
    private final Set<String> appIds;
    private final Map<String, Integer> canaryWeights;

    private MultiAppHost(Map<String, TesseraqlRuntime> runtimes, Set<String> appIds,
            Map<String, Integer> canaryWeights) {
        this.runtimes = runtimes;
        this.appIds = appIds;
        this.canaryWeights = canaryWeights;
    }

    /**
     * Starts every catalogued app under {@code installRoot}, each on its own ephemeral port. Any app
     * with a staged canary candidate is also hosted in a separate runtime for traffic splitting.
     */
    public static MultiAppHost start(Path installRoot) {
        AppCatalog catalog = new AppCatalog(installRoot);
        io.tesseraql.operations.app.AppUpgrader upgrader = new io.tesseraql.operations.app.AppUpgrader();
        Map<String, TesseraqlRuntime> started = new LinkedHashMap<>();
        Set<String> appIds = new java.util.LinkedHashSet<>();
        Map<String, Integer> canaryWeights = new LinkedHashMap<>();
        try {
            for (InstalledApp app : catalog.list()) {
                Path appHome = installRoot.resolve(app.path()).normalize();
                started.put(app.id(), TesseraqlRuntime.start(appHome, freePort()));
                appIds.add(app.id());
                LOG.info("Hosting app {} v{} from {}", app.id(), app.version(), appHome);

                upgrader.canary(app.id(), installRoot).ifPresent(canary -> {
                    Path candidateHome = installRoot.resolve(canary.candidate().path()).normalize();
                    started.put(app.id() + CANARY_SLOT, TesseraqlRuntime.start(candidateHome, freePort()));
                    canaryWeights.put(app.id(), canary.weightPercent());
                    LOG.info("Hosting canary {} v{} at {}% traffic",
                            app.id(), canary.candidate().version(), canary.weightPercent());
                });
            }
        } catch (RuntimeException ex) {
            started.values().forEach(MultiAppHost::closeQuietly);
            throw ex;
        }
        return new MultiAppHost(started, Set.copyOf(appIds), Map.copyOf(canaryWeights));
    }

    /** The hosted runtime for {@code appId}, or throws {@code TQL-APP-4040} if it is not hosted. */
    public TesseraqlRuntime app(String appId) {
        TesseraqlRuntime runtime = runtimes.get(appId);
        if (runtime == null) {
            throw new TqlException(UNKNOWN_APP, "App is not hosted: " + appId);
        }
        return runtime;
    }

    /** The HTTP port the given app's active version is listening on. */
    public int port(String appId) {
        return app(appId).port();
    }

    public Set<String> appIds() {
        return appIds;
    }

    /** Whether the app has a staged canary candidate receiving a share of traffic. */
    public boolean hasCanary(String appId) {
        return canaryWeights.containsKey(appId);
    }

    /** The percentage of traffic the app's canary candidate should receive (0 if none). */
    public int canaryWeight(String appId) {
        return canaryWeights.getOrDefault(appId, 0);
    }

    /** The HTTP port of the app's canary candidate; only valid when {@link #hasCanary} is true. */
    public int canaryPort(String appId) {
        return app(appId + CANARY_SLOT).port();
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
