package io.tesseraql.runtime;

import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.RuntimeContext;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves compiled routes on the platform router, off the worker pool (docs/http-edge.md
 * decision 1).
 *
 * <p>{@code camel-platform-http-vertx} handed every exchange to {@code executeBlocking}, so a
 * request ran on a pool of ten platform threads whether or not it needed anything that pool
 * protects. That single choice is what the [HTTP threading](http-threading.md) campaign spent
 * eight slices working around. Here the route's own processors run on a virtual thread instead,
 * and the only bound left on work that needs a connection is the connection pool — measured at
 * <strong>3629 ms against 1046 ms</strong> for the same route under the same saturation.
 *
 * <p><strong>Every request the runtime serves is served here.</strong> The hand-back to a Camel
 * route behind this one went with slice 5 of that campaign, and the route behind it went with
 * docs/camel-removal.md decision 1 — a mount names the pipeline that answers, and there is nothing
 * else to fall through to. What keeps it checkable is unchanged: the whole integration suite
 * drives every route over real HTTP.
 */
final class RouteEdge {

    private static final Logger LOG = LoggerFactory.getLogger(RouteEdge.class);

    /** Registry name, so a hot reload can find the edge without being handed it. */
    static final String BEAN = "tesseraqlRouteEdge";

    /**
     * Ahead of the hand-written router surfaces, behind the admission gate.
     *
     * <p>Deliberately ahead of the shared body handler as well, which is why a request with a body
     * is handed back rather than read here: reading it would consume the stream the handler
     * behind us needs.
     */
    private static final int AFTER_THE_GATE = Integer.MIN_VALUE + 1;

    /** Large enough that an ordinary response is one chunk, small enough to bound a large one. */
    private static final int CHUNK_BYTES = 64 * 1024;

    /** TQL-ROUTE-5000, the code {@code ErrorResponseRenderer} uses for a failure it cannot map. */
    private static final String UNRENDERED_FAILURE = "{\"error\":{\"code\":\"TQL-ROUTE-5000\",\"message\":\"Internal error\"}}";

    private final RuntimeContext runtimeContext;
    private final Map<String, Route> mounted = new ConcurrentHashMap<>();
    /** What each mounted route was mounted as, so a reload can tell a moved URL from a stable one. */
    private final Map<String, io.tesseraql.pipeline.HttpMounts.Mount> at = new ConcurrentHashMap<>();
    private volatile io.vertx.ext.web.Router router;
    /** Requests being served right now, and the monitor a drain waits on. */
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Object idle = new Object();

    private RouteEdge(RuntimeContext runtimeContext) {
        this.runtimeContext = runtimeContext;
    }

    /**
     * Mounts every declared HTTP surface; returns the edge so reloads can refresh it.
     *
     * <p><strong>A surface this cannot serve fails the boot.</strong> There is no route
     * behind it any longer — the REST DSL that used to put one there is gone — so declining would
     * mean a declared URL answering 404 for the life of the process. A runtime that cannot serve
     * what it was asked to serve should say so while somebody is still watching it start.
     */
    static RouteEdge install(RuntimeContext runtimeContext, int port) {
        io.vertx.ext.web.Router router = HttpEdgeBeans.router(runtimeContext);
        RouteEdge edge = new RouteEdge(runtimeContext);
        edge.router = router;
        for (io.tesseraql.pipeline.HttpMounts.Mount mount : io.tesseraql.pipeline.HttpMounts
                .of(runtimeContext).all()) {
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
     * directory appearing on disk starts serving — and there is no route behind this one to
     * keep that promise for it any more.
     */
    void refreshAll() {
        java.util.Set<String> declared = new java.util.HashSet<>();
        for (io.tesseraql.pipeline.HttpMounts.Mount mount : io.tesseraql.pipeline.HttpMounts
                .of(runtimeContext).all()) {
            String routeId = mount.pipeline();
            if (!Pipelines.of(runtimeContext).contains(routeId)) {
                // Declared but not built: a save that did not compile and left no stub. The
                // watcher has to survive that, which is why a reload is tolerant where a boot is
                // strict — nobody is watching a reload, and a runtime that exits on a bad save
                // takes the good routes with it.
                continue;
            }
            declared.add(routeId);
            if (!(mounted.containsKey(routeId) && mount.equals(at.get(routeId)))) {
                // A recompiled route that kept its URL needs nothing from the edge: the serve
                // handler asks the registry per request, so the swap already happened there.
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
     * file watcher, and there is no route behind this one to catch it — so the router grows
     * a route rather than waiting for a restart. Tolerant, for the reason above: a reload that
     * cannot mount one route leaves the others serving.
     */
    private void remount(io.tesseraql.pipeline.HttpMounts.Mount mount, String routeId) {
        unmount(routeId);
        at.put(routeId, mount);
        mounted.put(routeId, router.route(HttpMethod.valueOf(mount.method()), path(mount.path()))
                .order(AFTER_THE_GATE)
                .handler(HttpEdgeBeans.bodyHandler(runtimeContext))
                .handler(ctx -> serve(ctx, routeId)));
    }

    /** Takes a route off the router, so a deleted route answers 404 rather than its last body. */
    private void unmount(String routeId) {
        Route route = mounted.remove(routeId);
        if (route != null) {
            route.remove();
        }
        at.remove(routeId);
    }

    private void mount(io.vertx.ext.web.Router router,
            io.tesseraql.pipeline.HttpMounts.Mount mount) {
        String routeId = mount.pipeline();
        if (!Pipelines.of(runtimeContext).contains(routeId)) {
            throw new IllegalStateException("The HTTP surface " + mount.method() + " "
                    + mount.path() + " names pipeline " + routeId + ", which was not compiled");
        }
        at.put(routeId, mount);
        // The body handler is the router's own — the instance the platform consumer would have used,
        // with whatever the server configured on it — so an upload spools where it already
        // spooled and a form parses the way it already parsed.
        mounted.put(routeId, router.route(HttpMethod.valueOf(mount.method()), path(mount.path()))
                .order(AFTER_THE_GATE)
                .handler(HttpEdgeBeans.bodyHandler(runtimeContext))
                .handler(ctx -> serve(ctx, routeId)));
    }

    /**
     * The URL the route answers at: the declared path under the application's base path, with
     * Declared {@code {name}} parameters spelled the way this router spells them.
     *
     * <p>The base path used to arrive from the context-wide REST configuration
     * (docs/base-path.md), which is gone, so the mount is where it goes on — the one place that
     * knows about URLs at all.
     */
    private String path(String declared) {
        StringBuilder mountedPath = new StringBuilder(
                io.tesseraql.pipeline.BasePath.of(runtimeContext.beans()));
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
        // Asked of the registry per request, which is what makes hot reload a swap: a recompiled
        // route replaces its entry there and this handler picks the new chain up on the next
        // request, with no router surgery (docs/vertx-native.md decision 4).
        io.tesseraql.compiler.pipeline.Pipeline pipeline = Pipelines.of(runtimeContext)
                .find(routeId).orElse(null);
        if (pipeline == null) {
            ctx.next();
            return;
        }
        Context connection = ctx.vertx().getOrCreateContext();
        Exchange exchange = request(ctx, routeId);
        // Counted here, because this is the only place that knows (docs/camel-removal.md
        // decision 1). It used to be registered in Camel's inflight repository under the route
        // id, so the shutdown strategy would wait for it — and that worked exactly as long as
        // there was a route by that id to wait on. A compiled route is a pipeline now, the
        // strategy has nothing to count, and replacing a runtime went back to cutting an
        // in-flight request mid-answer. The suite said so before anybody had to guess.
        inFlight.incrementAndGet();
        Thread.ofVirtual().name("tql-route-" + routeId).start(() -> {
            try {
                PipelineRunner.run(pipeline, exchange);
                respond(ctx, connection, exchange);
            } catch (Throwable unrendered) {
                // A failure no clause claimed. The pipeline's envelope answers everything a route
                // declares a handler for, and a route that declares none left the caller holding
                // an open connection until it timed out — silence being the one answer an HTTP
                // surface must never give. Found while giving every framework pipeline its clauses
                // explicitly (docs/camel-removal.md slice 2b).
                LOG.error("Route {} failed with nothing to render it", routeId, unrendered);
                failed(ctx, connection);
            } finally {
                if (inFlight.decrementAndGet() == 0) {
                    synchronized (idle) {
                        idle.notifyAll();
                    }
                }
            }
        });
    }

    /**
     * Waits for the requests this edge is serving, up to {@code millis}.
     *
     * <p>The drain contract (docs/runtime-replace.md): replacing a runtime lets what is in flight
     * finish rather than cutting it. The bound is the declared {@code tesseraql.shutdown.timeout},
     * the same one the shutdown strategy used for everything else, so a stop still has one number.
     *
     * @return whether everything finished inside the bound
     */
    boolean drain(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        synchronized (idle) {
            while (inFlight.get() > 0) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                try {
                    idle.wait(left);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        int left = inFlight.get();
        if (left > 0) {
            LOG.warn("{} request(s) still in flight after {} ms; the stop stops waiting", left,
                    millis);
        }
        return left == 0;
    }

    /**
     * The exchange the route's processors expect, in the shape they already expect it.
     *
     * <p>Every line here was found by reading what {@code camel-platform-http-vertx} did and
     * finding out what depended on it, so each one is a fact rather than a guess:
     * {@link Headers#HTTP_PATH} is the <em>normalized</em> path and not the raw one, the peer address
     * headers are what the network conditions on a role resolve against, request headers and
     * query parameters go through the inbound filter and are <em>appended</em> rather than
     * assigned so a repeated name becomes a list, and path parameters land after query parameters
     * because that is the order a route already reads them in.
     */
    private Exchange request(RoutingContext ctx, String routeId) {
        HttpServerRequest request = ctx.request();
        Exchange exchange = new Exchange(runtimeContext.beans());
        // Which route this is, which a route running on a route never has to be told. Two
        // renderers ask: the redirect renderer, and the HTML renderer, which publishes the
        // Studio shell's member segment only for a route under `tql.studio.` — so an exchange
        // that cannot say which route it is drops that segment out of every link a shared
        // template emits, and thirty-one Studio assertions with it.
        exchange.setFromRouteId(routeId);
        // A plain message: the request and the response are the handler's, not the message's.
        // The platform-http HttpMessage carried them so a processor could reach the raw Vert.x
        // objects, and nothing in this framework ever did.
        io.tesseraql.pipeline.Message message = exchange.getMessage();
        Map<String, Object> headers = new java.util.LinkedHashMap<>();
        headers.put(Headers.HTTP_PATH, ctx.normalizedPath());
        applyInbound(headers, request.headers());
        if (!ctx.queryParams().isEmpty()) {
            applyInbound(headers, ctx.queryParams());
        }
        ctx.pathParams().forEach((name, value) -> appendEntry(headers, name, value));
        if (request.localAddress() != null) {
            headers.put(io.tesseraql.pipeline.Headers.LOCAL_ADDRESS,
                    request.localAddress());
        }
        if (request.remoteAddress() != null) {
            headers.put(io.tesseraql.pipeline.Headers.REMOTE_ADDRESS,
                    request.remoteAddress());
        }
        headers.put(Headers.HTTP_METHOD, request.method().toString());
        headers.put(Headers.HTTP_URL, request.absoluteURI());
        headers.put(Headers.HTTP_URI, request.uri());
        headers.put(Headers.HTTP_QUERY, request.query());
        headers.put(Headers.HTTP_RAW_QUERY, request.query());
        body(ctx, exchange, message, headers);
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
            io.tesseraql.pipeline.Message message, Map<String, Object> headers) {
        if (isForm(ctx)) {
            Map<String, Object> form = new java.util.LinkedHashMap<>();
            io.vertx.core.MultiMap attributes = ctx.request().formAttributes();
            for (String name : attributes.names()) {
                for (String value : attributes.getAll(name)) {
                    if (!io.tesseraql.pipeline.HeaderFilter.enters(name)) {
                        continue;
                    }
                    appendEntry(headers, name, value);
                    appendEntry(form, name, value);
                }
            }
            if (!form.isEmpty()) {
                message.setBody(form);
            }
            return;
        }
        if (ctx.body() != null && ctx.body().buffer() != null) {
            // Bytes, not a Vert.x Buffer. The consumer set a Buffer because the component it
            // lived in also registered the converter that turned one into a String — and that
            // converter left with the component. A webhook verifying a signature over the raw
            // body, a multipart deploy reading its part, and an export reading its own response
            // all failed on the same missing conversion, which is a good argument for the
            // adapter handing over a type the framework already knows.
            message.setBody(ctx.body().buffer().getBytes());
        }
    }

    /** Uploaded parts, as the attachments three processors already read them off the message. */
    private static void attachments(RoutingContext ctx, io.tesseraql.pipeline.Message message) {
        java.util.List<io.vertx.ext.web.FileUpload> uploads = ctx.fileUploads();
        if (uploads.isEmpty()) {
            return;
        }
        for (io.vertx.ext.web.FileUpload upload : uploads) {
            // The spooled file, named as the client named it: a part is a name, a content type
            // and a stream, which is all three readers ever asked of it.
            message.attachments().put(upload.name(), io.tesseraql.pipeline.Message.part(
                    java.nio.file.Path.of(upload.uploadedFileName()), upload.contentType(),
                    upload.fileName()));
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
    private static void applyInbound(Map<String, Object> headers,
            io.vertx.core.MultiMap source) {
        source.forEach(entry -> {
            String name = entry.getKey();
            String value = entry.getValue();
            if (io.tesseraql.pipeline.HeaderFilter.enters(name)) {
                appendEntry(headers, name, value);
            }
        });
    }

    /**
     * Appends a value under {@code name}, so a repeated header or field becomes a list.
     *
     * <p>This was {@code CollectionHelper.appendEntry}, and the behaviour is load-bearing: one
     * binder reads a repeated form field as a list and another reads the single value, so a
     * second value must not silently replace the first.
     */
    private static void appendEntry(Map<String, Object> into, String name, Object value) {
        Object existing = into.get(name);
        if (existing == null) {
            into.put(name, value);
            return;
        }
        if (existing instanceof java.util.List<?> list) {
            java.util.List<Object> appended = new java.util.ArrayList<>(list);
            appended.add(value);
            into.put(name, appended);
            return;
        }
        into.put(name, new java.util.ArrayList<>(java.util.List.of(existing, value)));
    }

    /**
     * The last-resort answer, so a hung connection is never the outcome.
     *
     * <p>Deliberately without detail: the failure that got here is by definition one nothing was
     * written to describe, and the log is where its message belongs — the same rule
     * {@code ErrorResponseRenderer} follows for every other failure.
     */
    private void failed(RoutingContext ctx, Context connection) {
        connection.runOnContext(reply -> {
            if (ctx.response().ended()) {
                return;
            }
            ctx.response().setStatusCode(500)
                    .putHeader("Content-Type", "application/json; charset=utf-8")
                    .end(UNRENDERED_FAILURE);
        });
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
                        : Buffer.buffer(exchange.getMessage().getBody(String.class));
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
     * The response headers, decided by the same filter the platform edge decided them with.
     *
     * <p>Load-bearing rather than tidy. The request's own headers are on this message — they were
     * put there so the route could read {@code Cookie}, {@code Accept} and the rest — and copying
     * the message's headers out untouched would echo a caller's cookie back as a response header.
     * The component's {@code HeaderFilterStrategy} is the thing that already knows which headers
     * leave a runtime, including this framework's one amendment to it (the cache-control entry
     * declarative route caching needs on the wire), so it is asked rather than reimplemented.
     */
    private void headers(HttpServerResponse response, Exchange exchange) {
        Integer code = exchange.getMessage().getHeader(Headers.HTTP_RESPONSE_CODE, Integer.class);
        response.setStatusCode(code == null ? 200 : code);
        // The platform edge wrote the content type itself rather than through the filter, which strips it
        // from the generic copy; doing the same here is why a JSON response says so.
        String contentType = exchange.getMessage().getHeader(Headers.CONTENT_TYPE, String.class);
        if (contentType != null) {
            response.putHeader("Content-Type", contentType);
        }
        exchange.getMessage().getHeaders().forEach((name, value) -> {
            if (value == null || !io.tesseraql.pipeline.HeaderFilter.leaves(name)) {
                return;
            }
            response.putHeader(name, String.valueOf(value));
        });
    }
}
