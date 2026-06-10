package io.tesseraql.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * Serves static assets under {@code GET /assets/**} (design ch. 12, 40): css/js/images from the
 * main app's {@code assets/} directory, from each mounted app's {@code assets/} under
 * {@code /assets/<app-name>/}, framework-bundled assets under {@code /assets/_tesseraql/}, and
 * vendored WebJar libraries under {@code /assets/vendor/<webjar>/<path>} (self-hosted, so pages
 * never reference external CDNs).
 *
 * <p>Only whitelisted extensions are served; paths are confined to their asset root and hidden
 * segments are rejected. Responses carry an {@code ETag} (content hash) honoring
 * {@code If-None-Match} with 304, and a short {@code Cache-Control}.
 */
final class AssetsRouteBuilder extends RouteBuilder {

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

    private static final String FRAMEWORK_PREFIX = "_tesseraql";
    private static final String VENDOR_PREFIX = "vendor";
    private static final String FRAMEWORK_RESOURCES = "tesseraql/assets/";
    private static final String WEBJAR_RESOURCES = "META-INF/resources/webjars/";

    private final Path mainAssets;
    private final Map<String, Path> appAssets;
    private final Map<String, String> etags = new ConcurrentHashMap<>();

    /**
     * @param mainAssets the main app's assets directory (may not exist)
     * @param appAssets  mounted-app name to its assets directory (existing directories only)
     */
    AssetsRouteBuilder(Path mainAssets, Map<String, Path> appAssets) {
        this.mainAssets = mainAssets.toAbsolutePath().normalize();
        this.appAssets = Map.copyOf(appAssets);
    }

    @Override
    public void configure() {
        // The default platform-http filter drops response caching headers (Cache-Control is in its
        // generic HTTP filter list); assets need them, so use a strategy that only filters
        // Camel-internal headers.
        org.apache.camel.support.DefaultHeaderFilterStrategy headers =
                new org.apache.camel.support.DefaultHeaderFilterStrategy();
        headers.setOutFilterPattern("(?i)(Camel|org\\.apache\\.camel)[\\.|a-zA-Z0-9]*");
        getContext().getRegistry().bind("tqlAssetHeaderFilter", headers);

        from("platform-http:/assets?matchOnUriPrefix=true&httpMethodRestrict=GET"
                + "&headerFilterStrategy=#tqlAssetHeaderFilter")
                .routeId("tql.assets")
                .process(this::serve);
    }

    private void serve(Exchange exchange) throws IOException {
        String path = requestPath(exchange);
        String ifNoneMatch = exchange.getMessage().getHeader("If-None-Match", String.class);
        // Drop the inbound request headers so they are not echoed into the response.
        exchange.getMessage().getHeaders().clear();
        if (path == null || path.isBlank() || !extensionAllowed(path) || hasHiddenSegment(path)) {
            notFound(exchange);
            return;
        }
        byte[] bytes = resolve(path);
        if (bytes == null) {
            notFound(exchange);
            return;
        }
        String etag = etags.computeIfAbsent(path, key -> "\"" + sha256(bytes) + "\"");
        exchange.getMessage().setHeader("ETag", etag);
        exchange.getMessage().setHeader("Cache-Control", "public, max-age=300");
        exchange.getMessage().setHeader("X-Content-Type-Options", "nosniff");
        if (etag.equals(ifNoneMatch)) {
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 304);
            exchange.getMessage().setBody("");
            return;
        }
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, contentType(path));
        exchange.getMessage().setBody(bytes);
    }

    /** Resolves the asset bytes: framework/vendor classpath, a mounted app, or the main app. */
    private byte[] resolve(String path) throws IOException {
        int slash = path.indexOf('/');
        String first = slash < 0 ? path : path.substring(0, slash);
        String rest = slash < 0 ? "" : path.substring(slash + 1);
        if (FRAMEWORK_PREFIX.equals(first)) {
            return classpathBytes(FRAMEWORK_RESOURCES + rest);
        }
        if (VENDOR_PREFIX.equals(first)) {
            return classpathBytes(WEBJAR_RESOURCES + rest);
        }
        if (appAssets.containsKey(first)) {
            return fileBytes(appAssets.get(first), rest);
        }
        return fileBytes(mainAssets, path);
    }

    private byte[] fileBytes(Path root, String relative) throws IOException {
        if (relative.isBlank()) {
            return null;
        }
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return null;
        }
        return Files.readAllBytes(file);
    }

    private byte[] classpathBytes(String resource) throws IOException {
        // The classpath name was traversal-checked as URL segments; reject any residual dots.
        if (resource.contains("..")) {
            return null;
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static String requestPath(Exchange exchange) {
        String raw = exchange.getMessage().getHeader(Exchange.HTTP_PATH, String.class);
        if (raw == null) {
            return null;
        }
        String path = raw.startsWith("/assets") ? raw.substring("/assets".length()) : raw;
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

    private static boolean extensionAllowed(String path) {
        return CONTENT_TYPES.containsKey(extension(path));
    }

    private static String contentType(String path) {
        return CONTENT_TYPES.get(extension(path));
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static void notFound(Exchange exchange) {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 404);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "text/plain; charset=utf-8");
        exchange.getMessage().setBody("Not Found");
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
