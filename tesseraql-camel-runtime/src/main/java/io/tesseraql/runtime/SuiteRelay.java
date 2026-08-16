package io.tesseraql.runtime;

import io.tesseraql.operations.app.InstalledApp;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.httpproxy.Body;
import io.vertx.httpproxy.HttpProxy;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyRequest;
import io.vertx.httpproxy.ProxyResponse;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gateway's request half: decide which app answers, refuse what no app answers, and relay
 * everything else verbatim (docs/suite-architecture.md decision 13).
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

    /** The shared-origin prefix each app is addressed under in suite mode. */
    static final String PREFIX = "/apps/";

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
    private final MultiAppGateway.Mode mode;
    private final Map<String, String> hostToApp;
    private final Map<String, InstalledApp> appsById;
    /** App id to the internal port that answers for it now — canary weighting included. */
    private final ToIntFunction<String> portOf;
    /** One proxy per internal port; a port belongs to exactly one app, stable or canary. */
    private final Map<Integer, HttpProxy> proxies = new ConcurrentHashMap<>();

    SuiteRelay(HttpClient client, MultiAppGateway.Mode mode, Map<String, String> hostToApp,
            Map<String, InstalledApp> appsById, ToIntFunction<String> portOf) {
        this.client = client;
        this.mode = mode;
        this.hostToApp = Map.copyOf(hostToApp);
        this.appsById = Map.copyOf(appsById);
        this.portOf = portOf;
    }

    /** Routes one request to the app that answers for it, or refuses it here. */
    void handle(HttpServerRequest request) {
        try {
            String rawPath = rawPath(request);
            String hostApp = hostToApp.get(requestHost(request));

            String appId;
            if (mode == MultiAppGateway.Mode.ISOLATED && hostApp != null) {
                // Host-based: the matched app owns the whole address, forward the path unchanged.
                appId = hostApp;
            } else if (mode == MultiAppGateway.Mode.SUITE && rawPath.startsWith(PREFIX)) {
                String remainder = rawPath.substring(PREFIX.length());
                int slash = remainder.indexOf('/');
                appId = slash < 0 ? remainder : remainder.substring(0, slash);
            } else {
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
            // The URI is forwarded verbatim in both modes — the app serves the address it is
            // fronted at (docs/base-path.md decision 5) — so there is nothing to rewrite here,
            // only an origin to choose.
            proxyFor(appPort).handle(request);
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
    private HttpProxy proxyFor(int appPort) {
        return proxies.computeIfAbsent(appPort, target -> HttpProxy.reverseProxy(client)
                .origin(target, "localhost")
                .addInterceptor(new BodylessRequestsHaveZeroLength()));
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

    /** The request path without the query, undecoded — routing reads raw segments. */
    private static String rawPath(HttpServerRequest request) {
        String uri = request.uri();
        int query = uri.indexOf('?');
        String path = query < 0 ? uri : uri.substring(0, query);
        return path.isEmpty() ? "/" : path;
    }

    /** The request's host without port, lowercased, or empty if absent. */
    private static String requestHost(HttpServerRequest request) {
        String header = request.getHeader("Host");
        if (header == null) {
            return "";
        }
        int colon = header.indexOf(':');
        String name = colon < 0 ? header : header.substring(0, colon);
        return name.toLowerCase(Locale.ROOT);
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
