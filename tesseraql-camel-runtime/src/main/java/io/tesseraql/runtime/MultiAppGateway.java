package io.tesseraql.runtime;

import io.tesseraql.operations.app.InstalledApp;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A single-port front that aggregates every app hosted by a {@link MultiAppHost} (design ch. 32.7).
 *
 * <p>Each app still runs in its own isolated runtime on an internal port. The gateway routes a
 * request to the app whose catalogued prefix addresses it, longest first; the full path is
 * forwarded, because the app is started serving the prefix it is fronted under (docs/base-path.md
 * decision 5). Unmatched requests get 404.
 *
 * <h2>The gateway is a route, not a rewrite</h2>
 *
 * <p>Under docs/stack-architecture.md decision 12 every deployment is a stack, so every request in
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
     * docs/stack-architecture.md decision 16 gives for the host's own settings: the list grows, and
     * a value in position four says nothing at the call site about what it is. This is the
     * gateway's own block and is not decision 16's host context object, which carries what only
     * the host can know about each <em>runtime</em> — that arrives with slice 3.
     *
     * @param http2          serve and forward cleartext HTTP/2. Off by default: the previous front
     *                       spoke HTTP/1.1 only, so this is new behaviour rather than restored
     *                       behaviour. It moves both hops together, because enabling it at one end
     *                       alone breaks request framing (see {@link StackRelay#frontOptions})
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

    /**
     * TQL-APP-4215: {@code root.redirect} names an application the stack does not hold.
     *
     * <p>Validated at start against the full membership, before {@code --app-name} narrowing —
     * the file describes the stack and the flag filters a run — so a typo is one loud refusal
     * rather than a redirect onto a 404 (docs/stack-architecture.md decision 24).
     */
    static final io.tesseraql.core.error.TqlErrorCode UNKNOWN_ROOT_REDIRECT = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.APP, 4215);

    /** Where {@code /} lands when the stack file names no {@code root.redirect} application. */
    static final String PORTAL_TARGET = "/_tesseraql/portal";

    private final MultiAppHost host;
    private final Vertx vertx;
    private final HttpClient client;
    private final HttpServer server;
    private final int port;
    private final StackRelay relay;
    /**
     * Converges the host to the install root's files while serving (docs/runtime-replace.md).
     * Only where a catalogue exists — an install root is the deployment shape with versions and
     * a ledger; a workspace of source trees keeps restart-to-deploy, and {@code dev} has
     * {@code --watch}, which is a different loop (routes, not versions). {@code null} otherwise.
     */
    private final StackReconciler reconciler;

    private MultiAppGateway(MultiAppHost host, List<InstalledApp> hostedApps,
            java.nio.file.Path installRoot, Settings settings, int frontPort,
            String rootTarget) {
        this.host = host;
        this.vertx = Vertx.vertx();
        this.client = vertx.createHttpClient(StackRelay.outboundOptions(settings.http2()));
        // Every per-app lookup is the host's live slot state (docs/runtime-replace.md): a
        // replace swaps which runtime, which entry and which strip set answer for a member, and
        // the relay reads all three per request rather than from a start-time copy. Membership
        // itself is the start-time list — adding or removing an application is a stack deploy.
        this.relay = new StackRelay(client,
                hostedApps.stream().map(InstalledApp::name)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                host::entry, host::ingressStrip,
                settings.trustedProxies(), this::targetPort, host::surfacePort, rootTarget);
        this.server = vertx.createHttpServer(
                StackRelay.frontOptions(frontPort, settings.http2()));
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
        this.reconciler = java.nio.file.Files.isRegularFile(installRoot.resolve("catalog.json"))
                ? new StackReconciler(installRoot, host, reconcileSweep(installRoot))
                : null;
    }

    /**
     * Hosts every app the directory holds and fronts them on {@code frontPort} (0 picks an
     * ephemeral port), each addressed as {@code /<name>/}.
     */
    public static MultiAppGateway start(java.nio.file.Path installRoot, int frontPort) {
        return start(installRoot, frontPort, new Settings());
    }

    /** As {@link #start(java.nio.file.Path, int)}, with the front door's settings. */
    public static MultiAppGateway start(java.nio.file.Path installRoot, int frontPort,
            Settings settings) {
        return start(installRoot, frontPort, settings, null);
    }

    /**
     * As {@link #start(java.nio.file.Path, int, Settings)}, narrowed to one member of the stack
     * when {@code appName} is non-null — {@code tesseraql host --app-name}.
     *
     * <p>Narrowing is a filter, never a second deployment shape: the member keeps the address the
     * catalogue gives it, so serving it narrowed and serving it beside its neighbours emit the
     * same URLs (docs/stack-architecture.md decision 12). A name the stack does not hold is
     * refused with the members that would have worked.
     */
    public static MultiAppGateway start(java.nio.file.Path installRoot, int frontPort,
            Settings settings, String appName) {
        return start(installRoot, frontPort, settings, appName, null);
    }

    /**
     * As {@link #start(java.nio.file.Path, int, Settings, String)}, with the development loop's
     * decisions — the embedded database's coordinate and the default external origin
     * (docs/cli-surface.md decisions 4 and 4b). Null for production, where neither exists.
     */
    public static MultiAppGateway start(java.nio.file.Path installRoot, int frontPort,
            Settings settings, String appName, DevMode dev) {
        // Whatever the directory holds: a catalogue, or application homes with no catalogue at
        // all (docs/cli-surface.md Decision 2). The entries are shaped the same either way, so
        // nothing below needs to know which it was.
        List<InstalledApp> catalogued = io.tesseraql.operations.app.AppDirectory.applications(
                io.tesseraql.operations.app.AppDirectory.resolve(installRoot));
        // The stack's file, read once on the gateway path: root.redirect is validated here,
        // against the FULL membership before any narrowing — the file describes the stack and
        // the flag filters a run. A narrowed-away target still redirects and then 404s, exactly
        // like every other link to a narrowed-away neighbour (docs/root-portal.md).
        io.tesseraql.operations.app.StackSettings stackSettings = io.tesseraql.operations.app.StackSettings
                .load(installRoot);
        List<InstalledApp> members = catalogued;
        String rootTarget = stackSettings.rootRedirect()
                .map(name -> {
                    if (members.stream().noneMatch(app -> name.equals(app.name()))) {
                        throw new io.tesseraql.core.error.TqlException(UNKNOWN_ROOT_REDIRECT,
                                io.tesseraql.operations.app.StackSettings.FILE_NAME
                                        + " names root.redirect: '" + name + "', and the stack"
                                        + " holds no application by that name. It holds: "
                                        + members.stream().map(InstalledApp::name)
                                                .collect(java.util.stream.Collectors
                                                        .joining(", "))
                                        + ". Correct the name, or remove root.redirect to let /"
                                        + " land on the portal.");
                    }
                    return "/" + name;
                })
                .orElse(PORTAL_TARGET);
        if (appName != null) {
            List<InstalledApp> named = catalogued.stream()
                    .filter(app -> appName.equals(app.name()))
                    .toList();
            if (named.isEmpty()) {
                throw new io.tesseraql.core.error.TqlException(MultiAppHost.UNKNOWN_APP,
                        "The stack holds no application named '" + appName + "'. It holds: "
                                + catalogued.stream().map(InstalledApp::name)
                                        .collect(java.util.stream.Collectors.joining(", "))
                                + ".");
            }
            catalogued = named;
        }
        // The session cookie is the gateway's call, not the applications' (docs/base-path.md
        // decision 4): a stack is one sign-in across one origin, so the cookie is issued at the
        // root of it rather than scoped to each app's prefix. The address is the catalogue's, and
        // the host reads it from there — each app is started serving the prefix it is fronted
        // under, so it answers at the addresses it emits (decision 5).
        MultiAppHost host = MultiAppHost.start(installRoot, HostContext.stack(), catalogued,
                dev, stackSettings);
        try {
            List<InstalledApp> hosted = catalogued.stream()
                    .filter(app -> host.appNames().contains(app.name()))
                    .toList();
            return new MultiAppGateway(host, hosted, installRoot, settings, frontPort,
                    rootTarget);
        } catch (RuntimeException ex) {
            host.close();
            throw ex;
        }
    }

    public int port() {
        return port;
    }

    /**
     * Watches every hosted application's source tree for the editor-first hot-reload loop —
     * {@code dev --watch}. Per runtime, because {@code watchRoutes} is: a save under one
     * application's {@code web/} bounces that application's route and nothing else's.
     */
    public void watchRoutes(java.util.function.Consumer<String> out) {
        for (String name : host.appNames()) {
            host.app(name).watchRoutes(out);
        }
    }

    public Set<String> appNames() {
        return host.appNames();
    }

    /**
     * The internal port {@code appName} serves on, for the differential test's "direct" leg: the
     * same request has to be answerable both here and through the gateway, and comparing the two
     * is what makes "the gateway is a route, not a rewrite" checkable rather than asserted.
     */
    int appPort(String appName) {
        return host.port(appName);
    }

    /**
     * How often this node reconciles without a filesystem event, from the stack file's
     * {@code stack.reconcile.interval} (an ISO-8601 duration or a plain number of seconds).
     *
     * <p>It lives in {@code tesseraql-stack.yml} because it describes the install root — shared
     * between nodes or not — which is a property of the deployment rather than of any application
     * (docs/hosting.md "A stack on more than one node"). An unreadable or negative value falls back
     * to the default rather than refusing the stack: the sweep is a safety net, and a stack that
     * will not start is worse than one sweeping at the default interval.
     */
    private static java.time.Duration reconcileSweep(java.nio.file.Path installRoot) {
        try {
            return io.tesseraql.operations.app.StackSettings.load(installRoot)
                    .reconcileInterval()
                    .filter(interval -> !interval.isNegative())
                    .orElse(StackReconciler.DEFAULT_SWEEP);
        } catch (RuntimeException unreadable) {
            return StackReconciler.DEFAULT_SWEEP;
        }
    }

    /** Resolves the port for {@code appName}, splitting traffic to a canary candidate by its weight. */
    private int targetPort(String appName) {
        int stablePort = host.port(appName);
        if (host.hasCanary(appName)
                && java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < host
                        .canaryWeight(appName)) {
            return host.canaryPort(appName);
        }
        return stablePort;
    }

    /**
     * The host whose slots this gateway routes to — the surface the replace operations live on
     * (docs/runtime-replace.md). The reconciler drives it in its slice; tests drive it directly.
     */
    public MultiAppHost host() {
        return host;
    }

    /**
     * The ordered stop (docs/runtime-replace.md, the stack's own stop). First the relay's
     * readiness flips to 503 while liveness stays 200 — the orchestrator's contract, "stop
     * routing to me, do not kill me" — and the relay keeps serving everything, new requests
     * included, while its in-flight count drains to zero under a bound derived from the members'
     * own declared {@code tesseraql.shutdown.timeout}s. Only then does the front close, the
     * runtimes drain under their own bounds as today, and the client and Vert.x follow. A
     * long-lived stream is an in-flight request that never ends; the derived bound cuts it,
     * which is the same deliberate boundary as the replace's.
     */
    @Override
    public void close() {
        // The reconciler first: a stop must not race a deploy landing mid-drain, and a closed
        // watcher simply leaves the files for the next start's boot-time reconciliation.
        if (reconciler != null) {
            reconciler.close();
        }
        relay.beginDrain();
        java.time.Duration bound = host.drainBound();
        int inFlight = relay.inFlight();
        LOG.info("Stack stopping: readiness now answers 503; draining {} in-flight request(s)"
                + " for up to {}", inFlight, bound);
        long deadline = System.nanoTime() + bound.toNanos();
        while (relay.inFlight() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (relay.inFlight() > 0) {
            LOG.warn("Stack stop drain bound {} reached with {} request(s) still in flight;"
                    + " closing the front now", bound, relay.inFlight());
        }
        closeFront();
        host.close();
        closeClientAndVertx();
    }

    /** The boot-failure path: nothing is in flight yet, so no drain — just release what opened. */
    private void closeQuietly() {
        closeFront();
        closeClientAndVertx();
    }

    private void closeFront() {
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
    }

    /**
     * The outbound client, then the Vert.x instance carrying the event loops. A gateway restart
     * used to accumulate the client's pool and the server's executor, so closing all of it is
     * the point.
     */
    private void closeClientAndVertx() {
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
