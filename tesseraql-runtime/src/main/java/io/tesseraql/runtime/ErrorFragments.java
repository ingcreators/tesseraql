package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlErrorCode;

/**
 * The markup a pre-route refusal answers an htmx request with (docs/hypermedia-ui.md).
 *
 * <p>The framework bootstrap swaps a 4xx only when its body carries an allowance marker, and a
 * router-level JSON envelope carries none — so a browser meeting one of these refusals used to
 * render nothing at all. {@code data-hc-field-errors} is that marker.
 *
 * <p>Held here rather than in either handler because both the body limit's 413 and the form
 * decoder's 400 answer with the same shape, and two copies of a markup contract is how they
 * drift apart. Everything about it is English and route-free by construction: a handler running
 * before any route has neither a negotiated locale nor a route to read one from.
 */
final class ErrorFragments {

    private ErrorFragments() {
    }

    /**
     * A field-errors alert carrying the code, a title and one sentence.
     *
     * @param code  the refusal's code, published on the alert so a support question can name it
     * @param title what went wrong, in one short sentence
     * @param body  what the caller can do about it, naming the bound rather than the route
     */
    static String fieldErrors(TqlErrorCode code, String title, String body) {
        return "<div class=\"hc-alert\" data-variant=\"error\" role=\"alert\""
                + " data-hc-field-errors data-error-code=\"" + code + "\">"
                + "<p class=\"hc-alert__title\">" + title + "</p>"
                + "<p class=\"hc-alert__body\">" + body + "</p></div>";
    }
}
