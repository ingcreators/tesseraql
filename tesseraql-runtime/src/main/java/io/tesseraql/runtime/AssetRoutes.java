package io.tesseraql.runtime;

import io.tesseraql.pipeline.RuntimeContext;
import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serves static assets under {@code GET /assets/**} directly on the platform router
 * (docs/http-threading.md decision 6): off the worker pool, and without reading a file into the
 * heap to answer for it.
 *
 * <p>They were a Camel route, so every stylesheet, script and icon took a worker for its whole
 * duration — the same ten workers a slow query holds — and a page's worth of assets waited behind
 * whatever the database was doing. Each request also read the file <em>entirely into memory</em>
 * and SHA-256'd it, including to answer 304: the cheapest possible response cost a full read and
 * a full hash. A twenty-megabyte image on ten concurrent requests was two hundred megabytes of
 * heap that nothing had asked for.
 *
 * <p><strong>One rule decides where a request runs.</strong> An asset already held in memory is
 * answered on the event loop, because answering it is a map lookup and a write. Anything that has
 * to reach storage — the first read of a classpath resource, the generated message module whose
 * catalog is read live, and every file — runs on this instance's own virtual threads. Nothing in
 * either path touches the worker pool, which is the coupling this exists to remove, and nothing
 * blocking runs on the event loop, which is the way that coupling could have been reintroduced.
 *
 * <p>A file is streamed rather than buffered: chunks are read on the virtual thread and handed to
 * the connection's context one at a time, each waiting for the previous write to complete. That
 * wait is what keeps a large file from becoming a large write queue — backpressure a virtual
 * thread can express by blocking, which is why this is where virtual threads belong. The
 * classpath half is cached, safe because those bytes come out of jars and cannot change while the
 * process runs; the mutable half is exactly the half that is never cached, which is why the
 * stale-validator bug that removed the previous cache cannot recur.
 *
 * <p><strong>Public by design, and this is where that is now said.</strong> As a Camel route it
 * was carried in {@code FrameworkSurfaces.PUBLIC_BY_DESIGN} — "static asset bytes, served before
 * any session exists" — the registry that lets a reviewer tell a declared public surface from a
 * forgotten one. A router handler is not a route and has no auth gate to be exempted from, so the
 * entry was removed rather than left pointing at nothing; the reasoning it carried lives here.
 * Nothing behind this mount is per-user: the extension allow-list, the confinement to the asset
 * root and the hidden-segment rejection are what keep it that way.
 *
 * <p>The behaviour the Camel route defined is kept: the extension allow-list, confinement to the
 * asset root, hidden-segment rejection, the version-less WebJar mount, {@code If-None-Match} with
 * 304, {@code Cache-Control}, {@code nosniff}, the CORS opening for Studio's sandboxed preview,
 * and the app's {@code security.responseHeaders}.
 */
final class AssetRoutes implements RuntimeContext.Service {

    /**
     * After the admission gate, before every compiled route.
     *
     * <p>The gate skips this prefix: it bounds work that occupies a worker, and an asset no longer
     * does. Refusing a stylesheet because the database is slow would put back the coupling this
     * whole change exists to remove.
     */
    private static final int AFTER_THE_GATE = Integer.MIN_VALUE + 1;

    /**
     * How much of a file is read before the connection is written to.
     *
     * <p>Large enough that an ordinary asset is one chunk and one hop to the event loop, small
     * enough that a large file never has more than this in flight — the property that makes
     * streaming worth doing at all.
     */
    private static final int CHUNK_BYTES = 64 * 1024;

    private static final String FRAMEWORK_PREFIX = "_tesseraql";
    private static final String VENDOR_PREFIX = "vendor";
    private static final String FRAMEWORK_RESOURCES = "tesseraql/assets/";
    private static final String WEBJAR_RESOURCES = "META-INF/resources/webjars/";
    private static final String MESSAGES_MODULE = "_tesseraql/messages.js";

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("map", "application/json"),
            Map.entry("json", "application/json"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("txt", "text/plain; charset=utf-8"));

    /** One classpath asset: its bytes and the strong validator for them, hashed once. */
    private record Jarred(byte[] bytes, String etag) {
    }

    /** What the handler read off the request before leaving the event loop. */
    private record Asked(String path, String ifNoneMatch, String locale) {
    }

    private final RuntimeContext runtimeContext;
    private final Path mainAssets;
    private final Map<String, Path> appAssets;
    private final ClientMessages clientMessages;
    private final org.webjars.WebJarVersionLocator webJars = new org.webjars.WebJarVersionLocator();
    /**
     * The classpath half, held after its first read.
     *
     * <p>Bounded by what the classpath actually holds — a path that resolves to no resource is
     * never stored — and immutable for the life of the process, so there is no invalidation
     * question to get wrong. Files are deliberately absent: those are the ones an operator edits.
     */
    private final Map<String, Jarred> jarred = new ConcurrentHashMap<>();
    /**
     * Where storage is read: this runtime's own threads, and nobody else's.
     *
     * <p>Vert.x streams a file with {@code sendFile}, but dispatches the reads behind it to the
     * worker pool, so an asset served that way stayed coupled to exactly what moving it off a
     * Camel route was meant to escape — measured at 1689 ms for a file against 7 ms for a
     * classpath asset, with every worker held in the database, and at 22 ms against 23 ms once
     * the read moved here. Virtual threads fit here as they fit nowhere else in this design: file
     * I/O blocks on something no connection pool bounds, a thread costs nothing while it waits,
     * and the JDK compensates its carriers rather than letting the blocking spread. No second permit guards it — the admission gate deliberately
     * does not bound assets, and a bound here would be that refusal under another name.
     *
     * <p>This executor is the whole reason the class is a service: there is nothing here to start,
     * and it is registered so that something shuts these threads down when the context goes
     * (docs/camel-removal.md decision 2), which matters to a host that stops and replaces one
     * application while the process keeps running.
     */
    private final ExecutorService storage = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("tql-asset-io-", 0).factory());

    private AssetRoutes(RuntimeContext runtimeContext, Path mainAssets, Map<String, Path> appAssets,
            ClientMessages clientMessages) {
        this.runtimeContext = runtimeContext;
        this.mainAssets = mainAssets.toAbsolutePath().normalize();
        this.appAssets = Map.copyOf(appAssets);
        this.clientMessages = clientMessages;
    }

    /** Mounts the asset tree on the started platform router, under the app's base path. */
    static void install(RuntimeContext runtimeContext, Path mainAssets,
            Map<String, Path> appAssets, ClientMessages clientMessages) {
        io.vertx.ext.web.Router router = HttpEdgeBeans.router(runtimeContext);
        AssetRoutes assets = new AssetRoutes(runtimeContext, mainAssets, appAssets, clientMessages);
        try {
            runtimeContext.addService(assets);
        } catch (Exception refused) {
            throw new IllegalStateException("Static asset serving could not be started", refused);
        }
        String mount = io.tesseraql.pipeline.BasePath
                .of(runtimeContext.beans()) + "/assets";
        router.route(HttpMethod.GET, mount + "/*").order(AFTER_THE_GATE)
                .handler(ctx -> assets.serve(ctx, mount));
    }

    /** The mount an admission gate must let through untouched. */
    static String mountOf(RuntimeContext runtimeContext) {
        return io.tesseraql.pipeline.BasePath.of(runtimeContext.beans())
                + "/assets";
    }

    @Override
    public void stop() {
        storage.shutdownNow();
    }

    private void serve(RoutingContext ctx, String mount) {
        String path = requestPath(ctx.request().path(), mount);
        if (path == null || path.isBlank() || !CONTENT_TYPES.containsKey(extension(path))
                || hasHiddenSegment(path)) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        Jarred held = jarred.get(path);
        if (held != null) {
            // Already in memory: a lookup and a write, which is what the event loop is for.
            sendBytes(ctx.response(), path, held.bytes(), held.etag(),
                    ctx.request().getHeader("If-None-Match"));
            return;
        }
        fromStorage(ctx, new Asked(path, ctx.request().getHeader("If-None-Match"),
                ctx.request().getParam("locale")));
    }

    /**
     * Everything that has to read something, on a virtual thread of this runtime's own.
     *
     * <p>The request is read here, on the event loop, and only the values travel: a handler that
     * has left its context must not reach back into it for them. Response mutations make the
     * opposite trip — every one of them is dispatched to the connection's context, which is the
     * only thread allowed to touch a response, and the queue preserves their order.
     */
    private void fromStorage(RoutingContext ctx, Asked asked) {
        HttpServerResponse response = ctx.response();
        Context connection = ctx.vertx().getOrCreateContext();
        AtomicBoolean gone = new AtomicBoolean();
        response.closeHandler(closed -> gone.set(true));
        response.exceptionHandler(failure -> gone.set(true));
        storage.execute(() -> {
            try {
                if (MESSAGES_MODULE.equals(asked.path())) {
                    // Generated per locale rather than read, so it has no file to stream and no
                    // stable identity to cache: hashed on the spot, as it always was. Its catalog
                    // is read live off disk, which is why it belongs on this side of the line.
                    byte[] script = clientMessages.script(asked.locale());
                    deliver(connection, gone, response, asked, script,
                            "\"" + sha256(script) + "\"");
                    return;
                }
                byte[] fromJar = jarBytes(asked.path());
                if (fromJar != null) {
                    deliver(connection, gone, response, asked, fromJar,
                            jarred.get(asked.path()).etag());
                    return;
                }
                Path file = file(asked.path());
                if (file == null) {
                    notFound(connection, gone, response);
                    return;
                }
                stream(connection, gone, response, asked, file);
            } catch (IOException unreadable) {
                notFound(connection, gone, response);
            }
        });
    }

    /**
     * A file, streamed a chunk at a time.
     *
     * <p>Its validator is {@code (mtime, size)} rather than a content hash, and weak because that
     * is what it is. A strong validator would mean reading and hashing the whole file to answer
     * every request — including the 304s, which are the requests worth making cheap — so the
     * content hash was the reason the old route could not stream. Weak validators are what a web
     * server has always used here.
     *
     * <p>Each chunk waits for the previous write to complete before the next read starts, so a
     * file arrives at the speed the client takes it and never at the speed the disk gives it. A
     * client that leaves mid-file stops the read at the next chunk boundary rather than paying
     * for the rest of the file it will not receive.
     */
    private void stream(Context connection, AtomicBoolean gone, HttpServerResponse response,
            Asked asked, Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        long size = attributes.size();
        String etag = "W/\"" + attributes.lastModifiedTime().toMillis() + "-" + size + "\"";
        if (etag.equals(asked.ifNoneMatch())) {
            onContext(connection, () -> {
                headers(response, etag);
                response.setStatusCode(304).end();
            });
            return;
        }
        onContext(connection, () -> {
            headers(response, etag);
            response.putHeader("Content-Type", CONTENT_TYPES.get(extension(asked.path())));
            response.putHeader("Content-Length", Long.toString(size));
            response.setStatusCode(200);
        });
        long written = 0;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] chunk = new byte[CHUNK_BYTES];
            int read;
            while (!gone.get() && (read = in.read(chunk)) > 0) {
                write(connection, gone, response, Buffer.buffer(Arrays.copyOf(chunk, read)));
                written += read;
            }
        }
        long delivered = written;
        onContext(connection, () -> {
            if (gone.get() || response.ended()) {
                return;
            }
            if (delivered == size) {
                response.end();
            } else {
                // The file changed under the read, so the length already announced is a promise
                // this response cannot keep. Dropping the connection tells the client the
                // transfer failed; ending it short would leave the client waiting for bytes that
                // are never coming.
                response.reset();
            }
        });
    }

    /** Bytes already in hand, delivered on the connection's context. */
    private void deliver(Context connection, AtomicBoolean gone, HttpServerResponse response,
            Asked asked, byte[] bytes, String etag) {
        onContext(connection, () -> {
            if (gone.get() || response.ended()) {
                return;
            }
            sendBytes(response, asked.path(), bytes, etag, asked.ifNoneMatch());
        });
    }

    private void sendBytes(HttpServerResponse response, String path, byte[] bytes, String etag,
            String ifNoneMatch) {
        headers(response, etag);
        if (etag.equals(ifNoneMatch)) {
            response.setStatusCode(304).end();
            return;
        }
        response.putHeader("Content-Type", CONTENT_TYPES.get(extension(path)));
        response.setStatusCode(200).end(Buffer.buffer(bytes));
    }

    private void notFound(Context connection, AtomicBoolean gone, HttpServerResponse response) {
        onContext(connection, () -> {
            if (!gone.get() && !response.ended()) {
                response.setStatusCode(404).end();
            }
        });
    }

    /** The response headers every asset carries, whether it is answered 200 or 304. */
    private void headers(HttpServerResponse response, String etag) {
        response.putHeader("ETag", etag);
        response.putHeader("Cache-Control", "public, max-age=300");
        response.putHeader("X-Content-Type-Options", "nosniff");
        // Public static assets are CORS-readable: Studio's opt-in live preview runs in an
        // opaque-origin sandbox (allow-scripts WITHOUT allow-same-origin), and ES module loads
        // from there are CORS-gated. Assets carry no credentials or per-user data.
        response.putHeader("Access-Control-Allow-Origin", "*");
        // The app's security.responseHeaders: an asset is a response leaving the runtime like any
        // other, and it is served by a hand-written surface the compiler never sees.
        securityHeaders().forEach(response::putHeader);
    }

    /**
     * Runs one response mutation on the connection's context — the only thread allowed to touch a
     * response — without waiting for it. The context runs what it is given in the order it was
     * given, so headers precede the first chunk and the end follows the last one without this
     * side of the handover ever blocking to find out.
     */
    private static void onContext(Context connection, Runnable mutation) {
        connection.runOnContext(run -> mutation.run());
    }

    /**
     * Writes one chunk and waits for the connection to have taken it.
     *
     * <p>This wait is the backpressure: without it a slow client would be served at disk speed
     * into Vert.x's write queue, which is the heap cost this whole decision removes, only spelled
     * differently.
     */
    private static void write(Context connection, AtomicBoolean gone, HttpServerResponse response,
            Buffer chunk) throws IOException {
        CompletableFuture<Void> written = new CompletableFuture<>();
        connection.runOnContext(run -> {
            if (gone.get() || response.ended()) {
                written.completeExceptionally(new IOException("The client closed the connection"));
                return;
            }
            response.write(chunk).onComplete(result -> {
                if (result.succeeded()) {
                    written.complete(null);
                } else {
                    gone.set(true);
                    written.completeExceptionally(result.cause());
                }
            });
        });
        try {
            // get(), not join(): this is the one place a shutting-down runtime has to be able to
            // interrupt, since a client that stops reading would otherwise hold the thread for as
            // long as it likes.
            written.get();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            gone.set(true);
            throw new IOException("The runtime stopped while the file was being sent", stopped);
        } catch (java.util.concurrent.ExecutionException failed) {
            gone.set(true);
            throw new IOException(failed.getCause());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> securityHeaders() {
        Map<String, String> headers = runtimeContext.lookup(
                io.tesseraql.pipeline.TesseraqlProperties.RESPONSE_HEADERS_BEAN, Map.class);
        return headers == null ? Map.of() : headers;
    }

    /** The classpath half — framework assets and vendored WebJars — read once and held. */
    private byte[] jarBytes(String path) throws IOException {
        Jarred held = jarred.get(path);
        if (held != null) {
            return held.bytes();
        }
        int slash = path.indexOf('/');
        String first = slash < 0 ? path : path.substring(0, slash);
        String rest = slash < 0 ? "" : path.substring(slash + 1);
        String resource;
        if (FRAMEWORK_PREFIX.equals(first)) {
            resource = FRAMEWORK_RESOURCES + rest;
        } else if (VENDOR_PREFIX.equals(first)) {
            // Version-less vendor URLs (/assets/vendor/<webjar>/<file>): the WebJar version is
            // resolved from the classpath, so an upgrade is a pom bump with templates unchanged.
            int nameEnd = rest.indexOf('/');
            if (nameEnd < 0) {
                return null;
            }
            String webjar = rest.substring(0, nameEnd);
            String version = webJars.version(webjar);
            if (version == null) {
                return null;
            }
            resource = WEBJAR_RESOURCES + webjar + "/" + version + "/"
                    + rest.substring(nameEnd + 1);
        } else {
            return null;
        }
        // The classpath name was traversal-checked as URL segments; reject any residual dots.
        if (resource.contains("..")) {
            return null;
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            byte[] bytes = in.readAllBytes();
            jarred.put(path, new Jarred(bytes, "\"" + sha256(bytes) + "\""));
            return bytes;
        }
    }

    /** The filesystem half: a mounted app's tree, or the main app's. */
    private Path file(String path) {
        int slash = path.indexOf('/');
        String first = slash < 0 ? path : path.substring(0, slash);
        String rest = slash < 0 ? "" : path.substring(slash + 1);
        return appAssets.containsKey(first)
                ? confined(appAssets.get(first), rest)
                : confined(mainAssets, path);
    }

    private static Path confined(Path root, String relative) {
        if (relative.isBlank()) {
            return null;
        }
        Path file = root.resolve(relative).normalize();
        return file.startsWith(root) && Files.isRegularFile(file) ? file : null;
    }

    private static String requestPath(String raw, String mount) {
        if (raw == null) {
            return null;
        }
        String path = raw.startsWith(mount)
                ? raw.substring(mount.length())
                : raw.startsWith("/assets") ? raw.substring("/assets".length()) : raw;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    private static boolean hasHiddenSegment(String path) {
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        return dot < 0 || dot < slash
                ? ""
                : path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }
}
