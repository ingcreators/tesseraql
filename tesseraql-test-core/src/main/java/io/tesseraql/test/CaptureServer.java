package io.tesseraql.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The runner's per-case HTTP capture server (docs/testing.md, real-send mode): a local
 * listener that records each request — method, path, headers, body — and answers 200, so a
 * suite exercises the true wire without any external dependency.
 */
final class CaptureServer implements AutoCloseable {

    /** One captured request; header names are lower-cased. */
    record Captured(String method, String pathAndQuery, Map<String, String> headers,
            String body) {
    }

    private final com.sun.net.httpserver.HttpServer server;
    private final List<Captured> requests = java.util.Collections
            .synchronizedList(new ArrayList<>());

    private CaptureServer(com.sun.net.httpserver.HttpServer server) {
        this.server = server;
    }

    static CaptureServer start() {
        try {
            com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer
                    .create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            CaptureServer capture = new CaptureServer(server);
            server.createContext("/", exchange -> {
                Map<String, String> headers = new LinkedHashMap<>();
                exchange.getRequestHeaders().forEach((name, values) -> headers
                        .put(name.toLowerCase(java.util.Locale.ROOT),
                                String.join(",", values)));
                String body = new String(exchange.getRequestBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                String query = exchange.getRequestURI().getRawQuery();
                capture.requests.add(new Captured(exchange.getRequestMethod(),
                        exchange.getRequestURI().getRawPath()
                                + (query == null ? "" : "?" + query),
                        headers, body));
                byte[] ok = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, ok.length);
                exchange.getResponseBody().write(ok);
                exchange.close();
            });
            server.start();
            return capture;
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    Captured last() {
        if (requests.isEmpty()) {
            throw new IllegalStateException("No request reached the capture server");
        }
        return requests.get(requests.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
