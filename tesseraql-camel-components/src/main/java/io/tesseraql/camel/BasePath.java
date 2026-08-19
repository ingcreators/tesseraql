package io.tesseraql.camel;

import io.tesseraql.core.http.BasePaths;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;

/**
 * The prefix this runtime's application is served under ({@code tesseraql.http.basePath},
 * docs/base-path.md), and the one place a framework-built URL acquires it.
 *
 * <p>{@link BasePaths} states the rule; this reads the running application's value. It is bound in
 * the Camel registry rather than threaded through constructors, for the same reason
 * {@link TesseraqlProperties#RESPONSE_HEADERS_BEAN} is: the surfaces that need it are hand-written
 * route builders and processors the compiler never sees.
 *
 * <p>Under an active role (docs/application-roles.md structural decision 5) the effective prefix
 * for this request is {@code basePath + "/_as/" + role} — the exchange-aware reads append it, so
 * every emitted URL keeps the caller's capacity structurally. One carve-out: a target under
 * {@code assets/} skips the segment, because an asset is role-independent and keying the browser
 * cache by role would duplicate it.
 */
public final class BasePath {

    private BasePath() {
    }

    /** Publishes the normalized prefix for every surface that emits a URL. */
    public static void bind(CamelContext context, String configured) {
        context.getRegistry().bind(TesseraqlProperties.BASE_PATH_BEAN,
                BasePaths.normalize(configured));
    }

    /** The application's prefix, {@code ""} when it is served at the root of its origin. */
    public static String of(CamelContext context) {
        if (context == null) {
            return "";
        }
        String bound = context.getRegistry().lookupByNameAndType(
                TesseraqlProperties.BASE_PATH_BEAN, String.class);
        return bound == null ? "" : bound;
    }

    /** The effective prefix of this exchange: the application's, plus its activation segment. */
    public static String of(Exchange exchange) {
        if (exchange == null) {
            return "";
        }
        return of(exchange.getContext()) + activationSegment(exchange);
    }

    /** A base-relative path as the wire URL this application serves it at. */
    public static String url(Exchange exchange, String path) {
        if (isAsset(path)) {
            return BasePaths.join(of(exchange == null ? null : exchange.getContext()), path);
        }
        return BasePaths.join(of(exchange), path);
    }

    /** The base-relative form of a wire URL read back off the request. */
    public static String relative(Exchange exchange, String url) {
        return BasePaths.relative(of(exchange), url);
    }

    /** The {@code /_as/<role>} segment of a request with an activated role, else {@code ""}. */
    public static String activationSegment(Exchange exchange) {
        String acting = exchange == null
                ? null
                : exchange.getProperty(TesseraqlProperties.ACTING_ROLE, String.class);
        return acting == null ? "" : "/_as/" + encodeSegment(acting);
    }

    /** A role code as a path segment: URL-encoded, with the form-encoding {@code +} corrected. */
    public static String encodeSegment(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    /** Whether a base-relative target is an asset — role-independent, so no activation segment. */
    private static boolean isAsset(String path) {
        if (path == null) {
            return false;
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        return normalized.equals("assets") || normalized.startsWith("assets/");
    }
}
