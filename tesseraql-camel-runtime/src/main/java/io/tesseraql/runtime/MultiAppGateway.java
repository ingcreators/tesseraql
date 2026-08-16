package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.operations.app.AppCatalog;
import io.tesseraql.operations.app.InstalledApp;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A single-port front that aggregates every app hosted by a {@link MultiAppHost} (design ch. 32.7).
 *
 * <p>Each app still runs in its own isolated runtime on an internal port. The gateway routes a
 * request to an app by, in order: the {@code Host} header (when the app declares hostnames in its
 * catalog entry), then the {@code /apps/<appId>/<path>} path prefix. Host routing forwards the full
 * path; prefix routing forwards it too, because the app is started serving the prefix it is fronted
 * under (docs/base-path.md decision 5). Unmatched requests get 404.
 *
 * <h2>The gateway is a route, not a rewrite</h2>
 *
 * <p>Under docs/suite-architecture.md decision 12 every deployment is a suite, so every request in
 * every deployment passes through here. Decision 13 draws the line that follows from it: <b>the
 * gateway routes, the ingress protects</b>. It fronts applications the operator installed, behind
 * whatever reverse proxy the deployment already runs, so body limits, rate limiting and TLS
 * termination belong there and not here.
 *
 * <p>The relay is {@code vertx-http-proxy} rather than a copy loop of our own. The hand-rolled
 * version was measured on 2026-08-16 and was not transparent in three ways, each of which is the
 * kind of defect a proxy library exists to have already solved:
 *
 * <ul>
 *   <li><b>Chunked answers lost their bodies.</b> {@code com.sun.net.httpserver} reads a response
 *       length of {@code 0} as "chunked" and {@code -1} as "no body at all"; an app that declared
 *       no {@code Content-Length} produced {@code -1}, which was relayed verbatim as {@code -1}.
 *       Every streaming export and every event stream answered 200, with the right headers, and
 *       nothing after them.</li>
 *   <li><b>Events arrived in bursts.</b> With the length fixed, the copy loop never flushed, so
 *       {@code ChunkedOutputStream} held frames until its 4 KB buffer filled or the stream closed —
 *       "working, but late", which decision 13 named as the hardest failure to diagnose.</li>
 *   <li><b>Protocol translation was absent.</b> The outbound client negotiated h2c with the app and
 *       the response headers were copied into an HTTP/1.1 answer unchanged, emitting the HTTP/2
 *       {@code :status} pseudo-header as an HTTP/1.1 field name — which is not a legal token.</li>
 * </ul>
 *
 * <h2>What is still ours</h2>
 *
 * <p><b>Ingress header stripping</b> stays, because it answers a different threat than the bounds
 * decision 13 removed: an app may be trusted while the caller reaching it is not. Hop-by-hop
 * correctness and body framing move to the library — applying our own {@code Content-Length} or
 * {@code Transfer-Encoding} rules on top of a proxy that owns framing is how a relay corrupts a
 * body, so the list kept here is the posture half only.
 *
 * <h2>The event loop is the execution model now</h2>
 *
 * <p>The previous front ran a virtual thread per request, which made blocking safe by default:
 * a blocked relay parked one cheap thread. Vert.x inverts that. Nothing on the request path may
 * block — routing is map lookups, the entitlement check reads an in-memory record, and the proxy
 * is non-blocking end to end. Work added here later that reads a database or a file must move to
 * {@code executeBlocking}, or it stalls every other connection sharing the loop. In exchange a
 * long-lived stream costs no thread at all rather than a parked one.
 */
public final class MultiAppGateway implements AutoCloseable {

    /**
     * How the gateway addresses the apps behind it — a deployment's choice, not a per-app one
     * (docs/app-isolation-model.md decision 2).
     *
     * <p>The two are exclusive on purpose. Serving both at once means an app reached on its own
     * hostname is *also* reachable on the shared origin, so a browser session taken through the
     * prefix carries across every app the gateway fronts — which is exactly the separation
     * declaring hostnames was meant to obtain. Whichever mode is chosen, the other addressing
     * answers 404.
     */
    public enum Mode {
        /**
         * One origin, {@code /apps/<appId>/} per app: related applications of one organization,
         * sharing a session by design.
         */
        SUITE,
        /**
         * A hostname per app: applications that must not see each other's sessions. Every app
         * must declare at least one hostname, or it would have no address at all.
         */
        ISOLATED
    }

    private static final Logger LOG = LoggerFactory.getLogger(MultiAppGateway.class);
    private static final String PREFIX = SuiteRelay.PREFIX;
    private static final long START_TIMEOUT_SECONDS = 60;

    /** An isolated-hosting app that declares no hostname would be started and unreachable. */
    private static final io.tesseraql.core.error.TqlErrorCode NO_HOSTNAME = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.APP, 5003);

    private final MultiAppHost host;
    private final Vertx vertx;
    private final HttpClient client;
    private final HttpServer server;
    private final int port;
    private final SuiteRelay relay;

    private MultiAppGateway(MultiAppHost host, List<InstalledApp> hostedApps, Mode mode,
            int frontPort) {
        this.host = host;
        Map<String, String> hosts = new java.util.HashMap<>();
        Map<String, InstalledApp> byId = new java.util.HashMap<>();
        for (InstalledApp app : hostedApps) {
            byId.put(app.id(), app);
            for (String hostName : app.hosts()) {
                hosts.put(hostName.toLowerCase(Locale.ROOT), app.id());
            }
        }
        this.vertx = Vertx.vertx();
        this.client = vertx.createHttpClient();
        this.relay = new SuiteRelay(client, mode, hosts, byId, this::targetPort);
        this.server = vertx.createHttpServer(SuiteRelay.frontOptions(frontPort));
        server.requestHandler(relay::handle);
        try {
            this.port = server.listen()
                    .toCompletionStage().toCompletableFuture()
                    .get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .actualPort();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            closeQuietly();
            throw new IllegalStateException("Interrupted starting the gateway", interrupted);
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException failed) {
            closeQuietly();
            throw new IllegalStateException("Could not start the gateway on port " + frontPort,
                    failed);
        }
    }

    /**
     * Hosts all catalogued apps and fronts them on {@code frontPort} (0 picks an ephemeral port),
     * addressed as {@code mode} says.
     *
     * <p>{@link Mode#ISOLATED} refuses to start when an app declares no hostname: it would be
     * catalogued, started, and unreachable — a silence worth failing on, since the deployment
     * that chose per-host addressing is the one that cares about which app answers where.
     */
    public static MultiAppGateway start(java.nio.file.Path installRoot, int frontPort, Mode mode) {
        List<InstalledApp> catalogued = new AppCatalog(installRoot).list();
        if (mode == Mode.ISOLATED) {
            List<String> addressless = catalogued.stream()
                    .filter(app -> app.hosts().isEmpty())
                    .map(InstalledApp::id)
                    .toList();
            if (!addressless.isEmpty()) {
                throw new TqlException(NO_HOSTNAME, "Isolated hosting addresses each app by"
                        + " hostname, and these declare none: " + String.join(", ", addressless)
                        + ". Declare hostnames in the catalog, or host them as a suite.");
            }
        }
        // Suite mode forwards the prefix, so each app is started serving it; isolated mode gives
        // each app its own origin and no prefix at all (docs/base-path.md decision 5).
        // The session cookie is the gateway's call, not the applications'
        // (docs/base-path.md decision 4): a suite is one sign-in across one origin, so the
        // cookie is issued at the root of it rather than scoped to each app's prefix. Isolated
        // hosting gives every application its own origin, where "/" is already its own alone.
        MultiAppHost host = MultiAppHost.start(installRoot,
                appId -> mode == Mode.SUITE ? PREFIX + appId : null, "/");
        try {
            List<InstalledApp> hosted = catalogued.stream()
                    .filter(app -> host.appIds().contains(app.id()))
                    .toList();
            return new MultiAppGateway(host, hosted, mode, frontPort);
        } catch (RuntimeException ex) {
            host.close();
            throw ex;
        }
    }

    public int port() {
        return port;
    }

    public Set<String> appIds() {
        return host.appIds();
    }

    /**
     * The internal port {@code appId} serves on, for the differential test's "direct" leg: the
     * same request has to be answerable both here and through the gateway, and comparing the two
     * is what makes "the gateway is a route, not a rewrite" checkable rather than asserted.
     */
    int appPort(String appId) {
        return host.port(appId);
    }

    /** Resolves the port for {@code appId}, splitting traffic to a canary candidate by its weight. */
    private int targetPort(String appId) {
        int stablePort = host.port(appId);
        if (host.hasCanary(appId)
                && java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < host
                        .canaryWeight(appId)) {
            return host.canaryPort(appId);
        }
        return stablePort;
    }

    @Override
    public void close() {
        closeQuietly();
        host.close();
    }

    /**
     * Everything this instance opened, in the order it was opened: the front server, the outbound
     * client, then the Vert.x instance carrying the event loops. A gateway restart used to
     * accumulate the client's pool and the server's executor, so closing all of it is the point.
     */
    private void closeQuietly() {
        try {
            if (server != null) {
                server.close().toCompletionStage().toCompletableFuture()
                        .get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException ignored) {
            LOG.debug("Gateway server did not close cleanly", ignored);
        }
        try {
            if (client != null) {
                client.close().toCompletionStage().toCompletableFuture()
                        .get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException ignored) {
            LOG.debug("Gateway client did not close cleanly", ignored);
        }
        try {
            if (vertx != null) {
                vertx.close().toCompletionStage().toCompletableFuture()
                        .get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException ignored) {
            LOG.debug("Gateway Vert.x instance did not close cleanly", ignored);
        }
    }
}
