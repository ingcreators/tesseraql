package io.tesseraql.camel;

import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.RuntimeContext;

/**
 * The {@code Path} this runtime's session cookie is issued with, as the host that started it
 * decided (docs/base-path.md decision 4).
 *
 * <p>It is not the base path, and cannot be derived from it. A standalone application behind a
 * proxy at {@code /myapp} wants its cookie scoped to {@code /myapp}, so it is not offered to
 * whatever else lives on that origin. A shared stack wants {@code /}, because one sign-in
 * reaching every application <em>is</em> the mode. Only the component that starts the runtimes
 * knows which of those it is building, so it carries the value; a configuration key was
 * considered and rejected, an operator setting it wrongly getting either a silently unshared
 * stack or a session offered to every neighbour, neither of which announces itself.
 */
public final class CookiePath {

    private CookiePath() {
    }

    /** Publishes the cookie path for every surface that issues or expires a session cookie. */
    public static void bind(RuntimeContext context, String cookiePath) {
        context.bind(TesseraqlProperties.COOKIE_PATH_BEAN,
                cookiePath == null || cookiePath.isBlank() ? "/" : cookiePath);
    }

    /** This runtime's cookie path; {@code /} unless a host said otherwise. */
    public static String of(io.tesseraql.pipeline.Beans beans) {
        if (beans == null) {
            return "/";
        }
        String bound = beans.lookup(TesseraqlProperties.COOKIE_PATH_BEAN, String.class);
        return bound == null ? "/" : bound;
    }

    /** The cookie path of the application serving this exchange. */
    public static String of(Exchange exchange) {
        return exchange == null ? "/" : of(exchange.beans());
    }
}
