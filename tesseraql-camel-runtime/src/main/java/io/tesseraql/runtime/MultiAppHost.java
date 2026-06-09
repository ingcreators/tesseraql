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

    private final Map<String, TesseraqlRuntime> runtimes;

    private MultiAppHost(Map<String, TesseraqlRuntime> runtimes) {
        this.runtimes = runtimes;
    }

    /** Starts every catalogued app under {@code installRoot}, each on its own ephemeral port. */
    public static MultiAppHost start(Path installRoot) {
        AppCatalog catalog = new AppCatalog(installRoot);
        Map<String, TesseraqlRuntime> started = new LinkedHashMap<>();
        try {
            for (InstalledApp app : catalog.list()) {
                Path appHome = installRoot.resolve(app.path()).normalize();
                started.put(app.id(), TesseraqlRuntime.start(appHome, freePort()));
                LOG.info("Hosting app {} v{} from {}", app.id(), app.version(), appHome);
            }
        } catch (RuntimeException ex) {
            started.values().forEach(MultiAppHost::closeQuietly);
            throw ex;
        }
        return new MultiAppHost(started);
    }

    /** The hosted runtime for {@code appId}, or throws {@code TQL-APP-4040} if it is not hosted. */
    public TesseraqlRuntime app(String appId) {
        TesseraqlRuntime runtime = runtimes.get(appId);
        if (runtime == null) {
            throw new TqlException(UNKNOWN_APP, "App is not hosted: " + appId);
        }
        return runtime;
    }

    /** The HTTP port the given app is listening on. */
    public int port(String appId) {
        return app(appId).port();
    }

    public Set<String> appIds() {
        return runtimes.keySet();
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
