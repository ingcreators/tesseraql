package io.tesseraql.compiler.binding;

import io.tesseraql.pipeline.Exchange;

/**
 * Which face of a content-negotiated contract a caller asked for.
 *
 * <p>A browser is either htmx — which sends {@code HX-Request} and whose {@code Accept} is
 * whatever the page inherited — or a plain form post, which sends an {@code Accept} naming
 * {@code text/html}. Everything else is an API caller and keeps the JSON contract untouched,
 * including a browser that asked for JSON explicitly.
 */
final class Negotiation {

    private Negotiation() {
    }

    /** Whether this caller wants the page rather than the JSON. */
    static boolean prefersHtml(Exchange exchange) {
        if ("true".equals(exchange.request().header("HX-Request"))) {
            return true;
        }
        String accept = exchange.request().header("Accept");
        // An explicit JSON preference wins even alongside text/html: a caller that named the
        // JSON contract asked for it, and */* is not naming anything.
        return accept != null && accept.contains("text/html")
                && !accept.startsWith("application/json");
    }
}
