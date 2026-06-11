package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.FileTransferService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/** Renders one transfer's state as JSON (design ch. 28); unknown ids are 404. */
public final class FileTransferStatusProcessor implements Processor {

    private static final TqlErrorCode UNKNOWN = new TqlErrorCode(TqlDomain.LD, 2822);

    private final String urlPath;

    public FileTransferStatusProcessor(String urlPath) {
        this.urlPath = urlPath;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        String transferId = exchange.getMessage().getHeader("transferId", String.class);
        FileTransferService transfers = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.FILE_TRANSFER_BEAN,
                        FileTransferService.class);
        FileTransferService.TransferStatus status = transfers == null
                ? null : transfers.status(transferId).orElse(null);
        if (status == null) {
            throw new TqlException(UNKNOWN, "Unknown transfer: " + transferId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferId", status.transferId());
        body.put("route", status.routeId());
        body.put("direction", status.direction());
        body.put("status", status.status());
        body.put("rows", status.rows());
        if (!status.errors().isEmpty()) {
            body.put("errors", errorRows(status.errors()));
        }
        if ("EXPORT".equals(status.direction())) {
            body.put("filename", status.filename());
            body.put("downloaded", status.downloaded());
            if ("COMPLETED".equals(status.status())) {
                body.put("fileUrl", urlPath + "/" + status.transferId() + "/file");
            }
        }
        exchange.getMessage().removeHeaders("*", Exchange.CONTENT_TYPE);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(
                FileImportProcessor.MAPPER.writeValueAsString(body));
    }

    private static List<Map<String, Object>> errorRows(
            List<FileTransferService.RowError> errors) {
        return errors.stream()
                .map(error -> Map.<String, Object>of(
                        "row", error.row(), "message", String.valueOf(error.message())))
                .toList();
    }
}
