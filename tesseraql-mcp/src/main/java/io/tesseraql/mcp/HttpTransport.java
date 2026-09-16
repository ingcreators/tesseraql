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

    /**
     * The most request body this transport reads: the runtime's own default body ceiling
     * ({@code tesseraql.http.maxBodyBytes}). The body was buffered whole before anything looked
     * at it, so the size of a request was the caller's choice (docs/audit-low-leads.md, G1).
     */
    static final long MAX_BODY_BYTES = 10L * 1024 * 1024;

    private final McpHttpHandler handler;
    private final String host;
    private final int requestedPort;
    private final String path;
    private final long maxBodyBytes;
    private HttpServer server;

    public HttpTransport(McpHttpHandler handler, String host, int port, String path) {
        this(handler, host, port, path, MAX_BODY_BYTES);
    }

    /** Visible for tests: the body ceiling small enough to cross without a ten-megabyte body. */
    HttpTransport(McpHttpHandler handler, String host, int port, String path,
            long maxBodyBytes) {
        this.handler = handler;
        this.host = host;
        this.requestedPort = port;
        this.path = path.startsWith("/") ? path : "/" + path;
        this.maxBodyBytes = maxBodyBytes;
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
            McpHttpHandler.Response response = read(exchange);
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

    /**
     * The handler's answer, or the 413 for a body past the ceiling. A declared length beyond it
     * is refused before a byte is read; an undeclared one is read to the ceiling and one byte
     * more, which is all the transport needs to know.
     */
    private McpHttpHandler.Response read(HttpExchange exchange) throws IOException {
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declared != null && declaredLength(declared) > maxBodyBytes) {
            return handler.bodyTooLarge(maxBodyBytes);
        }
        byte[] body = exchange.getRequestBody().readNBytes((int) maxBodyBytes + 1);
        if (body.length > maxBodyBytes) {
            return handler.bodyTooLarge(maxBodyBytes);
        }
        return handler.handle(new McpHttpHandler.Request(
                exchange.getRequestMethod(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst(McpHttpHandler.SESSION_HEADER),
                exchange.getRequestHeaders().getFirst(McpHttpHandler.PROTOCOL_VERSION_HEADER),
                exchange.getRequestHeaders().getFirst("Origin"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                new String(body, StandardCharsets.UTF_8)));
    }

    private static long declaredLength(String header) {
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException notANumber) {
            return -1;
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
