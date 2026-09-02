package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.util.LinkedHashMap;
import java.util.Map;

/** Renders one transfer's state as JSON (design ch. 28); unknown ids are 404. */
public final class FileTransferStatusProcessor implements Step {

    private static final TqlErrorCode UNKNOWN = new TqlErrorCode(TqlDomain.LD, 2822);

    private final String urlPath;

    public FileTransferStatusProcessor(String urlPath) {
        this.urlPath = urlPath;
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

}
