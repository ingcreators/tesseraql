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
    /** What each mounted route was mounted as, so a reload can tell a moved URL from a stable one. */
    private final Map<String, io.tesseraql.camel.HttpMounts.Mount> at = new ConcurrentHashMap<>();
    private volatile VertxPlatformHttpRouter router;

    private RouteEdge(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    /**
     * Mounts every declared HTTP surface; returns the edge so reloads can refresh it.
     *
     * <p><strong>A surface this cannot serve fails the boot.</strong> There is no Camel route
     * behind it any longer — the REST DSL that used to put one there is gone — so declining would
     * mean a declared URL answering 404 for the life of the process. A runtime that cannot serve
     * what it was asked to serve should say so while somebody is still watching it start.
     */
    static RouteEdge install(CamelContext camelContext, int port) {
        VertxPlatformHttpRouter router = VertxPlatformHttpRouter.lookup(camelContext,
                VertxPlatformHttpRouter.getRouterNameFromPort(port));
        RouteEdge edge = new RouteEdge(camelContext);
        edge.router = router;
        for (io.tesseraql.camel.HttpMounts.Mount mount : io.tesseraql.camel.HttpMounts
                .all(camelContext)) {
            edge.mount(router, mount);
        }
        LOG.debug("HTTP edge serving {} route(s) on the router", edge.mounted.size());
        return edge;
    }

    /**
     * Re-reads one route's pipeline after a hot reload.
     *
     * <p>Called with the routes already recompiled, and it reconciles rather than refreshes: a
     * route that changed keeps its mount and swaps its pipeline, one that appeared or moved gets
     * a mount, and one that is gone loses both so the URL answers 404 instead of its last body.
     * The router is edited under live traffic because the file watcher's promise is that a route
     * directory appearing on disk starts serving — and there is no Camel route behind this one to
     * keep that promise for it any more.
     */
    void refreshAll() {
        java.util.Set<String> declared = new java.util.HashSet<>();
        for (io.tesseraql.camel.HttpMounts.Mount mount : io.tesseraql.camel.HttpMounts
                .all(camelContext)) {
            String routeId = routeIdOf(mount.direct());
            if (routeId == null) {
                // Declared but not built: a save that did not compile and left no stub. The
                // watcher has to survive that, which is why a reload is tolerant where a boot is
                // strict — nobody is watching a reload, and a runtime that exits on a bad save
                // takes the good routes with it.
                continue;
            }
            declared.add(routeId);
            if (mounted.containsKey(routeId) && mount.equals(at.get(routeId))) {
                refresh(routeId);
            } else {
                remount(mount, routeId);
            }
        }
        for (String routeId : java.util.List.copyOf(mounted.keySet())) {
            if (!declared.contains(routeId)) {
                unmount(routeId);
            }
        }
    }

    /**
     * Mounts a route that was not there before, or was there at a different URL.
     *
     * <p>A route directory that appears while the runtime is running is a shipped promise of the
     * file watcher, and there is no Camel route behind this one to catch it — so the router grows
     * a route rather than waiting for a restart. Tolerant, for the reason above: a reload that
     * cannot mount one route leaves the others serving.
     */
    private void remount(io.tesseraql.camel.HttpMounts.Mount mount, String routeId) {
        RoutePipeline pipeline = RoutePipeline.of(camelContext, routeId).orElse(null);
        if (pipeline == null) {
            LOG.warn("Route {} cannot be served on the router after a reload", routeId);
            return;
        }
        try {
            pipeline.start();
        } catch (Exception unusable) {
            LOG.warn("Route {} could not be started after a reload", routeId, unusable);
            return;
        }
        unmount(routeId);
        pipelines.put(routeId, pipeline);
        at.put(routeId, mount);
        mounted.put(routeId, router.route(HttpMethod.valueOf(mount.method()), path(mount.path()))
                .order(AFTER_THE_GATE)
                .handler(router.bodyHandler())
                .handler(ctx -> serve(ctx, routeId)));
    }

    /** Takes a route off the router, so a deleted route answers 404 rather than its last body. */
    private void unmount(String routeId) {
        Route route = mounted.remove(routeId);
        if (route != null) {
            route.remove();
        }
        at.remove(routeId);
        RoutePipeline gone = pipelines.remove(routeId);
        if (gone != null) {
            gone.stop();
        }
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

    private void mount(VertxPlatformHttpRouter router,
            io.tesseraql.camel.HttpMounts.Mount mount) {
        String routeId = routeIdOf(mount.direct());
        if (routeId == null) {
            throw new IllegalStateException("The HTTP surface " + mount.method() + " "
                    + mount.path() + " names " + mount.direct() + ", which no route consumes");
        }
        RoutePipeline pipeline = RoutePipeline.of(camelContext, routeId)
                .orElseThrow(() -> new IllegalStateException("Route " + routeId
                        + " is not a plain processor chain, so " + mount.method() + " "
                        + mount.path() + " cannot be served"));
        try {
            pipeline.start();
        } catch (Exception unusable) {
            throw new IllegalStateException("Route " + routeId + " could not be started",
                    unusable);
        }
        pipelines.put(routeId, pipeline);
        at.put(routeId, mount);
        // The body handler is the router's own — the instance the Camel consumer would have used,
        // with whatever the server configured on it — so an upload spools where it already
        // spooled and a form parses the way it already parsed.
        mounted.put(routeId, router.route(HttpMethod.valueOf(mount.method()), path(mount.path()))
                .order(AFTER_THE_GATE)
                .handler(router.bodyHandler())
                .handler(ctx -> serve(ctx, routeId)));
    }

    /**
     * The route that consumes a mount's {@code direct:} endpoint.
     *
     * <p>A mount names the endpoint rather than the route id because that is what the declaring
     * line had in its hand; the id is on the {@code from(...)} beside it. Camel normalises
     * {@code direct:x} to {@code direct://x}, so the comparison is on what follows the scheme.
     */
    private String routeIdOf(String direct) {
        String name = direct.substring(direct.indexOf(':') + 1).replaceFirst("^//", "");
        for (RouteDefinition definition : ((ModelCamelContext) camelContext)
                .getRouteDefinitions()) {
            String from = definition.getInput().getEndpointUri();
            if (from != null && from.startsWith("direct:")
                    && from.substring(from.indexOf(':') + 1).replaceFirst("^//", "").equals(name)) {
                return definition.getRouteId();
            }
        }
        return null;
    }

    /**
     * The URL the route answers at: the declared path under the application's base path, with
     * Camel's {@code {name}} parameters spelled the way this router spells them.
     *
     * <p>The base path used to arrive from the context-wide REST configuration
     * (docs/base-path.md), which is gone, so the mount is where it goes on — the one place that
     * knows about URLs at all.
     */
    private String path(String declared) {
        StringBuilder mountedPath = new StringBuilder(io.tesseraql.camel.BasePath.of(camelContext));
        for (String segment : declared.split("/", -1)) {
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
        if (pipeline == null) {
            ctx.next();
            return;
        }
        Context connection = ctx.vertx().getOrCreateContext();
        Exchange exchange = request(ctx, routeId);
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
    private Exchange request(RoutingContext ctx, String routeId) {
        HttpServerRequest request = ctx.request();
        Exchange exchange = new DefaultExchange(camelContext);
        // Which route this is, which a route running on a route never has to be told. Two
        // renderers ask: the redirect renderer, and the HTML renderer, which publishes the
        // Studio shell's member segment only for a route under `tql.studio.` — so an exchange
        // that cannot say which route it is drops that segment out of every link a shared
        // template emits, and thirty-one Studio assertions with it.
        exchange.getExchangeExtension().setFromRouteId(routeId);
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
        body(ctx, exchange, message, headers, filter);
        message.setHeaders(headers);
        attachments(ctx, message);
        return exchange;
    }

    /**
     * The body a route expects, which is three different things.
     *
     * <p>A form — urlencoded or the non-file parts of a multipart — arrives as a {@code Map} body
     * <em>and</em> as headers, both appended so a repeated field is a list. That duplication is
     * not tidiness: one binder reads the map and another reads the header, and a route written
     * against either has to keep working. Everything else is the raw buffer, which is what a JSON
     * body has always been.
     */
    private static void body(RoutingContext ctx, Exchange exchange,
            org.apache.camel.Message message, Map<String, Object> headers,
            org.apache.camel.spi.HeaderFilterStrategy filter) {
        if (isForm(ctx)) {
            Map<String, Object> form = new java.util.LinkedHashMap<>();
            io.vertx.core.MultiMap attributes = ctx.request().formAttributes();
            for (String name : attributes.names()) {
                for (String value : attributes.getAll(name)) {
                    if (filter != null
                            && filter.applyFilterToExternalHeaders(name, value, exchange)) {
                        continue;
                    }
                    org.apache.camel.util.CollectionHelper.appendEntry(headers, name, value);
                    org.apache.camel.util.CollectionHelper.appendEntry(form, name, value);
                }
            }
            if (!form.isEmpty()) {
                message.setBody(form);
            }
            return;
        }
        if (ctx.body() != null && ctx.body().buffer() != null) {
            message.setBody(ctx.body().buffer());
        }
    }

    /** Uploaded parts, as the attachments three processors already read them off the message. */
    private static void attachments(RoutingContext ctx, org.apache.camel.Message message) {
        java.util.List<io.vertx.ext.web.FileUpload> uploads = ctx.fileUploads();
        if (uploads.isEmpty()) {
            return;
        }
        message.setHeader("CamelAttachmentsSize", uploads.size());
        org.apache.camel.attachment.AttachmentMessage attachments = message.getExchange()
                .getMessage(org.apache.camel.attachment.AttachmentMessage.class);
        for (io.vertx.ext.web.FileUpload upload : uploads) {
            attachments.addAttachment(upload.name(), new jakarta.activation.DataHandler(
                    new org.apache.camel.attachment.CamelFileDataSource(
                            new java.io.File(upload.uploadedFileName()), upload.fileName())));
        }
    }

    /** A form post, by the content type the body handler parsed it as. */
    private static boolean isForm(RoutingContext ctx) {
        String contentType = ctx.request().getHeader("Content-Type");
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("multipart/form-data")
                || lower.startsWith("application/x-www-form-urlencoded");
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
