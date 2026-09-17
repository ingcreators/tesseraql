package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;

/**
 * Streams a completed export's file (design ch. 28). Unknown transfers are 404, exports that are
 * still running (or failed, or stopped) are 409, a completed export whose bytes this node cannot
 * open is 410 (the service's own refusal); the first successful fetch triggers a
 * {@code download}-timed follow-up statement.
 */
public final class FileDownloadProcessor implements Step {

    private static final TqlErrorCode UNKNOWN = new TqlErrorCode(TqlDomain.LD, 2822);
    public static final TqlErrorCode NOT_READY = new TqlErrorCode(TqlDomain.LD, 2823);

    private final String appName;
    private final String routeId;

    /** Serves this application's {@code routeId}'s own files, no other ({@link TransferScope}). */
    public FileDownloadProcessor(String appName, String routeId) {
        this.appName = appName;
        this.routeId = routeId;
    }

    @Override
    public void process(Exchange exchange) {
        String transferId = exchange.request().param("transferId");
        FileTransferService transfers = exchange.beans().lookup(
                TesseraqlProperties.FILE_TRANSFER_BEAN,
                FileTransferService.class);
        if (TransferScope.own(transfers, transferId, appName, routeId, exchange).isEmpty()) {
            throw new TqlException(UNKNOWN, "Unknown transfer: " + transferId);
        }
        FileTransferService.Download download = transfers.download(transferId)
                .orElseThrow(() -> new TqlException(NOT_READY,
                        "Transfer " + transferId + " has no downloadable file (not an export,"
                                + " still running, stopped, or failed)"));
        exchange.response().status(200);
        exchange.response().header(Headers.CONTENT_TYPE, download.contentType());
        exchange.response().header("Content-Disposition",
                io.tesseraql.core.http.ContentDisposition.attachment(download.filename()));
        exchange.setBody(download.content());
    }
}
