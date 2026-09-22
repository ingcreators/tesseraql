package io.tesseraql.compiler.binding;

import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.pipeline.BasePath;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * What a transfer's kick-off answers a caller (docs/list-export.md decision 4), shared by the
 * import commit leg and the export start leg so the two cannot drift on what a browser is told.
 *
 * <p>Three faces. An htmx caller gets <b>202</b> and the running job card, which then polls
 * itself (docs/csv-import.md decision 6). A plain form post — a browser without JavaScript,
 * which says {@code Accept: text/html} and carries no {@code HX-Request} — gets post/redirect/get
 * to the transfer's own URL, where the status mount renders the same card inside the app's
 * chrome. Every other caller keeps the recipe's JSON 202: the transfer id, the status URL and,
 * for an export, the file URL.
 *
 * <p>Two shapes for one outcome on the browser side, and deliberately so: htmx surfaces a
 * redirect status to the XHR rather than to the tab, so a 303 there would swap the redirect's
 * target into a page region. The card is what replaces the redirect for that caller.
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
     * The no-JS leg: <b>303</b> to {@code urlPath/transferId} as a wire URL.
     */
    static void redirectToTransfer(Exchange exchange, String urlPath, String transferId) {
        String target = BasePath.url(exchange, urlPath + "/" + transferId);
        exchange.response().header(Headers.CONTENT_TYPE, "text/plain; charset=utf-8");
        exchange.setBody("");
        exchange.response().status(303);
        exchange.response().header("Location", target);
    }

    /**
     * The browser's answer: the running card for an htmx caller, the redirect for a plain
     * navigation. The card is {@code JobCards.of} over the transfer just started, rendered as
     * the bare fragment the status poll will answer with two seconds later — one markup
     * source. {@code locate} turns a data-row ordinal into the reference an import's report
     * reads; an export hands the service's own.
     */
    static void respondBrowser(Exchange exchange, String urlPath, String transferId,
            Path appHome, String defaultLocaleTag, FileTransferService transfers,
            ImportReports.RowLocator locate) {
        if (!"true".equals(exchange.request().header("HX-Request"))) {
            redirectToTransfer(exchange, urlPath, transferId);
            return;
        }
        String target = BasePath.url(exchange, urlPath + "/" + transferId);
        FileTransferService.TransferStatus status = transfers.status(transferId).orElse(null);
        Locale locale = Locale.forLanguageTag(exchange.getProperty(TesseraqlProperties.LOCALE,
                defaultLocaleTag, String.class));
        io.tesseraql.yaml.i18n.MessageCatalog catalog = ImportPages.catalog(appHome);
        Map<String, Object> card = status == null
                ? JobCards.tombstone(transferId, catalog, locale)
                : JobCards.of(status, target, target + "/cancel", locate, catalog, locale);
        exchange.response().header(Headers.CONTENT_TYPE, "text/html; charset=utf-8");
        exchange.setBody(ImportPages.render(exchange, appHome, card, locale,
                "tql/view/job-card"));
        // 202, not 200: the run was accepted and is going, and the card is how the caller
        // watches it — the async-job contract's own status code, answered where a page kicks
        // a job off.
        exchange.response().status(202);
    }

    /**
     * An export's start answer: the card or the redirect for a browser, the JSON 202 with the
     * file URL for everyone else.
     */
    static void respondExportStarted(Exchange exchange, String urlPath, String transferId,
            Path appHome, String defaultLocaleTag, FileTransferService transfers) {
        if (Negotiation.prefersHtml(exchange)) {
            respondBrowser(exchange, urlPath, transferId, appHome, defaultLocaleTag, transfers,
                    row -> transfers.locate(null, null, row));
            return;
        }
        FileImportProcessor.respondAccepted(exchange, urlPath, transferId, true);
    }
}
