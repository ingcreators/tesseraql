package io.tesseraql.compiler.binding;

import io.tesseraql.pipeline.BasePath;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;

/**
 * What a transfer's kick-off answers a caller (docs/list-export.md decision 4), shared by the
 * import commit leg and the export start leg so the two cannot drift on what a browser is told.
 *
 * <p>A plain form post — a browser without JavaScript, which says {@code Accept: text/html} and
 * carries no {@code HX-Request} — gets post/redirect/get to the transfer's own URL, where the
 * status mount renders the job card inside the app's chrome. Every other caller keeps the
 * recipe's JSON 202: the transfer id, the status URL and, for an export, the file URL.
 * (An htmx caller's answer, the running card, is the next slice's arm.)
 */
final class TransferKickoff {

    private TransferKickoff() {
    }

    /** Whether this caller is a plain browser navigation: HTML wanted, not through htmx. */
    static boolean isPlainNavigation(Exchange exchange) {
        return Negotiation.prefersHtml(exchange)
                && !"true".equals(exchange.request().header("HX-Request"));
    }

    /**
     * The no-JS leg: <b>303</b> to {@code urlPath/transferId} as a wire URL. htmx surfaces a
     * redirect status to the XHR rather than to the tab, so this arm is only ever the plain
     * navigation's.
     */
    static void redirectToTransfer(Exchange exchange, String urlPath, String transferId) {
        String target = BasePath.url(exchange, urlPath + "/" + transferId);
        exchange.response().header(Headers.CONTENT_TYPE, "text/plain; charset=utf-8");
        exchange.setBody("");
        exchange.response().status(303);
        exchange.response().header("Location", target);
    }

    /**
     * An export's start answer: the redirect for a plain form post, the JSON 202 with the file
     * URL for everyone else.
     */
    static void respondExportStarted(Exchange exchange, String urlPath, String transferId) {
        if (isPlainNavigation(exchange)) {
            redirectToTransfer(exchange, urlPath, transferId);
            return;
        }
        FileImportProcessor.respondAccepted(exchange, urlPath, transferId, true);
    }
}
