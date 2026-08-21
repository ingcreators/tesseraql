package io.tesseraql.runtime;

import io.tesseraql.operations.app.InstalledApp;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.httpproxy.Body;
import io.vertx.httpproxy.HttpProxy;
import io.vertx.httpproxy.OriginRequestProvider;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyRequest;
import io.vertx.httpproxy.ProxyResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gateway's request half: decide which app answers, refuse what no app answers, and relay
 * everything else verbatim (docs/stack-architecture.md decision 13).
 *
 * <p>Separate from {@link MultiAppGateway} because the two answer different questions. The gateway
 * owns a {@link MultiAppHost} — real runtimes, real datasources, a Postgres container in any test
 * that starts one. The relay owns only the hop, and the hop is where the failures a proxy causes
 * live: a stream that arrives late, a chunked body that never arrives, a header a caller must not
 * be able to set. Those want an origin a test can make misbehave on demand, which a hosted
 * application is not.
 *
 * <p><b>This runs on an event loop.</b> Every decision here is an in-memory lookup on purpose.
 * Adding a call that reads a database, a file or a lock to this path stalls every other connection
 * sharing the loop — it must go to {@code executeBlocking} instead.
 *
 * <h2>The per-app state is live</h2>
 *
 * <p>A replace (docs/runtime-replace.md) swaps which runtime answers for a member while the stack
 * serves. The relay therefore resolves everything per request through the functions the gateway
 * wires to the host's live slots — the port, the catalogue entry, the ingress-strip set — and
 * caches its proxies by <em>member</em>, never by port: a proxy's origin is re-resolved on every
 * request, so a retired runtime's port simply stops being answered with, and there is no
 * per-port cache to evict. Membership itself stays start-time (adding or removing an application
 * is a stack deploy), which is why the member set is still a snapshot.
 */
final class StackRelay {

    private static final Logger LOG = LoggerFactory.getLogger(StackRelay.class);

    /**
     * The front server's options.
     *
     * <p>Cleartext HTTP/2 is a deployment's choice and off by default, because the front this
     * replaced was {@code com.sun.net.httpserver}, which speaks HTTP/1.1 only — no client ever
     * reached a hosted app over h2c through the gateway, so serving it is new behaviour rather
     * than restored behaviour and an operator should ask for it.
     *
     * <p>It cannot be turned on at one end: with h2c accepted at the front and an HTTP/1.1 hop to
     * the app, a request body arriving over HTTP/2 is piped into an outbound request that has
     * neither a declared length nor chunked framing, and Vert.x refuses the write on the event
     * loop. So one setting moves both ends together — see
     * {@link #outboundOptions(boolean)}.
     */
    static HttpServerOptions frontOptions(int port, boolean http2) {
        return new HttpServerOptions().setPort(port).setHttp2ClearTextEnabled(http2);
    }

    /**
     * The outbound client's options, the other half of the same setting.
     *
     * <p>The upgrade is negotiated on a <b>preflight request</b>, and that detail is load-bearing.
     * A plain h2c upgrade carries the negotiation on the first real request, which is sent as
     * HTTP/1.1 — so a request that arrives at the front over HTTP/2 and is piped into it has
     * neither a declared length nor chunked framing, and Vert.x refuses the write on the event
     * loop. Measured, not reasoned: it is the same {@code IllegalStateException} that made
     * serving h2c at one end only unshippable. A preflight negotiates on its own exchange, so
     * every request that carries a body travels on HTTP/2 from the start.
     *
     * <p>An application that does not offer h2c answers the preflight over HTTP/1.1 and the hop
     * continues over HTTP/1.1, so enabling this cannot make a hosted application unreachable.
     */
    static HttpClientOptions outboundOptions(boolean http2) {
        return outboundOptions(http2, 10);
    }

    /**
     * As {@link #outboundOptions(boolean)}, sized to what the front door admits per member
     * (docs/http-threading.md decision 5).
     *
     * <p>Both protocol modes are given the same number on purpose. The client's own defaults are
     * five connections per origin on HTTP/1 and one connection with <em>unlimited</em> multiplexed
     * streams on h2c, so the concurrency the front door could reach a member with changed by an
     * order of magnitude — from five to unbounded — when an operator turned on a protocol flag.
     * A protocol setting deciding a capacity setting is the kind of coupling that is only ever
     * discovered under load.
     */
    static HttpClientOptions outboundOptions(boolean http2, int maxConcurrentPerMember) {
        return outboundOptions(http2, maxConcurrentPerMember, 0);
    }

    /**
     * As {@link #outboundOptions(boolean, int)}, reclaiming a forward whose member has gone quiet
     * for {@code readIdleSeconds} ({@code 0} leaves it disabled, which is the default).
     *
     * <p><strong>Deliberately opt-in, and it cannot safely be defaulted.</strong> A member that
     * has hung and a member running a legitimately long query look identical from here: both
     * accept the request and send nothing until they are done. Event streams are the easy half —
     * they heartbeat every 25 seconds — but a report with a raised {@code timeoutSeconds} can be
     * silent for minutes and is not misbehaving. So the framework cannot pick a number that is
     * both a hang detector and safe; an operator who knows their slowest legitimate response can.
     * Without it, the per-member share above is what contains a hung member: it takes that
     * member's own permits and nothing else, and the stack answers 503 for it rather than
     * degrading for everyone.
     */
    static HttpClientOptions outboundOptions(boolean http2, int maxConcurrentPerMember,
            int readIdleSeconds) {
        int limit = Math.max(1, maxConcurrentPerMember);
        HttpClientOptions options = new HttpClientOptions();
        if (readIdleSeconds > 0) {
            options.setReadIdleTimeout(readIdleSeconds);
        }
        return http2
                ? options.setProtocolVersion(HttpVersion.HTTP_2)
                        .setHttp2ClearTextUpgrade(true)
                        .setHttp2MultiplexingLimit(limit)
                : options;
    }

    /**
     * The per-member bound, which is a pool setting rather than a client setting.
     *
     * <p>Vert.x 5 moved connection pooling out of {@code HttpClientOptions} into its own object,
     * and the split suits this: the number is one declared bound taken in the relay
     * (docs/http-threading.md decision 5), and it now has somewhere of its own to be declared.
     * Both protocol modes are sized to it, so turning on h2c still cannot change a capacity.
     *
     * <p><strong>The wait queue is bounded to the same number.</strong> Decision 5 named an
     * unbounded outbound queue as one of the three things wrong at the front door and contained it
     * with a permit rather than closing it, because {@code maxWaitQueueSize} was a client setting
     * nothing else was touching. It is a pool setting now, and an unbounded queue in front of a
     * bound is the queue this campaign removed inside a runtime, one hop earlier.
     */
    static io.vertx.core.http.PoolOptions outboundPool(int maxConcurrentPerMember) {
        int limit = Math.max(1, maxConcurrentPerMember);
        return new io.vertx.core.http.PoolOptions()
                .setHttp1MaxSize(limit)
                .setHttp2MaxSize(limit)
                .setMaxWaitQueueSize(limit);
    }

    /** The default tenant header checked for app entitlement at the front door (ch. 32.8). */
    private static final String TENANT_HEADER = "X-Tenant-Id";

    /** TQL-APP-4030: the request's tenant is not on the app's entitlement list (HTTP 403). */
    private static final String NOT_ENTITLED = "TQL-APP-4030";

    /** TQL-APP-5020: the gateway failed to forward the request to the app's runtime (HTTP 502). */
    private static final String GATEWAY_ERROR = "TQL-APP-5020";

    /**
     * TQL-RATE-4294: the front door already has this member's share of forwards in flight
     * (HTTP 503). The member's own {@code tesseraql.http.maxInFlight} is a different bound at a
     * different place; this one keeps a slow member from consuming the front door for everyone.
     */
    private static final String MEMBER_AT_CAPACITY = "TQL-RATE-4294";

    /** The surface's key in the per-app proxy lookups; {@code #} is outside every legal name. */
    private static final String SURFACE = "#portal";

    /**
     * The attachment the origin provider sets the moment a connection to the origin exists, so
     * the retry interceptor can tell "never reached the origin" from "died talking to it".
     */
    private static final String CONNECTED = "tesseraql.relay.connected";

    /**
     * The attachment naming the port the send actually targeted, so the retry interceptor can
     * ask whether the slot has since moved away from it — the discriminator between an
     * application's own 503 and one minted by a retiring runtime's suspended consumer.
     */
    private static final String TARGET = "tesseraql.relay.target";

    private final HttpClient client;
    /** The stack's members — a start-time snapshot on purpose; see the class javadoc. */
    private final Set<String> memberNames;
    /** Member name to its live catalogue entry — after a replace, the new version's. */
    private final Function<String, InstalledApp> entryOf;
    /** Member name to the forwarded header its <em>current</em> version says to believe. */
    private final Function<String, Set<String>> stripOf;
    private final TrustedProxies trustedProxies;
    /** App name to the internal port that answers for it now — canary weighting included. */
    private final ToIntFunction<String> portOf;
    /**
     * The stack surface runtime's internal port — the origin scope's {@code /_tesseraql/*} and
     * {@code /assets/*} (docs/root-portal.md) — or {@code null} in relay tests that stand no
     * surface up; production always has one.
     */
    private final IntSupplier surfacePort;
    /**
     * Where {@code /} 307s to: {@code /<name>} when the stack file names a {@code root.redirect}
     * application, else the portal (docs/stack-architecture.md decision 24). Resolved and
     * validated at start — the relay never looks anything up per request. {@code null} in relay
     * tests that exercise routing without the root behaviour.
     */
    private final String rootTarget;
    /**
     * One proxy per member (and one for the surface), its origin re-resolved per request through
     * {@link #portOf} — which is what keeps a swap effective mid-stream and leaves no retired
     * port cached anywhere.
     */
    private final Map<String, HttpProxy> proxies = new ConcurrentHashMap<>();
    /**
     * Requests accepted and not yet fully answered. The stack's stop drains this to zero before
     * the front closes (docs/runtime-replace.md, the stack's own stop) — counted here because the
     * relay is the one place every request passes through.
     */
    private final AtomicInteger inFlight = new AtomicInteger();
    /**
     * Flipped by the gateway's ordered stop: readiness answers 503 while liveness stays 200 —
     * the orchestrator's contract, "stop routing to me, do not kill me". Everything else keeps
     * being served while the in-flight count drains.
     */
    private volatile boolean draining;

    /**
     * How many forwards one member may have in flight (docs/http-threading.md decision 5).
     *
     * <p>There was no declared bound and there was an undeclared one: the outbound client's own
     * pool defaults to five connections per origin, so the front door forwarded at most five
     * requests to a member at a time — fewer than the member's own worker pool — and queued the
     * rest in the client's waiter queue, which is unbounded by default. The effective limit on
     * the whole stack was a number nobody chose and nobody could see, and enabling h2c replaced
     * it with no limit at all.
     */
    private volatile int maxConcurrentPerMember = 10;

    /** One permit set per member, created on first forward to it. */
    private final Map<String, java.util.concurrent.Semaphore> memberPermits = new ConcurrentHashMap<>();

    StackRelay(HttpClient client, Map<String, InstalledApp> appsByName,
            ToIntFunction<String> portOf) {
        this(client, appsByName, Map.of(), TrustedProxies.NONE, portOf, null, null);
    }

    StackRelay(HttpClient client, Map<String, InstalledApp> appsByName,
            Map<String, Set<String>> ingressStripByApp,
            TrustedProxies trustedProxies, ToIntFunction<String> portOf) {
        this(client, appsByName, ingressStripByApp, trustedProxies, portOf, null, null);
    }

    /** The snapshot shape, for tests whose per-app state never changes mid-test. */
    StackRelay(HttpClient client, Map<String, InstalledApp> appsByName,
            Map<String, Set<String>> ingressStripByApp,
            TrustedProxies trustedProxies, ToIntFunction<String> portOf,
            IntSupplier surfacePort, String rootTarget) {
        this(client, Set.copyOf(appsByName.keySet()), Map.copyOf(appsByName)::get,
                stripLookup(Map.copyOf(ingressStripByApp)),
                trustedProxies, portOf, surfacePort, rootTarget);
    }

    private static Function<String, Set<String>> stripLookup(
            Map<String, Set<String>> ingressStripByApp) {
        return name -> ingressStripByApp.getOrDefault(name, Set.of());
    }

    /** The live shape the gateway wires: every per-app lookup answers from the host's slots. */
    StackRelay(HttpClient client, Set<String> memberNames, Function<String, InstalledApp> entryOf,
            Function<String, Set<String>> stripOf, TrustedProxies trustedProxies,
            ToIntFunction<String> portOf, IntSupplier surfacePort, String rootTarget) {
        this.client = client;
        this.memberNames = Set.copyOf(memberNames);
        this.entryOf = entryOf;
        this.stripOf = stripOf;
        this.trustedProxies = trustedProxies;
        this.portOf = portOf;
        this.surfacePort = surfacePort;
        this.rootTarget = rootTarget;
    }

    /**
     * Starts the ordered stop: readiness flips to 503 so a balancer stops sending new traffic,
     * while everything that still arrives is served in full.
     */
    void beginDrain() {
        draining = true;
    }

    /**
     * Sets how many forwards one member may have in flight, before the front starts serving.
     * The outbound client's pool is sized to match, so what this admits the transport can carry
     * without queueing behind it.
     */
    StackRelay maxConcurrentPerMember(int forwards) {
        this.maxConcurrentPerMember = Math.max(1, forwards);
        return this;
    }

    /** Requests accepted and not yet fully answered — what the stop drains to zero. */
    int inFlight() {
        return inFlight.get();
    }

    /**
     * The application whose declared prefix addresses {@code rawPath}, longest first, or null.
     *
     * <p>Every prefix is derived from a name — {@code /<name>}, one segment, the catalogue's one
     * producer — so at most one can match a given path, and the loop keeps longest-first only as a
     * defence should a second producer ever appear. A prefix matches on a segment boundary, so
     * {@code /orders} never answers for {@code /orders-archive}.
     */
    private String appAddressedBy(String rawPath) {
        String best = null;
        String bestPrefix = null;
        for (String name : memberNames) {
            InstalledApp entry = entryOf.apply(name);
            if (entry == null) {
                continue;
            }
            String prefix = entry.basePath();
            if (!addresses(prefix, rawPath)) {
                continue;
            }
            if (bestPrefix == null || prefix.length() > bestPrefix.length()) {
                best = name;
                bestPrefix = prefix;
            }
        }
        return best;
    }

    /**
     * Whether {@code rawPath} is the origin scope's framework claim: the fence, its assets, or
     * the well-known documents — RFC 8414 metadata sits at the bare
     * {@code /.well-known/oauth-authorization-server} because the issuer is the origin with no
     * path component (docs/token-issuance.md decision 6), and the surface runtime is what
     * serves it. The segment starts with a dot, so the address grammar already keeps every
     * member name clear of it.
     */
    private static boolean insideTheOriginFence(String rawPath) {
        return addresses("/_tesseraql", rawPath) || addresses("/assets", rawPath)
                || addresses("/.well-known", rawPath);
    }

    /** Whether {@code prefix} addresses {@code path}: equal, or followed by a segment boundary. */
    private static boolean addresses(String prefix, String path) {
        if (prefix.isEmpty()) {
            return true;
        }
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /** Routes one request to the app that answers for it, or refuses it here. */
    void handle(HttpServerRequest request) {
        // Counted before any answer, released exactly once however the response ends — the
        // close handler covers a caller that hangs up mid-stream, which would otherwise hold
        // the stop's drain open forever.
        inFlight.incrementAndGet();
        AtomicBoolean released = new AtomicBoolean();
        // The member permit rides the same guard rather than a second pair of handlers: Vert.x
        // keeps one endHandler per response, so registering again would replace the drain's own
        // and leak the count the stack's stop waits on.
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Semaphore> held = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                inFlight.decrementAndGet();
                java.util.concurrent.Semaphore permit = held.getAndSet(null);
                if (permit != null) {
                    permit.release();
                }
            }
        };
        request.response().endHandler(v -> release.run());
        request.response().closeHandler(v -> release.run());
        try {
            String rawPath = rawPath(request);
            // The origin's own health, answered by the gateway itself: the operator case a load
            // balancer needs (docs/stack-architecture.md decision 25 names it) sits inside the
            // framework's /_tesseraql/ fence at origin scope, which the name grammar's
            // segment-safety rule keeps unreachable by any application. Liveness is the process
            // answering; readiness is whether new traffic should be routed here, which a
            // draining stack answers no to while it finishes what it accepted.
            if ("/_tesseraql/health/live".equals(rawPath)) {
                request.response().setStatusCode(200)
                        .putHeader("Content-Type", "application/json; charset=utf-8")
                        .end("{\"status\":\"UP\"}");
                return;
            }
            if ("/_tesseraql/health/ready".equals(rawPath)) {
                boolean stopping = draining;
                request.response().setStatusCode(stopping ? 503 : 200)
                        .putHeader("Content-Type", "application/json; charset=utf-8")
                        .end(stopping ? "{\"status\":\"DRAINING\"}" : "{\"status\":\"UP\"}");
                return;
            }
            // The root does exactly one thing — redirect — and configuration chooses only the
            // target (docs/stack-architecture.md decision 24). 307 deliberately: a permanent
            // redirect is cached by browsers past the configuration change that retires it. The
            // query string rides along verbatim.
            if (rootTarget != null && "/".equals(rawPath)) {
                String uri = request.uri();
                int query = uri.indexOf('?');
                request.response().setStatusCode(307)
                        .putHeader("Location",
                                query < 0 ? rootTarget : rootTarget + uri.substring(query))
                        .end();
                return;
            }
            // The origin fence (docs/root-portal.md): origin-scope framework surfaces —
            // sign-in, the account surface, the portal — and their assets are the stack surface
            // runtime's, and the name grammar keeps both segments unreachable by any member
            // (`_tesseraql` by the leading-underscore rule, `assets` as a reserved word).
            // The health pair above deliberately stays the gateway's own answer, so a
            // load-balancer probe does not depend on the surface runtime being up.
            if (surfacePort != null && insideTheOriginFence(rawPath)) {
                proxies.computeIfAbsent(SURFACE, key -> proxyFor(key, surfacePort::getAsInt))
                        .handle(request);
                return;
            }
            // One address per application, and one way to reach it (docs/stack-architecture.md
            // Decision 12). Host-header routing went with independent hosting: it existed so an
            // application could own a whole origin, which is the separation a stack is defined by
            // not having.
            String appName = appAddressedBy(rawPath);
            if (appName == null) {
                respond(request, 404, MultiAppHost.UNKNOWN_APP.toString());
                return;
            }

            // Tenant entitlement at the front door (ch. 32.8): when the request declares its
            // tenant, an app with an entitlement list only serves the tenants on it. Claim-based
            // tenants are still enforced inside the app's own tenancy resolution. The entry is
            // the live one, so a replace's entitlement change takes effect with the swap.
            String tenant = request.getHeader(TENANT_HEADER);
            InstalledApp app = entryOf.apply(appName);
            if (tenant != null && app != null && !app.isEntitled(tenant)) {
                respond(request, 403, NOT_ENTITLED);
                return;
            }

            try {
                portOf.applyAsInt(appName);
            } catch (RuntimeException unknown) {
                respond(request, 404, MultiAppHost.UNKNOWN_APP.toString());
                return;
            }
            // The URI is forwarded verbatim — the app serves the address it is
            // fronted at (docs/base-path.md decision 5) — so there is nothing to rewrite here,
            // only an origin to choose. The proxy resolves the port again itself: the value
            // checked above is only the early 404, never the routing decision, so a swap between
            // the two reads cannot strand the request on a retired runtime.
            // Taken once the member is known and released on the guard above, so a member whose
            // backend has stalled can hold only its own share of the front door. The gateway's
            // own health answered above, before this: a stack that cannot say "that member is
            // busy" is one an orchestrator removes for the silence.
            java.util.concurrent.Semaphore permits = memberPermits.computeIfAbsent(appName,
                    name -> new java.util.concurrent.Semaphore(maxConcurrentPerMember));
            if (!permits.tryAcquire()) {
                request.response().putHeader("Retry-After", "1");
                respond(request, 503, MEMBER_AT_CAPACITY);
                return;
            }
            held.set(permits);
            proxies.computeIfAbsent(appName,
                    name -> proxyFor(name, () -> portOf.applyAsInt(name)))
                    .handle(request);
        } catch (RuntimeException ex) {
            LOG.warn("Gateway error: {}", ex.getMessage());
            respond(request, 502, GATEWAY_ERROR);
        }
    }

    /**
     * The proxy for one member, its origin resolved per request and its send retried once when
     * the origin was never reached — or answered 503 from a slot that has since moved on.
     *
     * <p>No header interceptor beyond the posture half. The gateway used to strip the mTLS
     * forwarded header its apps declare, on the reasoning that applications are trusted while
     * callers are not — but it stripped unconditionally, including the value the edge had just
     * set, so mTLS forwarded-header authentication could not work behind the gateway at all.
     * docs/authentication.md already assigns that duty where it can be discharged: "the edge must
     * overwrite (or strip) the forwardedHeader on every inbound request, and the runtime must not
     * be reachable except through that edge." That is the division this class exists to draw —
     * the gateway routes, the ingress protects.
     *
     * <p>The strip set is installed whenever an edge is named and consulted per request, because
     * a replace can change it: a version that starts declaring a forwarded header gets it
     * stripped from the swap onwards, without the gateway restarting.
     */
    private HttpProxy proxyFor(String appName, IntSupplier port) {
        HttpProxy proxy = HttpProxy.reverseProxy(client)
                .origin(new LiveOrigin(port))
                // First in the chain so the swap-race retry's re-send does not re-enter it;
                // the normalization is idempotent anyway (a stripped path no longer matches).
                .addInterceptor(new ActivationAddress(SURFACE.equals(appName)
                        ? () -> null
                        : () -> {
                            InstalledApp entry = entryOf.apply(appName);
                            return entry == null ? null : entry.basePath();
                        }))
                .addInterceptor(new RetryOnceAcrossTheSwap(port))
                .addInterceptor(new BodylessRequestsHaveZeroLength());
        if (!trustedProxies.isEmpty()) {
            proxy.addInterceptor(new StripUnlessFromATrustedEdge(
                    () -> stripOf.apply(appName), trustedProxies));
        }
        return proxy;
    }

    /**
     * Normalizes the acting-role address (docs/application-roles.md structural decision 5):
     * {@code /<member>/_as/<role>/…} forwards as {@code /<member>/…} with the role handed to the
     * member as the internal {@code Tesseraql-Acting-Role} header, still URL-encoded — the
     * member's activate step decodes and validates it against the caller's own grants, so the
     * relay adds reach, never authority. The client's own copy of the header is removed first,
     * unconditionally, on every proxied request — surface included: unlike the mTLS forwarded
     * header (whose edge-set value must survive, see {@link StripUnlessFromATrustedEdge}), this
     * header is relay-minted and no outside writer is ever legitimate.
     *
     * <p>Member route matching is untouched — the member never sees the segment — and a path
     * without the segment forwards byte-identically to before.
     */
    private record ActivationAddress(java.util.function.Supplier<String> prefixOf)
            implements
                ProxyInterceptor {

        @Override
        public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
            ProxyRequest proxied = context.request();
            proxied.headers().remove(
                    io.tesseraql.pipeline.TesseraqlProperties.ACTING_ROLE_HEADER);
            String prefix = prefixOf.get();
            if (prefix != null) {
                String uri = proxied.getURI();
                int query = uri.indexOf('?');
                String path = query < 0 ? uri : uri.substring(0, query);
                String marker = prefix + "/_as/";
                if (path.startsWith(marker)) {
                    String remainder = path.substring(marker.length());
                    int slash = remainder.indexOf('/');
                    String role = slash < 0 ? remainder : remainder.substring(0, slash);
                    String rest = slash < 0 ? "" : remainder.substring(slash);
                    if (!role.isEmpty()) {
                        proxied.setURI(prefix + rest + (query < 0 ? "" : uri.substring(query)));
                        proxied.headers().set(
                                io.tesseraql.pipeline.TesseraqlProperties.ACTING_ROLE_HEADER, role);
                    }
                }
            }
            return context.sendRequest();
        }
    }

    /**
     * Resolves the origin at send time — the live lookup that makes a swap effective — and
     * records on the context that a connection exists, which is the fact the retry interceptor
     * needs.
     */
    private record LiveOrigin(IntSupplier port) implements OriginRequestProvider {

        @Override
        public Future<io.vertx.core.http.HttpClientRequest> create(ProxyContext context) {
            int target;
            try {
                target = port.getAsInt();
            } catch (RuntimeException unresolvable) {
                return Future.failedFuture(unresolvable);
            }
            context.set(TARGET, target);
            return context.client()
                    .request(new RequestOptions()
                            .setServer(SocketAddress.inetSocketAddress(target, "localhost")))
                    .andThen(connected -> {
                        if (connected.succeeded()) {
                            context.set(CONNECTED, Boolean.TRUE);
                        }
                    });
        }
    }

    /**
     * Closes the swap race (docs/runtime-replace.md): a request can resolve a runtime's port
     * just before its slot is swapped and reach it only after that runtime retired — an error
     * minted by the deploy itself. The race has two legs, and the retry re-resolves the origin
     * (the provider above reads the live lookup) and happens <b>once</b> on each:
     *
     * <ul>
     * <li><b>The connection was never established</b> — the retired runtime's socket is gone.
     * No byte reached it, so nothing can double, and any request is safe to re-send.</li>
     * <li><b>The retired runtime answered 503</b> — its consumer suspended but its socket still
     * accepts, and the suspend gate refuses before any route runs. Retried only when the slot
     * has since moved away from the port that answered (an application's own 503 — a lane at
     * capacity, a readiness roll-up — comes from the port the slot still names, and passes
     * through), and only for a bodyless request: a request body is a stream the first send
     * already consumed, so a bodied request keeps the 503 and its client resubmits.</li>
     * </ul>
     *
     * <p>A request whose connection died mid-flight is <em>not</em> retried — replaying a
     * request the origin may have acted on is a worse defect than the 502 it would save.
     */
    private record RetryOnceAcrossTheSwap(IntSupplier port) implements ProxyInterceptor {

        @Override
        public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
            HttpMethod method = context.request().getMethod();
            boolean replayable = method == HttpMethod.GET || method == HttpMethod.HEAD;
            return context.sendRequest()
                    .compose(response -> {
                        if (replayable && response.getStatusCode() == 503
                                && slotMovedAwayFrom(context)) {
                            response.release();
                            return context.sendRequest();
                        }
                        return Future.succeededFuture(response);
                    })
                    .recover(failure -> {
                        if (context.get(CONNECTED, Boolean.class) == null) {
                            return context.sendRequest();
                        }
                        return Future.failedFuture(failure);
                    });
        }

        private boolean slotMovedAwayFrom(ProxyContext context) {
            Integer answered = context.get(TARGET, Integer.class);
            if (answered == null) {
                return false;
            }
            try {
                return port.getAsInt() != answered;
            } catch (RuntimeException unresolvable) {
                return false;
            }
        }
    }

    /**
     * Gives a bodyless request a definite length of zero before it is relayed.
     *
     * <p>Over HTTP/2 a {@code GET} still ends its stream with a data event, so the proxy pipes an
     * empty body into the outbound request — which has neither a declared length nor chunked
     * framing, and Vert.x refuses the write on the event loop. The request succeeds anyway,
     * because nothing was lost, so the only symptom is a stack trace per request: every page load
     * and every event stream through an h2c front, in the log, forever.
     *
     * <p>Measured rather than reasoned. The first theory was that HTTP/2 omits
     * {@code content-length} and large uploads were the casualty; the body lengths say otherwise —
     * an 8 MB {@code POST} arrives as {@code length=8388608} and relays cleanly, and it is
     * {@code GET length=-1} that throws. An unknown length on a method that cannot carry a body is
     * zero, and saying so is what the relay needs.
     */
    private record BodylessRequestsHaveZeroLength() implements ProxyInterceptor {

        @Override
        public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
            ProxyRequest proxied = context.request();
            boolean carriesNoBody = proxied.getMethod() == HttpMethod.GET
                    || proxied.getMethod() == HttpMethod.HEAD;
            if (carriesNoBody && proxied.getBody() != null && proxied.getBody().length() < 0) {
                proxied.setBody(Body.body(Buffer.buffer()));
            }
            return context.sendRequest();
        }
    }

    /**
     * Drops the app's forwarded mTLS header unless the request came from an address the operator
     * named as their edge.
     *
     * <p>Only installed when an edge is named. The gateway used to strip this header
     * unconditionally, which destroyed the edge's own value along with a forged one and made mTLS
     * forwarded-header authentication unusable behind a gateway at all; naming the edge is what
     * lets the strip tell the two apart. With no edge named there is nothing to compare against,
     * so nothing is stripped and the trust contract stays where {@code authentication.md} puts it.
     *
     * <p>The comparison is against the <em>peer of the connection</em>, never a header — a caller
     * can write {@code X-Forwarded-For}, and cannot write the socket it connected from. The strip
     * set itself is resolved per request, because a replace can change what the current version
     * declares.
     */
    private record StripUnlessFromATrustedEdge(
            java.util.function.Supplier<Set<String>> stripOnIngress, TrustedProxies edges)
            implements
                ProxyInterceptor {

        @Override
        public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
            Set<String> strip = stripOnIngress.get();
            SocketAddress peer = context.request().proxiedRequest().remoteAddress();
            if (!strip.isEmpty()
                    && !edges.includes(peer == null ? null : peer.hostAddress())) {
                MultiMap headers = context.request().headers();
                // Collected first: removing while iterating the names mutates what is being read.
                List<String> present = headers.names().stream()
                        .filter(name -> strip.contains(name.toLowerCase(Locale.ROOT)))
                        .toList();
                present.forEach(headers::remove);
            }
            return context.sendRequest();
        }
    }

    /** The request path without the query, undecoded — routing reads raw segments. */
    private static String rawPath(HttpServerRequest request) {
        String uri = request.uri();
        int query = uri.indexOf('?');
        String path = query < 0 ? uri : uri.substring(0, query);
        return path.isEmpty() ? "/" : path;
    }

    private static void respond(HttpServerRequest request, int status, String code) {
        HttpServerResponse response = request.response();
        if (response.ended()) {
            return;
        }
        response.setStatusCode(status)
                .putHeader("Content-Type", "application/json; charset=utf-8")
                .end("{\"error\":{\"code\":\"" + code + "\"}}");
    }
}
