package io.tesseraql.runtime;

import io.tesseraql.operations.app.InstalledApp;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.httpproxy.HttpProxy;
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
     * <p>Cleartext HTTP/2 is off. The previous front was {@code com.sun.net.httpserver}, which
     * speaks HTTP/1.1 only, so no client ever reached a hosted app over h2c through the gateway
     * and turning it on here would be new behaviour rather than restored behaviour. It also has to
     * be turned on at both ends together: with h2c accepted at the front and an HTTP/1.1 hop to
     * the app, a request body arriving over HTTP/2 is piped into an outbound request that has
     * neither a declared length nor chunked framing, and Vert.x refuses the write on the event
     * loop. Serving both protocols end to end is a change worth making deliberately, with the
     * differential test extended to run every case twice; it is not this slice.
     */
    static HttpServerOptions frontOptions(int port) {
        return new HttpServerOptions().setPort(port).setHttp2ClearTextEnabled(false);
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
        return proxies.computeIfAbsent(appPort,
                target -> HttpProxy.reverseProxy(client).origin(target, "localhost"));
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
