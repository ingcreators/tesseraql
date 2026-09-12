package io.tesseraql.pipeline;

import io.tesseraql.core.http.BasePaths;
import io.tesseraql.core.http.PercentEncoding;

/**
 * The prefix this runtime's application is served under ({@code tesseraql.http.basePath},
 * docs/base-path.md), and the one place a framework-built URL acquires it.
 *
 * <p>{@link BasePaths} states the rule; this reads the running application's value. It is bound in
 * the runtime's registry rather than threaded through constructors, for the same reason
 * {@link TesseraqlProperties#RESPONSE_HEADERS_BEAN} is: the surfaces that need it are hand-written
 * framework routes and processors the compiler never sees.
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
    public static void bind(RuntimeContext context, String configured) {
        context.bind(TesseraqlProperties.BASE_PATH_BEAN,
                BasePaths.normalize(configured));
    }

    /** The application's prefix, {@code ""} when it is served at the root of its origin. */
    public static String of(io.tesseraql.pipeline.Beans beans) {
        if (beans == null) {
            return "";
        }
        String bound = beans.lookup(TesseraqlProperties.BASE_PATH_BEAN, String.class);
        return bound == null ? "" : bound;
    }

    /** The effective prefix of this exchange: the application's, plus its activation segment. */
    public static String of(Exchange exchange) {
        if (exchange == null) {
            return "";
        }
        return of(exchange.beans()) + activationSegment(exchange);
    }

    /**
     * A base-relative path as the wire URL this application serves it at: the prefix joined on,
     * then the whole reference percent-encoded once as a URI literal
     * ({@link io.tesseraql.core.http.PercentEncoding#uriLiteral}) — on the joined result, the
     * prefix included, never before the join, so an authored {@code %XX} stays one triplet.
     * Everything inside the runtime is characters; this is the moment a URL becomes wire text
     * (docs/base-path-emission.md decision 1), so this is where a non-ASCII route path, a
     * non-ASCII prefix or a {@code _return} read back decoded off the request become the bytes a
     * request line can carry. ASCII, an authored triplet included, is left alone, so a value that
     * was already a wire URL passes unchanged. A null path passes through as null, as the join
     * hands it back; the encoder itself treats null as a caller error.
     */
    public static String url(Exchange exchange, String path) {
        String joined = isAsset(path)
                ? BasePaths.join(of(exchange == null ? null : exchange.beans()), path)
                : BasePaths.join(of(exchange), path);
        return joined == null ? null : PercentEncoding.uriLiteral(joined);
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

    /**
     * A value as a path segment: URL-encoded, with the form-encoding {@code +} corrected to
     * {@code %20}. The one rule for every segment the framework builds from data — an activated
     * role code, a workflow document key — because a path parameter is not decoded as a form
     * field, so a {@code +} here addresses a different resource rather than the same one.
     */
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
