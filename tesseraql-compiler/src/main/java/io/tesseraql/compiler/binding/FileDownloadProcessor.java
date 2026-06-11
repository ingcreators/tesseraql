package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.FileTransferService;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Streams a completed export's file (design ch. 28). Unknown transfers are 404, exports that are
 * still running (or failed) are 409; the first successful fetch triggers a {@code download}-timed
 * follow-up statement.
 */
public final class FileDownloadProcessor implements Processor {

    private static final TqlErrorCode UNKNOWN = new TqlErrorCode(TqlDomain.LD, 2822);
    private static final TqlErrorCode NOT_READY = new TqlErrorCode(TqlDomain.LD, 2823);

    @Override
    public void process(Exchange exchange) {
        String transferId = exchange.getMessage().getHeader("transferId", String.class);
        FileTransferService transfers = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.FILE_TRANSFER_BEAN,
                        FileTransferService.class);
        if (transfers == null || transfers.status(transferId).isEmpty()) {
            throw new TqlException(UNKNOWN, "Unknown transfer: " + transferId);
        }
        FileTransferService.Download download = transfers.download(transferId)
                .orElseThrow(() -> new TqlException(NOT_READY,
                        "Transfer " + transferId + " has no downloadable file (not an export,"
                                + " still running, or failed)"));
        exchange.getMessage().removeHeaders("*");
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, download.contentType());
        exchange.getMessage().setHeader("Content-Disposition",
                "attachment; filename=\"" + download.filename().replaceAll("[\\r\\n\"]", "_")
                        + "\"");
        exchange.getMessage().setBody(download.content());
    }
}
