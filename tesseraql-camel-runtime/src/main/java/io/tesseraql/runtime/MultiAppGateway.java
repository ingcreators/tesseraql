package io.tesseraql.runtime;

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
     * The deployment's choices about the front door.
     *
     * <p>A record rather than more positional arguments, on the reasoning
     * docs/suite-architecture.md decision 16 gives for the host's own settings: the list grows, and
     * a value in position four says nothing at the call site about what it is. This is the
     * gateway's own block and is not decision 16's host context object, which carries what only
     * the host can know about each <em>runtime</em> — that arrives with slice 3.
     *
     * @param http2          serve and forward cleartext HTTP/2. Off by default: the previous front
     *                       spoke HTTP/1.1 only, so this is new behaviour rather than restored
     *                       behaviour. It moves both hops together, because enabling it at one end
     *                       alone breaks request framing (see {@link SuiteRelay#frontOptions})
     * @param trustedProxies the addresses whose forwarded headers are the edge's rather than a
     *                       caller's. Empty by default, which strips nothing — see
     *                       {@link TrustedProxies} for why the opposite reading would be wrong
     */
    public record Settings(boolean http2, TrustedProxies trustedProxies) {

        /** Every front-door default. */
        public Settings() {
            this(false, TrustedProxies.NONE);
        }

        /** The protocol, with no edge named. */
        public Settings(boolean http2) {
            this(http2, TrustedProxies.NONE);
        }

        /**
         * The choices {@code tesseraql host} exposes: whether to serve HTTP/2, and the operator's
         * edge as {@code 10.0.0.0/8,192.168.1.5}.
         */
        public Settings(boolean http2, String trustedProxies) {
            this(http2, TrustedProxies.parse(trustedProxies));
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(MultiAppGateway.class);
    private static final long START_TIMEOUT_SECONDS = 60;

    private final MultiAppHost host;
    private final Vertx vertx;
    private final HttpClient client;
    private final HttpServer server;
    private final int port;
    private final SuiteRelay relay;

    private MultiAppGateway(MultiAppHost host, List<InstalledApp> hostedApps,
            java.nio.file.Path installRoot, Settings settings, int frontPort) {
        this.host = host;
        Map<String, InstalledApp> byId = new java.util.HashMap<>();
        Map<String, Set<String>> strip = new java.util.HashMap<>();
        for (InstalledApp app : hostedApps) {
            byId.put(app.id(), app);
            strip.put(app.id(), ingressStripHeaders(installRoot, app));
        }
        this.vertx = Vertx.vertx();
        this.client = vertx.createHttpClient(SuiteRelay.outboundOptions(settings.http2()));
        this.relay = new SuiteRelay(client, byId, strip,
                settings.trustedProxies(), this::targetPort);
        this.server = vertx.createHttpServer(
                SuiteRelay.frontOptions(frontPort, settings.http2()));
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
     * Hosts every app the directory holds and fronts them on {@code frontPort} (0 picks an
     * ephemeral port), each addressed as {@code /apps/<id>/}.
     */
    public static MultiAppGateway start(java.nio.file.Path installRoot, int frontPort) {
        return start(installRoot, frontPort, new Settings());
    }

    /** As {@link #start(java.nio.file.Path, int)}, with the front door's settings. */
    public static MultiAppGateway start(java.nio.file.Path installRoot, int frontPort,
            Settings settings) {
        // Whatever the directory holds: a catalogue, or application homes with no catalogue at
        // all (docs/cli-surface.md Decision 2). The entries are shaped the same either way, so
        // nothing below needs to know which it was.
        List<InstalledApp> catalogued = io.tesseraql.operations.app.AppDirectory.applications(
                io.tesseraql.operations.app.AppDirectory.resolve(installRoot));
        // Each app is started serving the prefix it is fronted under, so it answers at the
        // addresses it emits (docs/base-path.md decision 5). The session cookie is the gateway's
        // call, not the applications' (decision 4): a suite is one sign-in across one origin, so
        // the cookie is issued at the root of it rather than scoped to each app's prefix.
        Map<String, String> basePaths = catalogued.stream().collect(java.util.stream.Collectors
                .toMap(InstalledApp::id, InstalledApp::basePath, (first, second) -> first));
        MultiAppHost host = MultiAppHost.start(installRoot, basePaths::get, "/");
        try {
            List<InstalledApp> hosted = catalogued.stream()
                    .filter(app -> host.appIds().contains(app.id()))
                    .toList();
            return new MultiAppGateway(host, hosted, installRoot, settings, frontPort);
        } catch (RuntimeException ex) {
            host.close();
            throw ex;
        }
    }

    /**
     * The headers to drop from a request bound for {@code app} when it did not come from a named
     * edge: the mTLS forwarded header its configuration says to believe. Best-effort — an app
     * whose config cannot be read strips nothing extra, because the alternative is refusing to
     * host it over a header it may not even use.
     */
    static Set<String> ingressStripHeaders(java.nio.file.Path installRoot, InstalledApp app) {
        try {
            java.nio.file.Path appHome = installRoot.resolve(app.path()).normalize();
            return new io.tesseraql.yaml.manifest.ManifestLoader().load(appHome).config()
                    .getString("tesseraql.security.mtls.forwardedHeader")
                    .map(header -> Set.of(header.toLowerCase(Locale.ROOT)))
                    .orElseGet(Set::of);
        } catch (RuntimeException unreadable) {
            LOG.warn("Could not read '{}' configuration for ingress header stripping: {}",
                    app.id(), unreadable.getMessage());
            return Set.of();
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
