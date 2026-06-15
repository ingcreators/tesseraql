package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.attachment.AttachmentService;
import io.tesseraql.core.attachment.AttachmentStore;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Lists the attachments of one owning record as JSON (roadmap Phase 30 slice 1). Scoped to the record
 * in the path; the blob bytes are never read, only the metadata rows.
 */
public final class AttachmentListProcessor implements Processor {

    private static final TqlErrorCode NO_SERVICE = new TqlErrorCode(TqlDomain.LD, 2840);

    private final String entity;
    private final String recordKey;

    public AttachmentListProcessor(String entity, String recordKey) {
        this.entity = entity;
        this.recordKey = recordKey;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        AttachmentService service = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.ATTACHMENT_SERVICE_BEAN,
                        AttachmentService.class);
        if (service == null) {
            throw new TqlException(NO_SERVICE, "Attachment service is not configured");
        }
        String recordId = exchange.getMessage().getHeader(recordKey, String.class);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AttachmentStore.Attachment a : service.list(entity, recordId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", a.id());
            item.put("filename", a.filename());
            item.put("contentType", a.contentType());
            item.put("byteSize", a.byteSize());
            item.put("checksum", a.checksum());
            item.put("scanStatus", a.scanStatus());
            item.put("createdBy", a.createdBy());
            item.put("createdAt", a.createdAt() == null ? null : a.createdAt().toString());
            items.add(item);
        }
        exchange.getMessage().removeHeaders("*", Exchange.CONTENT_TYPE);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(FileImportProcessor.MAPPER.writeValueAsString(items));
    }
}
