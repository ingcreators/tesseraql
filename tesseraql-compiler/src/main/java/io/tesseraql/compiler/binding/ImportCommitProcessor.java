package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Spends a reviewed import batch (docs/csv-import.md decision 5): the confirm leg of an
 * {@code import.review: required} route.
 *
 * <p>The token is single-shot by a conditional claim in the store, taken <em>before</em> the run,
 * so a double click, a replayed form or a back-button re-post loses the race and is told to
 * upload again rather than importing twice. The claim is the batch row itself — the same row the
 * write is about — which is why this leg deliberately does not also carry an idempotency key.
 *
 * <p>What the route declares (the per-row statement, the failure policy) comes from here; the
 * read spec comes from the parked batch, because the locale is resolved per request and this is
 * a different request from the one that parsed the file.
 */
public final class ImportCommitProcessor implements Step {

    private static final TqlErrorCode NO_SERVICE = new TqlErrorCode(TqlDomain.LD, 2821);
    private static final TqlErrorCode TOKEN_MISMATCH = new TqlErrorCode(TqlDomain.LD, 2867);

    private final String routeId;
    private final String urlPath;
    private final String appName;
    private final String format;
    private final FileReadSpec readSpec;
    private final Path rowSqlFile;
    private final String onError;
    private final Path appHome;
    private final String defaultLocaleTag;
    /** The route's {@code emit:} topics, announced when the import's transaction commits. */
    private final java.util.List<String> emit;

    /** The shape before the confirm leg had a page to answer. */
    public ImportCommitProcessor(String routeId, String urlPath, String appName, String format,
            FileReadSpec readSpec, Path rowSqlFile, String onError) {
        this(routeId, urlPath, appName, format, readSpec, rowSqlFile, onError, null, "en",
                java.util.List.of());
    }

    public ImportCommitProcessor(String routeId, String urlPath, String appName, String format,
            FileReadSpec readSpec, Path rowSqlFile, String onError, Path appHome,
            String defaultLocaleTag, java.util.List<String> emit) {
        this.emit = java.util.List.copyOf(emit);
        this.appHome = appHome;
        this.defaultLocaleTag = defaultLocaleTag;
        this.routeId = routeId;
        this.urlPath = urlPath;
        this.appName = appName;
        this.format = format;
        this.readSpec = readSpec;
        this.rowSqlFile = rowSqlFile;
        this.onError = onError;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        FileTransferService transfers = exchange.beans().lookup(
                TesseraqlProperties.FILE_TRANSFER_BEAN,
                FileTransferService.class);
        if (transfers == null) {
            throw new TqlException(NO_SERVICE, "File transfer service is not configured");
        }
        String batchId = exchange.request().param("batchId");
        // The upstream contract puts the token in the path AND in a hidden field, and says the
        // pair must match. Reading one and ignoring the other would make the hidden field
        // decorative; a form that disagrees with its own action is a bug worth naming.
        String posted = exchange.request().param("token");
        if (posted != null && !posted.isBlank() && !posted.equals(batchId)) {
            String mismatch = "The confirm form's token does not match the address it posted to";
            throw TqlException.builder(TOKEN_MISMATCH)
                    .message(mismatch)
                    .details(java.util.Map.of("message", mismatch))
                    .build();
        }
        String transferId;
        try {
            transferId = transfers.commitImport(batchId,
                    FileImportProcessor.subject(exchange),
                    // No contract built here on purpose: the commit is held to the one parked
                    // with the batch, so resolving the catalogs again would be a query per
                    // catalog whose answer is thrown away — and the once-per-import promise
                    // would be false on the very leg that repeats the parse.
                    new FileTransferService.ImportRequest(routeId, appName, format, readSpec,
                            rowSqlFile, onError, null)
                            .announcing(emit, ImportTopics.tenant(exchange)));
        } catch (TqlException refusal) {
            // A refusal that declared human-safe text is one the confirming caller is meant to
            // act on — that is what `details.message` means (docs/csv-import.md decision 5) —
            // and on a page it belongs in the report region, not on an error page that loses
            // the upload form the fix needs. Anything else keeps the ordinary error path.
            if (!Negotiation.prefersHtml(exchange)
                    || !(refusal.details().get("message") instanceof String sentence)) {
                throw refusal;
            }
            respondStale(exchange, sentence);
            return;
        }
        if (Negotiation.prefersHtml(exchange)) {
            respondBrowser(exchange, transferId, transfers);
            return;
        }
        FileImportProcessor.respondAccepted(exchange, urlPath, transferId, false);
    }

    /**
     * The stale-token answer (docs/csv-import.md decision 5): <b>409</b> and the fragment that
     * says so, swapped into the report region the confirm form was in. The fix for a stale
     * token is always a fresh upload and never a retry, so the fragment says that and the
     * button it replaces is gone — the upload form above it is untouched and is the way back.
     *
     * <p>It carries the import marker rather than the field-errors one: the bootstrap's swap
     * allowance is substring-gated per fragment kind precisely so each kind states itself, and
     * borrowing another kind's marker to get past the gate would be a lie about what this is.
     */
    private void respondStale(Exchange exchange, String sentence) {
        exchange.response().status(409);
        exchange.response().header(io.tesseraql.pipeline.Headers.CONTENT_TYPE,
                "text/html; charset=utf-8");
        exchange.setBody("<div class=\"hc-stack\" data-tql-import-report>"
                + "<div class=\"hc-alert\" data-variant=\"error\" role=\"alert\">"
                + "<p class=\"hc-alert__title\">"
                + io.tesseraql.core.text.Escapes.html(sentence)
                + "</p><p class=\"hc-alert__body\">"
                + io.tesseraql.core.text.Escapes.html(reupload(exchange))
                + "</p></div></div>");
    }

    /** "Upload it again", in the request's own language. */
    private String reupload(Exchange exchange) {
        io.tesseraql.yaml.i18n.MessageCatalog catalog = appHome == null
                ? io.tesseraql.yaml.i18n.I18nSettings.builtinCatalog()
                : io.tesseraql.yaml.i18n.MessageCatalog.live(appHome.resolve("messages"))
                        .withFallback(io.tesseraql.yaml.i18n.I18nSettings.builtinCatalog());
        String tag = exchange.getProperty(TesseraqlProperties.LOCALE, defaultLocaleTag,
                String.class);
        return ViewMessages.text(catalog, java.util.Locale.forLanguageTag(tag),
                "tql.import.stale", "Upload the file again — a confirmation cannot be retried.");
    }

    /**
     * The browser's answer (docs/csv-import.md decision 6). An htmx caller gets <b>202</b> and
     * the running job card, which then polls itself; a plain form post gets post/redirect/get to
     * the transfer's own URL, where the same card renders inside the app's chrome.
     *
     * <p>Two shapes for one outcome, and deliberately so: htmx surfaces a redirect status to the
     * XHR rather than to the tab, so a 303 there would swap the redirect's target into a page
     * region. The card is what replaces the redirect for that caller — and the no-JS leg is
     * untouched by its arrival, which is the property that made shipping the redirect first
     * safe.
     */
    private void respondBrowser(Exchange exchange, String transferId,
            FileTransferService transfers) {
        String target = io.tesseraql.pipeline.BasePath.url(exchange, urlPath + "/" + transferId);
        if (!"true".equals(exchange.request().header("HX-Request"))) {
            exchange.response().header(io.tesseraql.pipeline.Headers.CONTENT_TYPE,
                    "text/plain; charset=utf-8");
            exchange.setBody("");
            exchange.response().status(303);
            exchange.response().header("Location", target);
            return;
        }
        FileTransferService.TransferStatus status = transfers.status(transferId).orElse(null);
        Locale locale = Locale.forLanguageTag(exchange.getProperty(TesseraqlProperties.LOCALE,
                defaultLocaleTag, String.class));
        io.tesseraql.yaml.i18n.MessageCatalog catalog = ImportPages.catalog(appHome);
        Map<String, Object> card = status == null
                ? JobCards.tombstone(transferId, catalog, locale)
                : JobCards.of(status, target, target + "/cancel",
                        row -> transfers.locate(format, readSpec, row), catalog, locale);
        exchange.response().header(io.tesseraql.pipeline.Headers.CONTENT_TYPE,
                "text/html; charset=utf-8");
        exchange.setBody(ImportPages.render(exchange, appHome, card, locale,
                "tql/view/job-card"));
        // 202, not 200: the import was accepted and is running, and the card is how the caller
        // watches it. This is the one place the framework answers the async-job contract's own
        // status code, because it is the one place it kicks a job off from a page.
        exchange.response().status(202);
    }
}
