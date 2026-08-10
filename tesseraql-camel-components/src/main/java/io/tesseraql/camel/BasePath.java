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

    /** The prefix of the application serving this exchange. */
    public static String of(Exchange exchange) {
        return exchange == null ? "" : of(exchange.getContext());
    }

    /** A base-relative path as the wire URL this application serves it at. */
    public static String url(Exchange exchange, String path) {
        return BasePaths.join(of(exchange), path);
    }

    /** The base-relative form of a wire URL read back off the request. */
    public static String relative(Exchange exchange, String url) {
        return BasePaths.relative(of(exchange), url);
    }
}
