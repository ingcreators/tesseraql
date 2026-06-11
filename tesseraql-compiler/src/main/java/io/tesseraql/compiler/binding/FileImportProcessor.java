package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileTransferService;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Accepts an uploaded file body and starts its asynchronous import (design ch. 28): the raw
 * request body is the file content, the response is 202 with the transfer id and status URL.
 */
public final class FileImportProcessor implements Processor {

    private static final TqlErrorCode EMPTY_BODY = new TqlErrorCode(TqlDomain.LD, 2820);
    private static final TqlErrorCode NO_SERVICE = new TqlErrorCode(TqlDomain.LD, 2821);

    private final String routeId;
    private final String urlPath;
    private final String appName;
    private final String format;
    private final FileReadSpec readSpec;
    private final Path rowSqlFile;
    private final String onError;

    public FileImportProcessor(String routeId, String urlPath, String appName, String format,
            FileReadSpec readSpec, Path rowSqlFile, String onError) {
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
        byte[] content = body(exchange);
        if (content == null || content.length == 0) {
            throw new TqlException(EMPTY_BODY,
                    "file-import expects the uploaded file as the request body");
        }
        FileTransferService transfers = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.FILE_TRANSFER_BEAN,
                        FileTransferService.class);
        if (transfers == null) {
            throw new TqlException(NO_SERVICE, "File transfer service is not configured");
        }
        String transferId = transfers.startImport(new FileTransferService.ImportRequest(
                routeId, appName, format, readSpec, rowSqlFile, onError), content);
        respondAccepted(exchange, urlPath, transferId, false);
    }

    private static byte[] body(Exchange exchange) throws Exception {
        Object body = exchange.getMessage().getBody();
        if (body instanceof byte[] bytes) {
            return bytes;
        }
        if (body instanceof InputStream in) {
            return in.readAllBytes();
        }
        if (body instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        return exchange.getMessage().getBody(byte[].class);
    }

    /** The shared 202 response: transfer id plus the status (and for exports file) URLs. */
    static void respondAccepted(Exchange exchange, String urlPath, String transferId,
            boolean withFileUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferId", transferId);
        body.put("statusUrl", urlPath + "/" + transferId);
        if (withFileUrl) {
            body.put("fileUrl", urlPath + "/" + transferId + "/file");
        }
        exchange.getMessage().removeHeaders("*", Exchange.CONTENT_TYPE);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 202);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        try {
            exchange.getMessage().setBody(MAPPER.writeValueAsString(body));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
}
