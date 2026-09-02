package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.nio.file.Path;

/**
 * Spends a reviewed import batch (docs/csv-import.md decision 5): the confirm leg of an
 * {@code import.review: required} route.
 *
 * <p>The token is single-shot by a conditional claim in the store, taken <em>before</em> the run,
 * so a double click, a replayed form or a back-button re-post loses the race and is told to
 * upload again rather than importing twice. The claim is the batch row itself — the same row the
 * write is about — which is why this leg deliberately does not also carry an idempotency key.
 *
 * <p>What the route declares (the per-row statement, the failure policy) comes from here; the
 * read spec comes from the parked batch, because the locale is resolved per request and this is
 * a different request from the one that parsed the file.
 */
public final class ImportCommitProcessor implements Step {

    private static final TqlErrorCode NO_SERVICE = new TqlErrorCode(TqlDomain.LD, 2821);
    private static final TqlErrorCode TOKEN_MISMATCH = new TqlErrorCode(TqlDomain.LD, 2867);

    private final String routeId;
    private final String urlPath;
    private final String appName;
    private final String format;
    private final FileReadSpec readSpec;
    private final Path rowSqlFile;
    private final String onError;

    public ImportCommitProcessor(String routeId, String urlPath, String appName, String format,
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
        FileTransferService transfers = exchange.beans().lookup(
                TesseraqlProperties.FILE_TRANSFER_BEAN,
                FileTransferService.class);
        if (transfers == null) {
            throw new TqlException(NO_SERVICE, "File transfer service is not configured");
        }
        String batchId = exchange.request().param("batchId");
        // The upstream contract puts the token in the path AND in a hidden field, and says the
        // pair must match. Reading one and ignoring the other would make the hidden field
        // decorative; a form that disagrees with its own action is a bug worth naming.
        String posted = exchange.request().param("token");
        if (posted != null && !posted.isBlank() && !posted.equals(batchId)) {
            String mismatch = "The confirm form's token does not match the address it posted to";
            throw TqlException.builder(TOKEN_MISMATCH)
                    .message(mismatch)
                    .details(java.util.Map.of("message", mismatch))
                    .build();
        }
        String transferId = transfers.commitImport(batchId,
                FileImportProcessor.subject(exchange),
                // No contract built here on purpose: the commit is held to the one parked with
                // the batch, so resolving the catalogs again would be a query per catalog whose
                // answer is thrown away — and the once-per-import promise would be false on the
                // very leg that repeats the parse.
                new FileTransferService.ImportRequest(routeId, appName, format, readSpec,
                        rowSqlFile, onError, null));
        FileImportProcessor.respondAccepted(exchange, urlPath, transferId, false);
    }
}
