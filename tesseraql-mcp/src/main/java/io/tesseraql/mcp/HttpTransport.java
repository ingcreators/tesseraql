package io.tesseraql.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Binds an {@link McpHttpHandler} to a JDK {@link HttpServer} at one path. Zero extra dependencies -
 * suitable for a standalone dev-tool server on a shared host. The runtime serves its app-declared
 * MCP endpoints through its own HTTP server instead, reusing the same {@link McpHttpHandler}.
 */
public final class HttpTransport {

    private final McpHttpHandler handler;
    private final String host;
    private final int requestedPort;
    private final String path;
    private HttpServer server;

    public HttpTransport(McpHttpHandler handler, String host, int port, String path) {
        this.handler = handler;
        this.host = host;
        this.requestedPort = port;
        this.path = path.startsWith("/") ? path : "/" + path;
    }

    /** Binds the socket and starts serving. Non-blocking - requests run on a worker pool. */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, requestedPort), 0);
        server.createContext(path, this::dispatch);
        server.setExecutor(Executors.newCachedThreadPool(daemonThreads()));
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** The bound port (resolves an ephemeral {@code 0} request to the actual port). */
    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://" + host + ":" + port() + path;
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            McpHttpHandler.Request request = new McpHttpHandler.Request(
                    exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst(McpHttpHandler.SESSION_HEADER),
                    exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"),
                    new String(body, StandardCharsets.UTF_8));
            McpHttpHandler.Response response = handler.handle(request);
            for (Map.Entry<String, String> header : response.headers().entrySet()) {
                exchange.getResponseHeaders().set(header.getKey(), header.getValue());
            }
            byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
            if (payload.length == 0) {
                exchange.sendResponseHeaders(response.status(), -1);
                return;
            }
            exchange.sendResponseHeaders(response.status(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "mcp-http-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
