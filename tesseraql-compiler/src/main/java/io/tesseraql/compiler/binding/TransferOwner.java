package io.tesseraql.compiler.binding;

import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.security.Principal;

/**
 * Who starts a transfer (docs/job-inbox.md decision 1): the requesting principal's stable
 * subject, or null when the request has none.
 *
 * <p>Read here, on the request, and carried on the request record — because the run outlives
 * the request, and because the reviewed commit rebuilds its request as a frozen copy that has
 * to carry the owner the way it carries the topics and the pool. Null, never the empty string:
 * {@link FileImportProcessor#subject} answers {@code ""} for the batch, whose subject is an
 * equality key the commit compares; the transfer's is an owner a surface lists by, and a public
 * route's export belongs to nobody rather than to {@code ""}.
 */
final class TransferOwner {

    private TransferOwner() {
    }

    /** The requesting principal's subject, or null when there is no principal or no subject. */
    static String of(Exchange exchange) {
        if (!(exchange.getProperty(TesseraqlProperties.PRINCIPAL) instanceof Principal principal)) {
            return null;
        }
        String subject = principal.subject();
        return subject == null || subject.isBlank() ? null : subject;
    }
}
