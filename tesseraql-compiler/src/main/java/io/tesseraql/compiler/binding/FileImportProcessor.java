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
    private final String localeDeclaration;
    private final Path rowSqlFile;
    private final String onError;

    public FileImportProcessor(String routeId, String urlPath, String appName, String format,
            FileReadSpec readSpec, String localeDeclaration, Path rowSqlFile, String onError) {
        this.routeId = routeId;
        this.urlPath = urlPath;
        this.appName = appName;
        this.format = format;
        this.readSpec = readSpec;
        this.localeDeclaration = localeDeclaration;
        this.rowSqlFile = rowSqlFile;
        this.onError = onError;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        FileTransferService transfers = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.FILE_TRANSFER_BEAN,
                        FileTransferService.class);
        if (transfers == null) {
            throw new TqlException(NO_SERVICE, "File transfer service is not configured");
        }
        try (InputStream content = body(exchange)) {
            if (content == null) {
                throw new TqlException(EMPTY_BODY,
                        "file-import expects the uploaded file as the request body");
            }
            // The service spools the stream off-heap before returning; large uploads never
            // materialize in memory here (an empty upload fails with the same 400).
            String transferId = transfers.startImport(new FileTransferService.ImportRequest(
                    routeId, appName, format,
                    readSpec.withLocale(FormatSources.resolve(exchange, localeDeclaration)),
                    rowSqlFile, onError), content);
            respondAccepted(exchange, urlPath, transferId, false);
        }
    }

    /**
     * The uploaded file content as a stream: for {@code multipart/form-data} the first file part
     * (a part named {@code file} preferred, streamed from Vert.x's on-disk upload), otherwise the
     * raw request body.
     */
    private static InputStream body(Exchange exchange) throws Exception {
        String contentType = exchange.getMessage().getHeader(Exchange.CONTENT_TYPE, String.class);
        if (contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/")) {
            org.apache.camel.attachment.AttachmentMessage attachments = exchange
                    .getMessage(org.apache.camel.attachment.AttachmentMessage.class);
            if (attachments != null && attachments.hasAttachments()) {
                jakarta.activation.DataHandler part = attachments.getAttachment("file");
                if (part == null) {
                    part = attachments.getAttachments().values().iterator().next();
                }
                return part.getInputStream();
            }
        }
        Object body = exchange.getMessage().getBody();
        if (body instanceof InputStream in) {
            return in;
        }
        if (body instanceof byte[] bytes) {
            return new java.io.ByteArrayInputStream(bytes);
        }
        if (body instanceof String text) {
            return new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        }
        byte[] converted = exchange.getMessage().getBody(byte[].class);
        return converted == null ? null : new java.io.ByteArrayInputStream(converted);
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

    static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
}
