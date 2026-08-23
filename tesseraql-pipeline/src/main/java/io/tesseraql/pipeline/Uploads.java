package io.tesseraql.pipeline;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The multipart upload-part selection three surfaces carried by copy — the file importer, the
 * attachment upload, and the deploy endpoint, whose comments already cross-referenced each
 * other (docs/duplication-consolidation.md, campaign 4): when the request is
 * {@code multipart/form-data}, the part named {@code file} is preferred and the first part is
 * the fallback. What an <em>absent</em> part means stays with each caller — the importer and
 * the attachment upload fall back to the raw body, the deploy endpoint refuses an empty
 * deploy — so absence comes back as an empty {@link Optional}, never as one flattened policy.
 */
public final class Uploads {

    private Uploads() {
    }

    /** Whether the request declares a {@code multipart/*} content type. */
    public static boolean isMultipart(Exchange exchange) {
        String contentType = exchange.request().header(Headers.CONTENT_TYPE);
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    /**
     * The uploaded file part of a multipart request — {@code file}-named preferred, first part
     * as the fallback — or empty when the request is not multipart or carries no part.
     */
    public static Optional<Part> filePart(Exchange exchange) {
        if (!isMultipart(exchange)) {
            return Optional.empty();
        }
        Map<String, Part> attachments = exchange.request().attachments();
        if (attachments == null || attachments.isEmpty()) {
            return Optional.empty();
        }
        Part part = attachments.get("file");
        return Optional.of(part != null ? part : attachments.values().iterator().next());
    }
}
