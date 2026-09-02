package io.tesseraql.compiler.binding;

import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.util.Map;

/**
 * What a reviewed upload hands the page that renders it (docs/csv-import.md decision 7).
 *
 * <p>The processor parses and the view renders, so the outcome travels between them the way
 * every other render input does — through the execution context, under one reserved key.
 * Deliberately the parse's own result rather than a rendered report: the locale is negotiated
 * per request and the message catalog is the view binding's, so building the sentences here
 * would mean resolving them somewhere that has neither.
 *
 * <p>It is published only by a request that actually parsed a file. The GET that renders the
 * empty upload form finds nothing here, which is exactly why that page shows a form and no
 * report.
 *
 * @param review    what the parse found
 * @param locate    where a data row of this format sits in the file, for the report's labels
 * @param commitUrl where the confirm form posts, or null when there is nothing to confirm
 */
record ImportContext(FileTransferService.ImportReview review, ImportReports.RowLocator locate,
        String commitUrl) {

    /** The execution-context key the import view reads. */
    static final String KEY = "importOutcome";

    /** Publishes this outcome for the renderer that runs next. */
    void publish(Exchange exchange) {
        context(exchange).put(KEY, this);
    }

    /** The execution context, created when this route bound none — an import binds none. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> context(Exchange exchange) {
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.class);
        if (context == null) {
            context = new java.util.HashMap<>();
            exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        }
        return context;
    }
}
