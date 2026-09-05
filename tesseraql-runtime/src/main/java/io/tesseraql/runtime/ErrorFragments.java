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
     * Whether this caller is asking for a page rather than a document.
     *
     * <p>A pre-route refusal is written before anything has negotiated content, so this is the
     * whole of the negotiation: an {@code Accept} naming HTML is a browser navigating, and
     * anything else — an API client, a fetch, an EventSource — keeps the JSON envelope it has
     * always been given.
     */
    static boolean wantsHtml(String accept) {
        return accept != null && accept.contains("text/html");
    }

    /**
     * A capacity refusal as a page, for a caller that asked for one.
     *
     * <p>A browser navigating to a busy runtime, or posting a snapshot pager's native form to
     * one, was handed the framework's JSON error envelope and painted it as the whole
     * document. This is the same fact in something a person can read.
     *
     * <p>A fixed string written on the event loop: no template engine, no route, no worker —
     * the same cost the refusal already pays, on the path whose entire purpose is to shed load.
     * The application's own error template is deliberately not consulted, and the rule is one
     * rule for every pre-route refusal: a handler running ahead of any route holds neither the
     * app home nor a negotiated locale, so rendering an app template here would spend exactly
     * the work the refusal exists to avoid. English by construction, for the reason the body
     * limit's fragment already records.
     *
     * <p>No {@code TQL-} code is spelled anywhere in this file, deliberately: the reference
     * generator reads a code mentioned in a javadoc as a site that raises it, and this class
     * raises none — it renders whichever code its caller hands it.
     */
    static String busyPage(TqlErrorCode code, String sentence) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<title>Busy</title></head><body>"
                + "<h1>The service is busy</h1>"
                + "<p>" + sentence + "</p>"
                + "<p>Please try again in a moment.</p>"
                + "<p><small>" + code + "</small></p>"
                + "</body></html>";
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
