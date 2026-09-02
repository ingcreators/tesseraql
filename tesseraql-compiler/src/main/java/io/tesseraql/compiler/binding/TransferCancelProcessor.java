package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Asks a running transfer to stop (docs/csv-import.md decision 6): {@code POST
 * {path}/{transferId}/cancel}.
 *
 * <p>Cooperative, and the answer says so. The request sets a flag the import's row loop reads
 * between rows, so this responds with the transfer's state <em>now</em> rather than with the
 * state it is about to reach — and the card the browser gets back is still a running card, which
 * keeps polling and shows the stop when it lands. Claiming "cancelled" here would be a promise
 * made by the wrong side of the boundary.
 *
 * <p>A finished run has nothing to stop, and that is not an error: the answer is the terminal
 * card, which is exactly what the caller wanted to know.
 */
public final class TransferCancelProcessor implements Step {

    private static final TqlErrorCode NO_SERVICE = new TqlErrorCode(TqlDomain.LD, 2821);
    private static final TqlErrorCode UNKNOWN = new TqlErrorCode(TqlDomain.LD, 2822);

    private final String urlPath;
    private final Path appHome;
    private final String defaultLocaleTag;
    private final String format;
    private final FileReadSpec readSpec;

    public TransferCancelProcessor(String urlPath, Path appHome, String defaultLocaleTag,
            String format, FileReadSpec readSpec) {
        this.urlPath = urlPath;
        this.appHome = appHome;
        this.defaultLocaleTag = defaultLocaleTag;
        this.format = format;
        this.readSpec = readSpec;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        FileTransferService transfers = exchange.beans().lookup(
                TesseraqlProperties.FILE_TRANSFER_BEAN, FileTransferService.class);
        if (transfers == null) {
            throw new TqlException(NO_SERVICE, "File transfer service is not configured");
        }
        String transferId = exchange.request().param("transferId");
        boolean requested = transfers.cancel(transferId);
        FileTransferService.TransferStatus status = transfers.status(transferId).orElse(null);
        if (Negotiation.prefersHtml(exchange)) {
            Locale locale = Locale.forLanguageTag(exchange.getProperty(
                    TesseraqlProperties.LOCALE, defaultLocaleTag, String.class));
            io.tesseraql.yaml.i18n.MessageCatalog catalog = ImportPages.catalog(appHome);
            String statusUrl = io.tesseraql.pipeline.BasePath.url(exchange,
                    urlPath + "/" + transferId);
            ImportPages.renderCard(exchange, appHome, status == null
                    ? JobCards.tombstone(transferId, catalog, locale)
                    : JobCards.of(status, statusUrl, statusUrl + "/cancel",
                            row -> transfers.locate(format, readSpec, row), catalog, locale),
                    locale);
            return;
        }
        if (status == null) {
            throw new TqlException(UNKNOWN, "Unknown transfer: " + transferId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferId", transferId);
        // What was asked, not what happened: the loop decides when, at its next row boundary.
        body.put("cancelRequested", requested);
        body.put("status", status.status());
        exchange.response().status(200);
        exchange.response().header(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.setBody(FileImportProcessor.MAPPER.writeValueAsString(body));
    }
}
