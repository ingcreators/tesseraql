package io.tesseraql.compiler.binding;

import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.security.Principal;

/**
 * The tenant an import's completion signal is scoped to (docs/csv-import.md decision 6).
 *
 * <p>Read here, on the request, and carried with the run — because the run outlives the request.
 * A live-view signal is scoped to the emitting principal's tenant the way
 * {@link TopicEmitProcessor} scopes a command's, and the background thread that finishes the
 * import has no principal to read it from.
 */
final class ImportTopics {

    private ImportTopics() {
    }

    /** The requesting principal's tenant, or null when the request has none. */
    static String tenant(Exchange exchange) {
        return exchange.getProperty(TesseraqlProperties.PRINCIPAL) instanceof Principal principal
                ? principal.tenantId()
                : null;
    }
}
