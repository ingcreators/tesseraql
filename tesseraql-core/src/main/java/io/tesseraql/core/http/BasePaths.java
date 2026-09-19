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
     * protocol-relative ({@code //host}), not a backslash trick ({@code /\}), no control
     * character. The open-redirect guard shared by the login {@code next} target and the
     * {@code location: back} {@code _return} field (docs/list-surface.md decision 11) — anything
     * else is discarded in favor of the caller's fallback.
     *
     * <p>Every C0 control and DEL is refused, not only CR and LF: a browser deletes a tab, CR or
     * LF from a URL before parsing it, so {@code /<TAB>/host/x} navigates to {@code //host/x} —
     * off-site, past the two prefix checks above. No control character has a place in a return
     * target, so all of them are refused together.
     */
    public static boolean isLocal(String path) {
        return path != null
                && path.startsWith("/")
                && !path.startsWith("//")
                && !path.startsWith("/\\")
                && noControl(path);
    }

    private static boolean noControl(String path) {
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
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
        // A URL read back off the request is wire text, so the base it carries is the base's
        // wire spelling (docs/router-unicode-names.md R1): under /受注 a _return of
        // /%E5%8F%97%E6%B3%A8/things kept its prefix here, and the redirect helper joined a
        // second one. ASCII is its own wire form, so the two spellings coincide there. The
        // wire spelling is compared with its hex folded to upper case, as the gateway compares a
        // member's prefix: a link written by a client that spells %e5 kept its prefix here too
        // (docs/audit-low-leads.md DN-01c). The remainder is cut from the URL as sent.
        String stripped = strip(PercentEncoding.uriLiteral(base), url,
                PercentEncoding.upperHex(url));
        return stripped != null
                ? stripped
                : java.util.Objects.requireNonNullElse(
                        strip(base, url, url), url);
    }

    /**
     * {@code url} without {@code prefix}, or null when the prefix does not address it;
     * {@code comparable} is the spelling the prefix is matched against, {@code url} the text the
     * remainder is cut from. The bare base followed by a query is the base's root page with that
     * query — a list entered at {@code /shop?page=2} — not a stranger's path
     * (docs/audit-low-leads.md unfiled 69).
     */
    private static String strip(String prefix, String url, String comparable) {
        if (comparable.equals(prefix)) {
            return "/";
        }
        if (comparable.startsWith(prefix + "/")) {
            return url.substring(prefix.length());
        }
        return comparable.startsWith(prefix + "?") ? "/" + url.substring(prefix.length()) : null;
    }
}
