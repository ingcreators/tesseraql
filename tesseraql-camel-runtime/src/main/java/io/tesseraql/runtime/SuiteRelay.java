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
import io.vertx.core.net.SocketAddress;
import io.vertx.httpproxy.Body;
import io.vertx.httpproxy.HttpProxy;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyRequest;
import io.vertx.httpproxy.ProxyResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 */
final class SuiteRelay {

    private static final Logger LOG = LoggerFactory.getLogger(SuiteRelay.class);

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
        HttpClientOptions options = new HttpClientOptions();
        return http2
                ? options.setProtocolVersion(HttpVersion.HTTP_2)
                        .setHttp2ClearTextUpgrade(true)
                : options;
    }

    /** The default tenant header checked for app entitlement at the front door (ch. 32.8). */
    private static final String TENANT_HEADER = "X-Tenant-Id";

    /** TQL-APP-4030: the request's tenant is not on the app's entitlement list (HTTP 403). */
    private static final String NOT_ENTITLED = "TQL-APP-4030";

    /** TQL-APP-5020: the gateway failed to forward the request to the app's runtime (HTTP 502). */
    private static final String GATEWAY_ERROR = "TQL-APP-5020";

    private final HttpClient client;
    private final Map<String, InstalledApp> appsById;
    /** Per app, the forwarded header its configuration tells it to believe, lowercased. */
    private final Map<String, Set<String>> ingressStripByApp;
    private final TrustedProxies trustedProxies;
    /** App id to the internal port that answers for it now — canary weighting included. */
    private final ToIntFunction<String> portOf;
    /** One proxy per internal port; a port belongs to exactly one app, stable or canary. */
    private final Map<Integer, HttpProxy> proxies = new ConcurrentHashMap<>();

    SuiteRelay(HttpClient client, Map<String, InstalledApp> appsById,
            ToIntFunction<String> portOf) {
        this(client, appsById, Map.of(), TrustedProxies.NONE, portOf);
    }

    SuiteRelay(HttpClient client, Map<String, InstalledApp> appsById,
            Map<String, Set<String>> ingressStripByApp,
            TrustedProxies trustedProxies, ToIntFunction<String> portOf) {
        this.client = client;
        this.appsById = Map.copyOf(appsById);
        this.ingressStripByApp = Map.copyOf(ingressStripByApp);
        this.trustedProxies = trustedProxies;
        this.portOf = portOf;
    }

    /**
     * The application whose declared prefix addresses {@code rawPath}, longest first, or null.
     *
     * <p>Longest-first, not first-match, because the prefixes are declared rather than derived: a
     * suite of one may take the origin root while another application keeps {@code /apps/<id>}, and
     * a root-addressed application would otherwise swallow its neighbour's traffic. A prefix
     * matches on a segment boundary, so {@code /apps/orders} never answers for
     * {@code /apps/orders-archive}.
     */
    private String appAddressedBy(String rawPath) {
        String best = null;
        String bestPrefix = null;
        for (Map.Entry<String, InstalledApp> entry : appsById.entrySet()) {
            String prefix = entry.getValue().basePath();
            if (!addresses(prefix, rawPath)) {
                continue;
            }
            if (bestPrefix == null || prefix.length() > bestPrefix.length()) {
                best = entry.getKey();
                bestPrefix = prefix;
            }
        }
        return best;
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
        try {
            String rawPath = rawPath(request);
            // One address per application, and one way to reach it (docs/stack-architecture.md
            // Decision 12). Host-header routing went with independent hosting: it existed so an
            // application could own a whole origin, which is the separation a suite is defined by
            // not having.
            String appId = appAddressedBy(rawPath);
            if (appId == null) {
                respond(request, 404, MultiAppHost.UNKNOWN_APP.toString());
                return;
            }

            // Tenant entitlement at the front door (ch. 32.8): when the request declares its
            // tenant, an app with an entitlement list only serves the tenants on it. Claim-based
            // tenants are still enforced inside the app's own tenancy resolution.
            String tenant = request.getHeader(TENANT_HEADER);
            InstalledApp app = appsById.get(appId);
            if (tenant != null && app != null && !app.isEntitled(tenant)) {
                respond(request, 403, NOT_ENTITLED);
                return;
            }

            int appPort;
            try {
                appPort = portOf.applyAsInt(appId);
            } catch (RuntimeException unknown) {
                respond(request, 404, MultiAppHost.UNKNOWN_APP.toString());
                return;
            }
            // The URI is forwarded verbatim — the app serves the address it is
            // fronted at (docs/base-path.md decision 5) — so there is nothing to rewrite here,
            // only an origin to choose.
            proxyFor(appId, appPort).handle(request);
        } catch (RuntimeException ex) {
            LOG.warn("Gateway error: {}", ex.getMessage());
            respond(request, 502, GATEWAY_ERROR);
        }
    }

    /**
     * The proxy for one internal port.
     *
     * <p>No header interceptor. The gateway used to strip the mTLS forwarded header its apps
     * declare, on the reasoning that applications are trusted while callers are not — but it
     * stripped unconditionally, including the value the edge had just set, so mTLS forwarded-header
     * authentication could not work behind the gateway at all. docs/authentication.md already
     * assigns that duty where it can be discharged: "the edge must overwrite (or strip) the
     * forwardedHeader on every inbound request, and the runtime must not be reachable except
     * through that edge." That is the division this class exists to draw — the gateway routes, the
     * ingress protects.
     */
    private HttpProxy proxyFor(String appId, int appPort) {
        Set<String> strip = ingressStripByApp.getOrDefault(appId, Set.of());
        return proxies.computeIfAbsent(appPort, target -> {
            HttpProxy proxy = HttpProxy.reverseProxy(client).origin(target, "localhost")
                    .addInterceptor(new BodylessRequestsHaveZeroLength());
            if (!trustedProxies.isEmpty() && !strip.isEmpty()) {
                proxy.addInterceptor(new StripUnlessFromATrustedEdge(strip, trustedProxies));
            }
            return proxy;
        });
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
     * can write {@code X-Forwarded-For}, and cannot write the socket it connected from.
     */
    private record StripUnlessFromATrustedEdge(Set<String> stripOnIngress, TrustedProxies edges)
            implements
                ProxyInterceptor {

        @Override
        public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
            SocketAddress peer = context.request().proxiedRequest().remoteAddress();
            if (!edges.includes(peer == null ? null : peer.hostAddress())) {
                MultiMap headers = context.request().headers();
                // Collected first: removing while iterating the names mutates what is being read.
                List<String> present = headers.names().stream()
                        .filter(name -> stripOnIngress.contains(name.toLowerCase(Locale.ROOT)))
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
