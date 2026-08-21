package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.attachment.AttachmentService;
import io.tesseraql.core.attachment.AttachmentStore;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.Step;
import io.tesseraql.security.Principal;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handles an attachment upload (roadmap Phase 30 slice 1): reads the multipart file part (or raw
 * body), enforces the content-type allow-list, then hands the stream to the {@link AttachmentService}
 * which spools it off-heap into the blob store and records its metadata against the owning record.
 * Responds {@code 201} with the stored attachment's id and checksum.
 */
public final class AttachmentUploadProcessor implements Step {

    private static final TqlErrorCode NO_SERVICE = new TqlErrorCode(TqlDomain.LD, 2840);
    private static final TqlErrorCode EMPTY = new TqlErrorCode(TqlDomain.LD, 2841);
    private static final TqlErrorCode UNSUPPORTED_TYPE = new TqlErrorCode(TqlDomain.LD, 2842);

    private final String entity;
    private final String recordKey;
    private final String bucket;
    private final long maxBytes;
    private final List<String> contentTypes;

    public AttachmentUploadProcessor(String entity, String recordKey, String bucket, long maxBytes,
            List<String> contentTypes) {
        this.entity = entity;
        this.recordKey = recordKey;
        this.bucket = bucket;
        this.maxBytes = maxBytes;
        this.contentTypes = List.copyOf(contentTypes);
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        AttachmentService service = exchange.beans().lookup(
                TesseraqlProperties.ATTACHMENT_SERVICE_BEAN,
                AttachmentService.class);
        if (service == null) {
            throw new TqlException(NO_SERVICE, "Attachment service is not configured");
        }
        String recordId = exchange.getMessage().getHeader(recordKey, String.class);
        UploadPart part = resolvePart(exchange);
        try (InputStream content = part.content()) {
            if (content == null) {
                throw new TqlException(EMPTY,
                        "attachment upload expects a file part or request body");
            }
            String contentType = part.contentType() != null
                    ? part.contentType()
                    : "application/octet-stream";
            if (!allows(contentType)) {
                throw new TqlException(UNSUPPORTED_TYPE,
                        "content type '" + contentType + "' is not allowed for this attachment");
            }
            Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL,
                    Principal.class);
            String createdBy = principal == null
                    ? null
                    : (principal.loginId() != null ? principal.loginId() : principal.subject());
            AttachmentStore.Attachment stored = service.store(
                    new AttachmentService.StoreRequest(bucket, entity, recordId, part.filename(),
                            contentType, createdBy, maxBytes),
                    content);
            respond(exchange, stored);
        }
    }

    private boolean allows(String contentType) {
        if (contentTypes.isEmpty()) {
            return true;
        }
        String candidate = baseType(contentType);
        return contentTypes.stream().anyMatch(t -> baseType(t).equalsIgnoreCase(candidate));
    }

    private static String baseType(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim();
    }

    private static void respond(Exchange exchange, AttachmentStore.Attachment a) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", a.id());
        body.put("filename", a.filename());
        body.put("contentType", a.contentType());
        body.put("byteSize", a.byteSize());
        body.put("checksum", a.checksum());
        exchange.getMessage().removeHeaders("*", Headers.CONTENT_TYPE);
        exchange.getMessage().setHeader(Headers.HTTP_RESPONSE_CODE, 201);
        // 201 identifies what it created (docs/vocabulary-cleanup.md slice 3): the
        // attachment's own subtree URL under the upload path.
        String uri = exchange.getMessage().getHeader(Headers.HTTP_URI, String.class);
        if (uri != null && !uri.isBlank()) {
            exchange.getMessage().setHeader("Location", uri + "/" + a.id());
        }
        exchange.getMessage().setHeader(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getMessage().setBody(FileImportProcessor.MAPPER.writeValueAsString(body));
    }

    /** The uploaded part: the first multipart file part (preferring {@code file}), or the raw body. */
    private static UploadPart resolvePart(Exchange exchange) throws Exception {
        String contentType = exchange.getMessage().getHeader(Headers.CONTENT_TYPE, String.class);
        if (contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            java.util.Map<String, jakarta.activation.DataHandler> attachments = exchange
                    .getMessage().attachments();
            if (attachments != null && !attachments.isEmpty()) {
                jakarta.activation.DataHandler handler = attachments.get("file");
                if (handler == null) {
                    handler = attachments.values().iterator().next();
                }
                return new UploadPart(handler.getName(), handler.getContentType(),
                        handler.getInputStream());
            }
        }
        Object raw = exchange.getMessage().getBody();
        InputStream in;
        if (raw instanceof InputStream stream) {
            in = stream;
        } else if (raw instanceof byte[] bytes) {
            in = new ByteArrayInputStream(bytes);
        } else {
            byte[] converted = exchange.getMessage().getBody(byte[].class);
            in = converted == null ? null : new ByteArrayInputStream(converted);
        }
        return new UploadPart(null, contentType, in);
    }

    private record UploadPart(String filename, String contentType, InputStream content) {
    }
}
