package io.tesseraql.core.http;

/**
 * The base-path rule, as pure functions (docs/base-path.md): an application served under
 * {@code tesseraql.http.basePath} mounts its routes under that prefix and emits URLs that carry
 * it.
 *
 * <p>The rule the framework holds to is that <strong>a URL is base-relative everywhere inside the
 * runtime and acquires the prefix at the moment it becomes a wire URL</strong> — in markup at the
 * Thymeleaf link builder, in a response header at the redirect helper. A URL read back off the
 * request is already a wire URL and must not acquire it twice.
 *
 * <p>Living in core keeps the rule available to the OpenAPI generator, which runs at build time
 * with no runtime context; {@code io.tesseraql.pipeline.BasePath} adds the runtime's own prefix on top.
 */
public final class BasePaths {

    private BasePaths() {
    }

    /** Trims a configured prefix into {@code ""} or {@code /a/b}. */
    public static String normalize(String configured) {
        if (configured == null || configured.isBlank() || "/".equals(configured.trim())) {
            return "";
        }
        String trimmed = configured.trim();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * A base-relative path as the wire URL it is served at. Anything that does not address this
     * application by a root-relative path — an absolute {@code https://…}, a protocol-relative
     * {@code //host/x}, a fragment, an empty value — is returned untouched.
     */
    public static String join(String base, String path) {
        if (base.isEmpty() || path == null || !path.startsWith("/") || path.startsWith("//")) {
            return path;
        }
        return base + path;
    }

    /**
     * Whether a caller-supplied path stays inside this application: one leading slash, not
     * protocol-relative ({@code //host}), not a backslash trick ({@code /\}), no CR/LF. The
     * open-redirect guard shared by the login {@code next} target and the {@code location: back}
     * {@code _return} field (docs/list-surface.md decision 11) — anything else is discarded in
     * favor of the caller's fallback.
     */
    public static boolean isLocal(String path) {
        return path != null
                && path.startsWith("/")
                && !path.startsWith("//")
                && !path.startsWith("/\\")
                && path.indexOf('\n') < 0
                && path.indexOf('\r') < 0;
    }

    /**
     * The base-relative form of a wire URL — the inverse of {@link #join}, for the places that
     * read a path back off the request and hand it to something that will prefix it again, such
     * as the login page's {@code next} target.
     */
    public static String relative(String base, String url) {
        if (base.isEmpty() || url == null) {
            return url;
        }
        if (url.equals(base)) {
            return "/";
        }
        return url.startsWith(base + "/") ? url.substring(base.length()) : url;
    }
}
