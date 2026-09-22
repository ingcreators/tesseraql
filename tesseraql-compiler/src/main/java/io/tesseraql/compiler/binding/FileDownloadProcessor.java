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
 * {@code download}-timed follow-up statement. A HEAD (docs/edge-hygiene.md E4) answers the
 * GET's status and headers and takes nothing: the claim and the follow-up mean "the bytes were
 * fetched", and a HEAD is the one request that fetches none by definition.
 */
public final class FileDownloadProcessor implements Step {

    private static final TqlErrorCode UNKNOWN = new TqlErrorCode(TqlDomain.LD, 2822);
    public static final TqlErrorCode NOT_READY = new TqlErrorCode(TqlDomain.LD, 2823);

    private final String appName;
    private final String routeId;
    /** The route's {@code emit:} topics, announced when a download-timed follow-up commits. */
    private final java.util.List<String> emit;
    /** The route's {@code invalidates:} tables, dropped at the same commit. */
    private final java.util.List<String> invalidates;

    /**
     * Serves this application's {@code routeId}'s own files, no other ({@link TransferScope}),
     * and carries the route's declaration to the follow-up the first fetch runs
     * (docs/list-export.md): the statement runs on this request, so this request is where the
     * route's topics and tables are known — the shape a reviewed import's confirm leg has.
     */
    public FileDownloadProcessor(String appName, String routeId, java.util.List<String> emit,
            java.util.List<String> invalidates) {
        this.appName = appName;
        this.routeId = routeId;
        this.emit = java.util.List.copyOf(emit);
        this.invalidates = java.util.List.copyOf(invalidates);
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
        boolean head = "HEAD".equals(exchange.request().method());
        FileTransferService.Download download = (head
                ? transfers.inspect(transferId)
                : transfers.download(transferId, new FileTransferService.Announcement(emit,
                        invalidates, TransferTopics.tenant(exchange))))
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
