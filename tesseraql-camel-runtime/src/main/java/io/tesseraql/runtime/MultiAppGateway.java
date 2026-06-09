package io.tesseraql.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import java.util.Set;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A single-port front that aggregates every app hosted by a {@link MultiAppHost} (design ch. 32.7).
 *
 * <p>Each app still runs in its own isolated runtime on an internal port; the gateway routes
 * {@code /apps/<appId>/<path>} to the matching app, stripping the prefix, so all apps are reachable
 * through one address without sharing route paths or data. Unknown apps get 404.
 */
public final class MultiAppGateway implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MultiAppGateway.class);
    private static final String PREFIX = "/apps/";

    /** Hop-by-hop and length/host headers that must not be forwarded verbatim (RFC 9110 7.6.1). */
    private static final Set<String> SKIP_HEADERS = Set.of(
            "host", "content-length", "connection", "upgrade", "transfer-encoding",
            "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "expect");

    private final MultiAppHost host;
    private final HttpServer server;
    private final HttpClient client;
    private final int port;

    private MultiAppGateway(MultiAppHost host, HttpServer server) {
        this.host = host;
        this.server = server;
        this.client = HttpClient.newHttpClient();
        this.port = server.getAddress().getPort();
        server.createContext(PREFIX, this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    /** Hosts all catalogued apps and fronts them on {@code frontPort} (0 picks an ephemeral port). */
    public static MultiAppGateway start(java.nio.file.Path installRoot, int frontPort) {
        MultiAppHost host = MultiAppHost.start(installRoot);
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(frontPort), 0);
            return new MultiAppGateway(host, server);
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
            String remainder = exchange.getRequestURI().getRawPath().substring(PREFIX.length());
            int slash = remainder.indexOf('/');
            String appId = slash < 0 ? remainder : remainder.substring(0, slash);
            String downstreamPath = slash < 0 ? "/" : remainder.substring(slash);

            int appPort;
            try {
                appPort = host.port(appId);
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
