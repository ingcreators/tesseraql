package io.tesseraql.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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

    private static final Logger LOG = LoggerFactory.getLogger(MultiAppGateway.class);
    private static final String PREFIX = "/apps/";

    /** Hop-by-hop and length/host headers that must not be forwarded verbatim (RFC 9110 7.6.1). */
    private static final Set<String> SKIP_HEADERS = Set.of(
            "host", "content-length", "connection", "upgrade", "transfer-encoding",
            "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "expect");

    /** The default tenant header checked for app entitlement at the front door (ch. 32.8). */
    private static final String TENANT_HEADER = "X-Tenant-Id";

    private final MultiAppHost host;
    private final HttpServer server;
    private final HttpClient client;
    private final int port;
    private final Map<String, String> hostToApp;
    private final Map<String, InstalledApp> appsById;

    private MultiAppGateway(MultiAppHost host, HttpServer server, List<InstalledApp> hostedApps) {
        this.host = host;
        this.server = server;
        Map<String, String> hosts = new java.util.HashMap<>();
        Map<String, InstalledApp> byId = new java.util.HashMap<>();
        for (InstalledApp app : hostedApps) {
            byId.put(app.id(), app);
            for (String hostName : app.hosts()) {
                hosts.put(hostName.toLowerCase(Locale.ROOT), app.id());
            }
        }
        this.hostToApp = Map.copyOf(hosts);
        this.appsById = Map.copyOf(byId);
        this.client = HttpClient.newHttpClient();
        this.port = server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    /** Hosts all catalogued apps and fronts them on {@code frontPort} (0 picks an ephemeral port). */
    public static MultiAppGateway start(java.nio.file.Path installRoot, int frontPort) {
        MultiAppHost host = MultiAppHost.start(installRoot);
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(frontPort), 0);
            List<InstalledApp> hosted = new AppCatalog(installRoot).list().stream()
                    .filter(app -> host.appIds().contains(app.id()))
                    .toList();
            return new MultiAppGateway(host, server, hosted);
        } catch (IOException ex) {
            host.close();
            throw new UncheckedIOException(ex);
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
            if (hostApp != null) {
                // Host-based: the matched app owns the whole address, forward the path unchanged.
                appId = hostApp;
                downstreamPath = rawPath.isEmpty() ? "/" : rawPath;
            } else if (rawPath.startsWith(PREFIX)) {
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
                respond(exchange, 403, "{\"error\":{\"code\":\"TQL-APP-4030\"}}");
                return;
            }

            int appPort;
            try {
                appPort = targetPort(appId);
            } catch (RuntimeException unknown) {
                respond(exchange, 404, "{\"error\":{\"code\":\"TQL-APP-4040\"}}");
                return;
            }
            forward(exchange, appPort, downstreamPath);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            respond(exchange, 502, "{\"error\":{\"code\":\"TQL-APP-5020\"}}");
        } catch (RuntimeException ex) {
            LOG.warn("Gateway error: {}", ex.getMessage());
            respond(exchange, 502, "{\"error\":{\"code\":\"TQL-APP-5020\"}}");
        } finally {
            exchange.close();
        }
    }

    private void forward(HttpExchange exchange, int appPort, String downstreamPath)
            throws IOException, InterruptedException {
        String query = exchange.getRequestURI().getRawQuery();
        URI target = URI.create("http://localhost:" + appPort + downstreamPath
                + (query == null ? "" : "?" + query));

        byte[] body = exchange.getRequestBody().readAllBytes();
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .method(exchange.getRequestMethod(), body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!SKIP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                for (String value : values) {
                    try {
                        request.header(name, value);
                    } catch (IllegalArgumentException restricted) {
                        // The HTTP client disallows some headers; skip them.
                    }
                }
            }
        });

        HttpResponse<byte[]> response = client.send(request.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        response.headers().map().forEach((name, values) -> {
            if (!SKIP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                exchange.getResponseHeaders().put(name, List.copyOf(values));
            }
        });
        byte[] responseBody = response.body();
        exchange.sendResponseHeaders(response.statusCode(), responseBody.length == 0 ? -1 : responseBody.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(responseBody);
        }
    }

    /** Resolves the port for {@code appId}, splitting traffic to a canary candidate by its weight. */
    private int targetPort(String appId) {
        int stablePort = host.port(appId);
        if (host.hasCanary(appId)
                && java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < host.canaryWeight(appId)) {
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
            host.close();
        }
    }
}
