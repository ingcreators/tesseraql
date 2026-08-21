package io.tesseraql.runtime;

import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.component.platform.http.vertx.HttpMessage;
import org.apache.camel.component.platform.http.vertx.VertxPlatformHttpRouter;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.support.DefaultExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves compiled routes on the platform router, off the worker pool (docs/http-edge.md
 * decision 1).
 *
 * <p>{@code camel-platform-http-vertx} hands every exchange to {@code executeBlocking}, so a
 * request runs on a pool of ten platform threads whether or not it needs anything that pool
 * protects. That single choice is what the [HTTP threading](http-threading.md) campaign spent
 * eight slices working around. Here the route's own processors run on a virtual thread instead,
 * and the only bound left on work that needs a connection is the connection pool — measured at
 * <strong>3629 ms against 1046 ms</strong> for the same route under the same saturation.
 *
 * <p><strong>It takes what it can and hands the rest back.</strong> A request whose shape this
 * adapter does not reproduce faithfully — anything carrying a body, because form and multipart
 * parsing is Camel's today — falls through to the Camel route still mounted behind it. That is
 * what makes this reversible: the route model is unchanged, both paths exist, and the whole
 * integration suite drives them over real HTTP either way.
 */
final class RouteEdge {

    private static final Logger LOG = LoggerFactory.getLogger(RouteEdge.class);

    /** Registry name, so a hot reload can find the edge without being handed it. */
    static final String BEAN = "tesseraqlRouteEdge";

    /**
     * Ahead of the Camel routes, behind the admission gate.
     *
     * <p>Deliberately ahead of Camel's body handler as well, which is why a request with a body
     * is handed back rather than read here: reading it would consume the stream the handler
     * behind us needs.
     */
    private static final int AFTER_THE_GATE = Integer.MIN_VALUE + 1;

    /** Large enough that an ordinary response is one chunk, small enough to bound a large one. */
    private static final int CHUNK_BYTES = 64 * 1024;

    private final CamelContext camelContext;
    /**
     * Pipelines by route id, looked up per request rather than captured by the handler.
     *
     * <p>That indirection is what makes hot reload a swap: a recompiled route replaces its entry
     * and the mounted handler picks the new one up on the next request, with no router surgery.
     */
    private final Map<String, RoutePipeline> pipelines = new ConcurrentHashMap<>();
    private final Map<String, Route> mounted = new ConcurrentHashMap<>();

    private RouteEdge(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    /** Mounts every compiled HTTP route this can run; returns the edge so reloads can refresh it. */
    static RouteEdge install(CamelContext camelContext, int port) {
        VertxPlatformHttpRouter router = VertxPlatformHttpRouter.lookup(camelContext,
                VertxPlatformHttpRouter.getRouterNameFromPort(port));
        RouteEdge edge = new RouteEdge(camelContext);
        int served = 0;
        int declined = 0;
        for (RouteDefinition definition : ((ModelCamelContext) camelContext)
                .getRouteDefinitions()) {
            if (edge.mount(router, definition)) {
                served++;
            } else {
                declined++;
            }
        }
        LOG.debug("HTTP edge serving {} route(s) on the router, {} left to Camel", served,
                declined);
        return edge;
    }

    /**
     * Re-reads one route's pipeline after a hot reload.
     *
     * <p>Called with the routes already recompiled: each entry is replaced, or removed when the
     * new definition is one this cannot run or is gone, in which case the mounted handler hands
     * that path back — to the Camel route if it still exists, and to a 404 if it does not, which
     * is what a deleted route should answer. The mount itself is never moved: a route that
     * appears at a new URL is served by Camel until the next restart, recorded here rather than
     * hidden, because router surgery under live traffic buys nothing a restart does not.
     */
    void refreshAll() {
        mounted.keySet().forEach(this::refresh);
    }

    private void refresh(String routeId) {
        RoutePipeline replaced = RoutePipeline.of(camelContext, routeId).map(pipeline -> {
            try {
                pipeline.start();
                return pipelines.put(routeId, pipeline);
            } catch (Exception unusable) {
                return pipelines.remove(routeId);
            }
        }).orElseGet(() -> pipelines.remove(routeId));
        if (replaced != null) {
            replaced.stop();
        }
    }

    private boolean mount(VertxPlatformHttpRouter router, RouteDefinition definition) {
        String from = definition.getInput().getEndpointUri();
        if (from == null || !from.startsWith("rest://")) {
            return false;
        }
        String routeId = definition.getRouteId();
        RoutePipeline pipeline = RoutePipeline.of(camelContext, routeId).orElse(null);
        if (pipeline == null) {
            return false;
        }
        try {
            pipeline.start();
        } catch (Exception unusable) {
            return false;
        }
        HttpMethod method = method(from);
        String path = path(from);
        if (method == null || path == null) {
            return false;
        }
        pipelines.put(routeId, pipeline);
        mounted.put(routeId, router.route(method, path).order(AFTER_THE_GATE)
                .handler(ctx -> serve(ctx, routeId)));
        return true;
    }

    /** {@code rest://get:/api/echo?…} — the method is the segment before the first colon. */
    private static HttpMethod method(String from) {
        String rest = from.substring("rest://".length());
        int colon = rest.indexOf(':');
        if (colon < 0) {
            return null;
        }
        try {
            return HttpMethod.valueOf(rest.substring(0, colon).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /**
     * The URL the route answers at: the REST path under the application's base path, with Camel's
     * {@code {name}} parameters spelled the way this router spells them.
     *
     * <p>The base path is on the context-wide REST configuration rather than on each route
     * (docs/base-path.md), so it has to be put back here — the one thing the REST DSL was doing
     * that a router mount does not do by itself.
     */
    private String path(String from) {
        String rest = from.substring("rest://".length());
        int colon = rest.indexOf(':');
        int query = rest.indexOf('?');
        String path = query < 0 ? rest.substring(colon + 1) : rest.substring(colon + 1, query);
        path = java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8);
        if (path.isBlank()) {
            return null;
        }
        StringBuilder mountedPath = new StringBuilder(io.tesseraql.camel.BasePath.of(camelContext));
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            mountedPath.append('/');
            if (segment.startsWith("{") && segment.endsWith("}")) {
                mountedPath.append(':').append(segment, 1, segment.length() - 1);
            } else {
                mountedPath.append(segment);
            }
        }
        return mountedPath.length() == 0 ? "/" : mountedPath.toString();
    }

    private void serve(RoutingContext ctx, String routeId) {
        RoutePipeline pipeline = pipelines.get(routeId);
        if (pipeline == null || carriesABody(ctx)) {
            ctx.next();
            return;
        }
        Context connection = ctx.vertx().getOrCreateContext();
        Exchange exchange = request(ctx);
        // Counted where the drain already looks (docs/runtime-replace.md). A request served off a
        // route is not an in-flight exchange as far as Camel's shutdown strategy is concerned, so
        // replacing a runtime cut it mid-answer instead of waiting for it — the drain contract
        // holding for every request except the ones this edge had taken over. Registering the
        // exchange under its route id puts it back where the strategy counts, rather than adding
        // a second thing for a stop to wait on.
        org.apache.camel.spi.InflightRepository inflight = camelContext.getInflightRepository();
        inflight.add(exchange, routeId);
        Thread.ofVirtual().name("tql-route-" + routeId).start(() -> {
            try {
                pipeline.run(exchange);
                respond(ctx, connection, exchange);
            } finally {
                inflight.remove(exchange, routeId);
            }
        });
    }

    /**
     * Whether this request has a body, and therefore belongs to Camel for now.
     *
     * <p>Form posts reach a route as parsed attributes today, and reproducing that faithfully —
     * urlencoded and multipart, merged into headers the way {@code platform-http} merges them — is
     * its own change with its own tests. Reading the body here to find out would consume the
     * stream the handler behind us needs, so the question is answered from headers alone.
     */
    private static boolean carriesABody(RoutingContext ctx) {
        String length = ctx.request().getHeader("Content-Length");
        return ctx.request().getHeader("Transfer-Encoding") != null
                || (length != null && !"0".equals(length.trim()));
    }

    /**
     * The exchange the route's processors expect, in the shape they already expect it.
     *
     * <p>Every line here was found by reading what {@code camel-platform-http-vertx} does and
     * finding out what depended on it, so each one is a fact rather than a guess:
     * {@code CamelHttpPath} is the <em>normalized</em> path and not the raw one, the peer address
     * headers are what the network conditions on a role resolve against, request headers and
     * query parameters go through the inbound filter and are <em>appended</em> rather than
     * assigned so a repeated name becomes a list, and path parameters land after query parameters
     * because that is the order a route already reads them in.
     */
    private Exchange request(RoutingContext ctx) {
        HttpServerRequest request = ctx.request();
        Exchange exchange = new DefaultExchange(camelContext);
        HttpMessage message = new HttpMessage(exchange, request, ctx.response());
        exchange.setMessage(message);
        Map<String, Object> headers = new java.util.LinkedHashMap<>();
        headers.put(Exchange.HTTP_PATH, ctx.normalizedPath());
        org.apache.camel.spi.HeaderFilterStrategy filter = headerFilter();
        applyInbound(headers, exchange, filter, request.headers());
        if (!ctx.queryParams().isEmpty()) {
            applyInbound(headers, exchange, filter, ctx.queryParams());
        }
        ctx.pathParams().forEach((name, value) -> org.apache.camel.util.CollectionHelper
                .appendEntry(headers, name, value));
        if (request.localAddress() != null) {
            headers.put(io.tesseraql.camel.PlatformHttpHeaders.LOCAL_ADDRESS,
                    request.localAddress());
        }
        if (request.remoteAddress() != null) {
            headers.put(io.tesseraql.camel.PlatformHttpHeaders.REMOTE_ADDRESS,
                    request.remoteAddress());
        }
        headers.put(Exchange.HTTP_METHOD, request.method().toString());
        headers.put(Exchange.HTTP_URL, request.absoluteURI());
        headers.put(Exchange.HTTP_URI, request.uri());
        headers.put(Exchange.HTTP_QUERY, request.query());
        headers.put(Exchange.HTTP_RAW_QUERY, request.query());
        message.setHeaders(headers);
        return exchange;
    }

    /** Request headers and query parameters, filtered and appended the way a consumer does it. */
    private static void applyInbound(Map<String, Object> headers, Exchange exchange,
            org.apache.camel.spi.HeaderFilterStrategy filter, io.vertx.core.MultiMap source) {
        source.forEach(entry -> {
            String name = entry.getKey();
            String value = entry.getValue();
            if (filter == null || !filter.applyFilterToExternalHeaders(name, value, exchange)) {
                org.apache.camel.util.CollectionHelper.appendEntry(headers, name, value);
            }
        });
    }

    private org.apache.camel.spi.HeaderFilterStrategy headerFilter() {
        return camelContext.getComponent("platform-http",
                org.apache.camel.component.platform.http.PlatformHttpComponent.class)
                .getHeaderFilterStrategy();
    }

    private void respond(RoutingContext ctx, Context connection, Exchange exchange) {
        Object body = exchange.getMessage().getBody();
        if (body instanceof InputStream stream) {
            // Streamed on the thread that is already ours, so a download costs no worker and no
            // heap — the coupling docs/http-edge.md decision 1 expects to die by evacuation,
            // dying here by ownership instead.
            stream(ctx, connection, exchange, stream);
            return;
        }
        Buffer buffer = body == null
                ? null
                : body instanceof byte[] bytes
                        ? Buffer.buffer(bytes)
                        : Buffer.buffer(exchange.getContext().getTypeConverter()
                                .convertTo(String.class, exchange, body));
        connection.runOnContext(reply -> {
            if (ctx.response().ended()) {
                return;
            }
            headers(ctx.response(), exchange);
            if (buffer == null) {
                ctx.response().end();
            } else {
                ctx.response().end(buffer);
            }
        });
    }

    private void stream(RoutingContext ctx, Context connection, Exchange exchange,
            InputStream body) {
        HttpServerResponse response = ctx.response();
        AtomicBoolean gone = new AtomicBoolean();
        connection.runOnContext(open -> {
            response.closeHandler(closed -> gone.set(true));
            response.exceptionHandler(failure -> gone.set(true));
            headers(response, exchange);
            response.setChunked(true);
        });
        try (InputStream in = body) {
            byte[] chunk = new byte[CHUNK_BYTES];
            int read;
            while (!gone.get() && (read = in.read(chunk)) > 0) {
                write(connection, gone, response, Buffer.buffer(java.util.Arrays.copyOf(chunk,
                        read)));
            }
        } catch (IOException unreadable) {
            gone.set(true);
        }
        connection.runOnContext(end -> {
            if (!gone.get() && !response.ended()) {
                response.end();
            }
        });
    }

    /** One chunk, and the wait for it that keeps a large body from becoming a large queue. */
    private static void write(Context connection, AtomicBoolean gone, HttpServerResponse response,
            Buffer chunk) {
        CompletableFuture<Void> written = new CompletableFuture<>();
        connection.runOnContext(run -> {
            if (gone.get() || response.ended()) {
                written.complete(null);
                return;
            }
            response.write(chunk).onComplete(result -> {
                if (result.failed()) {
                    gone.set(true);
                }
                written.complete(null);
            });
        });
        try {
            written.get();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            gone.set(true);
        } catch (ExecutionException impossible) {
            gone.set(true);
        }
    }

    /**
     * The response headers, decided by the same filter the Camel edge decides them with.
     *
     * <p>Load-bearing rather than tidy. The request's own headers are on this message — they were
     * put there so the route could read {@code Cookie}, {@code Accept} and the rest — and copying
     * the message's headers out untouched would echo a caller's cookie back as a response header.
     * The component's {@code HeaderFilterStrategy} is the thing that already knows which headers
     * leave a runtime, including this framework's one amendment to it (the cache-control entry
     * declarative route caching needs on the wire), so it is asked rather than reimplemented.
     */
    private void headers(HttpServerResponse response, Exchange exchange) {
        Integer code = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
        response.setStatusCode(code == null ? 200 : code);
        org.apache.camel.spi.HeaderFilterStrategy filter = headerFilter();
        // Camel writes the content type itself rather than through the filter, which strips it
        // from the generic copy; doing the same here is why a JSON response says so.
        String contentType = exchange.getMessage().getHeader(Exchange.CONTENT_TYPE, String.class);
        if (contentType != null) {
            response.putHeader("Content-Type", contentType);
        }
        exchange.getMessage().getHeaders().forEach((name, value) -> {
            if (value == null || (filter != null
                    && filter.applyFilterToCamelHeaders(name, value, exchange))) {
                return;
            }
            response.putHeader(name, String.valueOf(value));
        });
    }
}
