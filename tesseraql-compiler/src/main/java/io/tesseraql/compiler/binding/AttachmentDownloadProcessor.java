package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.attachment.AttachmentService;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.Optional;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Streams an attachment download (roadmap Phase 30 slice 1): loads the metadata owner-scoped to the
 * record in the path (an attachment owned by a different record reads as unknown, never leaked), then
 * streams the blob with a sanitized {@code Content-Disposition}. Authorization rides the route's
 * {@code security:}; the owner scope is enforced here.
 */
public final class AttachmentDownloadProcessor implements Processor {

    private static final TqlErrorCode NO_SERVICE = new TqlErrorCode(TqlDomain.LD, 2840);
    private static final TqlErrorCode UNKNOWN = new TqlErrorCode(TqlDomain.LD, 2844);

    private final String entity;
    private final String recordKey;
    private final String idParam;

    public AttachmentDownloadProcessor(String entity, String recordKey, String idParam) {
        this.entity = entity;
        this.recordKey = recordKey;
        this.idParam = idParam;
    }

    @Override
    public void process(Exchange exchange) {
        AttachmentService service = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.ATTACHMENT_SERVICE_BEAN,
                        AttachmentService.class);
        if (service == null) {
            throw new TqlException(NO_SERVICE, "Attachment service is not configured");
        }
        String recordId = exchange.getMessage().getHeader(recordKey, String.class);
        String attachmentId = exchange.getMessage().getHeader(idParam, String.class);
        Optional<AttachmentService.Fetched> fetched = service.fetch(attachmentId, entity, recordId);
        if (fetched.isEmpty()) {
            throw new TqlException(UNKNOWN, "Unknown attachment: " + attachmentId);
        }
        AttachmentService.Fetched f = fetched.get();
        String contentType = f.metadata().contentType() != null
                ? f.metadata().contentType()
                : "application/octet-stream";
        String filename = f.metadata().filename() != null
                ? f.metadata().filename()
                : f.metadata().id();
        exchange.getMessage().removeHeaders("*");
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, contentType);
        exchange.getMessage().setHeader("Content-Disposition",
                "attachment; filename=\"" + filename.replaceAll("[\\r\\n\"]", "_") + "\"");
        exchange.getMessage().setBody(f.content());
    }
}
