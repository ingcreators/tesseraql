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
 * Renders one transfer's state (design ch. 28) — as JSON for an API caller, and as the
 * self-polling job card for a browser (docs/csv-import.md decision 6).
 *
 * <p>One mount answers for both directions, because it always has: {@code buildFileImport} and
 * {@code buildFileExport} both call it, and a transfer row carries which it is. So the card's
 * done state is direction-aware from the start — an import's is what the database rejected, an
 * export's is the file itself — rather than retrofitted when the second consumer arrives.
 *
 * <p>An unknown id is a {@code 404} envelope for the API and a {@code 200} tombstone for the
 * card. That is not two answers to one question: a poller that receives an error keeps polling
 * an error, so staleness has to arrive as a state the card can stop on.
 */
public final class FileTransferStatusProcessor implements Step {

    private static final TqlErrorCode UNKNOWN = new TqlErrorCode(TqlDomain.LD, 2822);

    private final String urlPath;
    private final Path appHome;
    private final String defaultLocaleTag;
    private final String format;
    private final FileReadSpec readSpec;

    public FileTransferStatusProcessor(String urlPath) {
        this(urlPath, null, "en", null, null);
    }

    /**
     * @param format   the import's format, or null on an export route — what turns a data-row
     *                 ordinal into the reference the author reads (docs/csv-import.md decision 8)
     * @param readSpec the route's declared read spec, for the same reason. The route's rather
     *                 than the batch's: a reviewed import freezes the locale, and a locale moves
     *                 no rows, so the two agree on where a row sits.
     */
    public FileTransferStatusProcessor(String urlPath, Path appHome, String defaultLocaleTag,
            String format, FileReadSpec readSpec) {
        this.urlPath = urlPath;
        this.appHome = appHome;
        this.defaultLocaleTag = defaultLocaleTag;
        this.format = format;
        this.readSpec = readSpec;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        String transferId = exchange.request().param("transferId");
        FileTransferService transfers = exchange.beans().lookup(
                TesseraqlProperties.FILE_TRANSFER_BEAN,
                FileTransferService.class);
        FileTransferService.TransferStatus status = transfers == null
                ? null
                : transfers.status(transferId).orElse(null);
        if (Negotiation.prefersHtml(exchange)) {
            respondCard(exchange, transferId, status, transfers);
            return;
        }
        if (status == null) {
            throw new TqlException(UNKNOWN, "Unknown transfer: " + transferId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferId", status.transferId());
        body.put("route", status.routeId());
        body.put("direction", status.direction());
        body.put("status", status.status());
        // rowCount, not rows: `rows` is a list of records everywhere else on the wire
        // (docs/contract-bugfixes.md track D).
        body.put("rowCount", status.rows());
        if (status.expectedRows() != null) {
            body.put("expectedRows", status.expectedRows());
        }
        if (!status.errors().isEmpty()) {
            body.put("errors", FileImportProcessor.errorRows(status.errors()));
        }
        if ("EXPORT".equals(status.direction())) {
            body.put("filename", status.filename());
            body.put("downloaded", status.downloaded());
            if ("COMPLETED".equals(status.status())) {
                body.put("fileUrl", urlPath + "/" + status.transferId() + "/file");
            }
        }
        exchange.response().status(200);
        exchange.response().header(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.setBody(
                FileImportProcessor.MAPPER.writeValueAsString(body));
    }

    /** The browser's face: the card, or the tombstone when the id is not one this node knows. */
    private void respondCard(Exchange exchange, String transferId,
            FileTransferService.TransferStatus status, FileTransferService transfers)
            throws Exception {
        Locale locale = Locale.forLanguageTag(exchange.getProperty(TesseraqlProperties.LOCALE,
                defaultLocaleTag, String.class));
        io.tesseraql.yaml.i18n.MessageCatalog catalog = ImportPages.catalog(appHome);
        String statusUrl = io.tesseraql.pipeline.BasePath.url(exchange,
                urlPath + "/" + transferId);
        Map<String, Object> card = status == null
                ? JobCards.tombstone(transferId, catalog, locale)
                : JobCards.of(status, statusUrl, statusUrl + "/cancel",
                        row -> transfers.locate(format, readSpec, row), catalog, locale);
        ImportPages.renderCard(exchange, appHome, card, locale);
    }
}
