package io.tesseraql.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.operations.app.AppCatalog;
import io.tesseraql.operations.app.InstalledApp;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A single-port front that aggregates every app hosted by a {@link MultiAppHost} (design ch. 32.7).
 *
 * <p>Each app still runs in its own isolated runtime on an internal port. The gateway routes a
 * request to an app by, in order: the {@code Host} header (when the app declares hostnames in its
 * catalog entry), then the {@code /apps/<appId>/<path>} path prefix. Host routing forwards the full
 * path; prefix routing strips the prefix. All apps are reachable through one address without sharing
 * route paths or data; unmatched requests get 404.
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
    private static final String PREFIX = "/apps/";

    /** Hop-by-hop and length/host headers that must not be forwarded verbatim (RFC 9110 7.6.1). */
    private static final Set<String> SKIP_HEADERS = Set.of(
            "host", "content-length", "connection", "upgrade", "transfer-encoding",
            "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "expect");

    /** The default tenant header checked for app entitlement at the front door (ch. 32.8). */
    private static final String TENANT_HEADER = "X-Tenant-Id";

    /** TQL-APP-4030: the request's tenant is not on the app's entitlement list (HTTP 403). */
    private static final String NOT_ENTITLED = "TQL-APP-4030";

    /** TQL-APP-5020: the gateway failed to forward the request to the app's runtime (HTTP 502). */
    private static final String GATEWAY_ERROR = "TQL-APP-5020";

    /** An isolated-hosting app that declares no hostname would be started and unreachable. */
    private static final io.tesseraql.core.error.TqlErrorCode NO_HOSTNAME = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.APP, 5003);

    private final MultiAppHost host;
    private final HttpServer server;
    /**
     * The largest request body the gateway will buffer before forwarding it.
     *
     * <p>A fixed ceiling rather than a config key, deliberately: the gateway fronts several apps
     * and a per-app limit would be ambiguous here, so this is the front door's own bound and the
     * app behind it keeps whatever limits it declares. Ten megabytes leaves ordinary form and
     * upload traffic alone while making "how much heap can a stranger take" a bounded question.
     */
    static final int MAX_REQUEST_BODY_BYTES = 10 * 1024 * 1024;

    /**
     * The largest response body the gateway will relay.
     *
     * <p>The request side was bounded and the response side was not, which left the same
     * question open from the other direction: a hosted app answering a large export decided how
     * much of the gateway's heap it took. The body streams through a bounded buffer instead of
     * materializing, so an oversized response fails the exchange rather than the process.
     */
    static final long MAX_RESPONSE_BODY_BYTES = 64L * 1024 * 1024;

    private final HttpClient client;
    private final java.util.concurrent.ExecutorService executor;
    private final int port;
    private final Map<String, String> hostToApp;
    private final Map<String, InstalledApp> appsById;
    /**
     * Per app, the headers a client must never be able to supply for itself: the mTLS
     * forwarded header that app trusts, lowercased.
     *
     * <p>A client certificate is public, so PKIX proves issuance rather than possession — the
     * edge's trust in this header *is* the control. The gateway forwarded a client-supplied copy
     * verbatim, which let a caller present the header the app was configured to believe.
     * TesseraQL's own front strips it on ingress; only an edge in front of the gateway may set
     * it (docs/deployment.md).
     *
     * <p>{@code X-Tenant-Id} is deliberately *not* stripped: the gateway's entitlement check is
     * a convenience filter, not a control, and the app's own tenancy resolution is the
     * authoritative one (docs/app-isolation-model.md decision 3).
     */
    private final Map<String, Set<String>> ingressStripByApp;
    private final Mode mode;

    private MultiAppGateway(MultiAppHost host, HttpServer server, List<InstalledApp> hostedApps,
            java.nio.file.Path installRoot, Mode mode) {
        this.host = host;
        this.server = server;
        this.mode = mode;
        Map<String, String> hosts = new java.util.HashMap<>();
        Map<String, InstalledApp> byId = new java.util.HashMap<>();
        Map<String, Set<String>> strip = new java.util.HashMap<>();
        for (InstalledApp app : hostedApps) {
            byId.put(app.id(), app);
            for (String hostName : app.hosts()) {
                hosts.put(hostName.toLowerCase(Locale.ROOT), app.id());
            }
            strip.put(app.id(), ingressStripHeaders(installRoot, app));
        }
        this.hostToApp = Map.copyOf(hosts);
        this.appsById = Map.copyOf(byId);
        this.ingressStripByApp = Map.copyOf(strip);
        this.client = HttpClient.newHttpClient();
        this.port = server.getAddress().getPort();
        server.createContext("/", this::handle);
        // Held so close() can shut it down: it was created inline and dropped, so stopping the
        // gateway left the executor behind along with the client's connection pool and selector.
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
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
        MultiAppHost host = MultiAppHost.start(installRoot);
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(frontPort), 0);
            List<InstalledApp> hosted = catalogued.stream()
                    .filter(app -> host.appIds().contains(app.id()))
                    .toList();
            return new MultiAppGateway(host, server, hosted, installRoot, mode);
        } catch (IOException ex) {
            host.close();
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * The headers to drop from a request bound for {@code app}: the mTLS forwarded header its
     * configuration names, if any. Best-effort — an app whose config cannot be read strips
     * nothing extra, because the alternative is refusing to host it over a header it may not
     * even use.
     */
    private static Set<String> ingressStripHeaders(java.nio.file.Path installRoot,
            InstalledApp app) {
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

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String rawPath = exchange.getRequestURI().getRawPath();
            String hostApp = hostToApp.get(requestHost(exchange));

            String appId;
            String downstreamPath;
            if (mode == Mode.ISOLATED && hostApp != null) {
                // Host-based: the matched app owns the whole address, forward the path unchanged.
                appId = hostApp;
                downstreamPath = rawPath.isEmpty() ? "/" : rawPath;
            } else if (mode == Mode.SUITE && rawPath.startsWith(PREFIX)) {
                String remainder = rawPath.substring(PREFIX.length());
                int slash = remainder.indexOf('/');
                appId = slash < 0 ? remainder : remainder.substring(0, slash);
                downstreamPath = slash < 0 ? "/" : remainder.substring(slash);
            } else {
                respond(exchange, 404, "{\"error\":{\"code\":\"TQL-APP-4040\"}}");
                return;
            }

            // Tenant entitlement at the front door (ch. 32.8): when the request declares its
            // tenant, an app with an entitlement list only serves the tenants on it. Claim-based
            // tenants are still enforced inside the app's own tenancy resolution.
            String tenant = exchange.getRequestHeaders().getFirst(TENANT_HEADER);
            InstalledApp app = appsById.get(appId);
            if (tenant != null && app != null && !app.isEntitled(tenant)) {
                respond(exchange, 403, "{\"error\":{\"code\":\"" + NOT_ENTITLED + "\"}}");
                return;
            }

            int appPort;
            try {
                appPort = targetPort(appId);
            } catch (RuntimeException unknown) {
                respond(exchange, 404, "{\"error\":{\"code\":\"TQL-APP-4040\"}}");
                return;
            }
            forward(exchange, appPort, downstreamPath,
                    ingressStripByApp.getOrDefault(appId, Set.of()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            respond(exchange, 502, "{\"error\":{\"code\":\"" + GATEWAY_ERROR + "\"}}");
        } catch (RuntimeException ex) {
            LOG.warn("Gateway error: {}", ex.getMessage());
            respond(exchange, 502, "{\"error\":{\"code\":\"" + GATEWAY_ERROR + "\"}}");
        } finally {
            exchange.close();
        }
    }

    private void forward(HttpExchange exchange, int appPort, String downstreamPath,
            Set<String> stripOnIngress) throws IOException, InterruptedException {
        String query = exchange.getRequestURI().getRawQuery();
        URI target = URI.create("http://localhost:" + appPort + downstreamPath
                + (query == null ? "" : "?" + query));

        // Bounded, because this is the front door: readAllBytes() let any caller decide how much
        // of the gateway's heap to take, and a proxy holds the whole body before it can forward.
        // One byte past the cap is enough to know it was exceeded without buffering the rest.
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        if (body.length > MAX_REQUEST_BODY_BYTES) {
            respond(exchange, 413, "{\"error\":\"Request body too large\"}");
            return;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .method(exchange.getRequestMethod(), body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        exchange.getRequestHeaders().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!SKIP_HEADERS.contains(lower) && !stripOnIngress.contains(lower)) {
                for (String value : values) {
                    try {
                        request.header(name, value);
                    } catch (IllegalArgumentException restricted) {
                        // The HTTP client disallows some headers; skip them.
                    }
                }
            }
        });

        HttpResponse<java.io.InputStream> response = client.send(request.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        response.headers().map().forEach((name, values) -> {
            if (!SKIP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                exchange.getResponseHeaders().put(name, List.copyOf(values));
            }
        });
        // The declared length is relayed when the app declared one, so a download still reports
        // its size and the wire format matches what the app produced; only an app that answers
        // without a length makes this chunked (0). Either way the body streams through a fixed
        // buffer — the gateway never holds a whole response, which is what the request side has
        // bounded since it grew a cap.
        long declared = response.headers().firstValueAsLong("content-length").orElse(-1);
        if (declared > MAX_RESPONSE_BODY_BYTES) {
            LOG.warn("App on port {} declared a {}-byte response, past the {}-byte relay bound",
                    appPort, declared, MAX_RESPONSE_BODY_BYTES);
            response.body().close();
            respond(exchange, 502, "{\"error\":{\"code\":\"" + GATEWAY_ERROR + "\"}}");
            return;
        }
        exchange.sendResponseHeaders(response.statusCode(), declared == 0 ? -1 : declared);
        try (java.io.InputStream downstream = response.body();
                OutputStream out = exchange.getResponseBody()) {
            byte[] buffer = new byte[8192];
            long relayed = 0;
            int read;
            while ((read = downstream.read(buffer)) != -1) {
                relayed += read;
                if (relayed > MAX_RESPONSE_BODY_BYTES) {
                    // An undeclared length that runs past the bound: the status is already sent,
                    // so the only honest signal left is to break the response. A truncated body
                    // the client detects beats one it cannot.
                    LOG.warn("Response from app on port {} exceeded {} bytes; relay aborted",
                            appPort, MAX_RESPONSE_BODY_BYTES);
                    return;
                }
                out.write(buffer, 0, read);
            }
        }
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

    /** The request's host without port, lowercased, or empty if absent. */
    private static String requestHost(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Host");
        if (header == null) {
            return "";
        }
        int colon = header.indexOf(':');
        String name = colon < 0 ? header : header.substring(0, colon);
        return name.toLowerCase(Locale.ROOT);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        try {
            server.stop(0);
        } finally {
            // Everything this instance opened, in the order it was opened: the executor the
            // server ran on, then the client's pool and selector thread, then the hosted app.
            // Two of the three were never closed at all, so a gateway restart accumulated both.
            executor.shutdownNow();
            client.close();
            host.close();
        }
    }
}
