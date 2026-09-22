package io.tesseraql.compiler.binding;

import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.security.Principal;

/**
 * The tenant a transfer's signal is scoped to: an import's completion (docs/csv-import.md
 * decision 6), an export's {@code after:} commit (docs/list-export.md).
 *
 * <p>Read here, on the request, and carried with the run — because the run outlives the request.
 * A live-view signal is scoped to the emitting principal's tenant the way
 * {@link TopicEmitProcessor} scopes a command's, and the background thread that finishes the
 * transfer has no principal to read it from. A {@code download}-timed follow-up reads it from
 * the transfer row the export recorded it on, for the same reason: the fetch that runs it is a
 * later request, and may be the operations console's, with no route behind it.
 */
final class TransferTopics {

    private TransferTopics() {
    }

    /** The requesting principal's tenant, or null when the request has none. */
    static String tenant(Exchange exchange) {
        return exchange.getProperty(TesseraqlProperties.PRINCIPAL) instanceof Principal principal
                ? principal.tenantId()
                : null;
    }
}
