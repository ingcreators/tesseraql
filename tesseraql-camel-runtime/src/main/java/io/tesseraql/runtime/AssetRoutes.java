package io.tesseraql.runtime;

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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.camel.CamelContext;
import org.apache.camel.component.platform.http.vertx.VertxPlatformHttpRouter;

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
 * <p>Here a file is answered with {@code sendFile} — the event loop streams it, nothing is
 * buffered — and the classpath half is cached, which is safe because those bytes come out of jars
 * and cannot change while the process runs. The mutable half is exactly the half that is never
 * cached, which is why the stale-validator bug that removed the previous cache cannot recur.
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
final class AssetRoutes {

    /**
     * After the admission gate, before every route Camel registered.
     *
     * <p>The gate skips this prefix: it bounds work that occupies a worker, and an asset no longer
     * does. Refusing a stylesheet because the database is slow would put back the coupling this
     * whole change exists to remove.
     */
    private static final int AFTER_THE_GATE = Integer.MIN_VALUE + 1;

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

    private final CamelContext camelContext;
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

    private AssetRoutes(CamelContext camelContext, Path mainAssets, Map<String, Path> appAssets,
            ClientMessages clientMessages) {
        this.camelContext = camelContext;
        this.mainAssets = mainAssets.toAbsolutePath().normalize();
        this.appAssets = Map.copyOf(appAssets);
        this.clientMessages = clientMessages;
    }

    /** Mounts the asset tree on the started platform router, under the app's base path. */
    static void install(CamelContext camelContext, int port, Path mainAssets,
            Map<String, Path> appAssets, ClientMessages clientMessages) {
        VertxPlatformHttpRouter router = VertxPlatformHttpRouter.lookup(camelContext,
                VertxPlatformHttpRouter.getRouterNameFromPort(port));
        AssetRoutes assets = new AssetRoutes(camelContext, mainAssets, appAssets, clientMessages);
        String mount = io.tesseraql.camel.BasePath.of(camelContext) + "/assets";
        router.route(HttpMethod.GET, mount + "/*").order(AFTER_THE_GATE)
                .handler(ctx -> assets.serve(ctx, mount));
    }

    /** The mount an admission gate must let through untouched. */
    static String mountOf(CamelContext camelContext) {
        return io.tesseraql.camel.BasePath.of(camelContext) + "/assets";
    }

    private void serve(RoutingContext ctx, String mount) {
        String path = requestPath(ctx.request().path(), mount);
        if (path == null || path.isBlank() || !CONTENT_TYPES.containsKey(extension(path))
                || hasHiddenSegment(path)) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        try {
            if (MESSAGES_MODULE.equals(path)) {
                // Generated per locale rather than read, so it has no file to stream and no
                // stable identity to cache: hashed on the spot, as it always was.
                byte[] script = clientMessages.script(ctx.request().getParam("locale"));
                sendBytes(ctx, path, script, "\"" + sha256(script) + "\"");
                return;
            }
            byte[] fromJar = jarBytes(path);
            if (fromJar != null) {
                sendBytes(ctx, path, fromJar, jarred.get(path).etag());
                return;
            }
            Path file = file(path);
            if (file == null) {
                ctx.response().setStatusCode(404).end();
                return;
            }
            sendFile(ctx, path, file);
        } catch (IOException unreadable) {
            ctx.response().setStatusCode(404).end();
        }
    }

    /**
     * A file, streamed.
     *
     * <p>Its validator is {@code (mtime, size)} rather than a content hash, and weak because that
     * is what it is. A strong validator would mean reading and hashing the whole file to answer
     * every request — including the 304s, which are the requests worth making cheap — so the
     * content hash was the reason the old route could not stream. Weak validators are what a web
     * server has always used here.
     */
    private void sendFile(RoutingContext ctx, String path, Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        String etag = "W/\"" + attributes.lastModifiedTime().toMillis() + "-"
                + attributes.size() + "\"";
        if (headers(ctx, path, etag)) {
            return;
        }
        ctx.response().setStatusCode(200).sendFile(file.toString());
    }

    private void sendBytes(RoutingContext ctx, String path, byte[] bytes, String etag) {
        if (headers(ctx, path, etag)) {
            return;
        }
        ctx.response().setStatusCode(200).end(io.vertx.core.buffer.Buffer.buffer(bytes));
    }

    /** The common response headers; true when the client's validator matched and 304 was sent. */
    private boolean headers(RoutingContext ctx, String path, String etag) {
        HttpServerResponse response = ctx.response();
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
        if (etag.equals(ctx.request().getHeader("If-None-Match"))) {
            response.setStatusCode(304).end();
            return true;
        }
        response.putHeader("Content-Type", CONTENT_TYPES.get(extension(path)));
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> securityHeaders() {
        Map<String, String> headers = camelContext.getRegistry().lookupByNameAndType(
                io.tesseraql.camel.TesseraqlProperties.RESPONSE_HEADERS_BEAN, Map.class);
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
