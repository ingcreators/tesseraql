package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.core.files.FileWriteSpec;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.nio.file.Path;
import java.util.Map;

/**
 * Starts an asynchronous file export (design ch. 28): the route-bound parameters feed the
 * extraction query, the response is 202 with the transfer id, status URL and file URL.
 */
public final class FileExportStartProcessor implements Step {

    private static final TqlErrorCode NO_SERVICE = new TqlErrorCode(TqlDomain.LD, 2821);

    private final String routeId;
    private final String urlPath;
    private final String appName;
    private final String format;
    private final FileWriteSpec writeSpec;
    private final FormatDeclaration locale;
    private final FormatDeclaration timezone;
    private final String filename;
    private final Path querySqlFile;
    private final String afterTiming;
    private final Path afterSqlFile;
    private final io.tesseraql.core.files.ExportRowCap rowCap;
    private final java.util.List<io.tesseraql.core.files.ExportQuery> queries;
    private final java.util.Set<String> httpSources;

    private final java.util.List<EnrichProcessor> enrichments;
    /** The application home and default locale the browser's card renders against. */
    private final Path appHome;
    private final String defaultLocaleTag;
    /** The route's {@code emit:} topics, announced when the {@code after:} statement commits. */
    private final java.util.List<String> emit;
    /** The route's {@code invalidates:} tables, dropped when the {@code after:} statement commits. */
    private final java.util.List<String> invalidates;

    public FileExportStartProcessor(String routeId, String urlPath, String appName, String format,
            FileWriteSpec writeSpec, FormatDeclaration locale,
            FormatDeclaration timezone, String filename, Path querySqlFile,
            String afterTiming, Path afterSqlFile,
            io.tesseraql.core.files.ExportRowCap rowCap,
            java.util.List<io.tesseraql.core.files.ExportQuery> queries,
            java.util.Set<String> httpSources, java.util.List<EnrichProcessor> enrichments,
            Path appHome, String defaultLocaleTag, java.util.List<String> emit,
            java.util.List<String> invalidates) {
        this.enrichments = java.util.List.copyOf(enrichments);
        this.appHome = appHome;
        this.defaultLocaleTag = defaultLocaleTag;
        this.emit = java.util.List.copyOf(emit);
        this.invalidates = java.util.List.copyOf(invalidates);
        this.routeId = routeId;
        this.urlPath = urlPath;
        this.appName = appName;
        this.format = format;
        this.writeSpec = writeSpec;
        this.locale = locale;
        this.timezone = timezone;
        this.filename = filename;
        this.querySqlFile = querySqlFile;
        this.afterTiming = afterTiming;
        this.afterSqlFile = afterSqlFile;
        this.rowCap = rowCap;
        this.queries = java.util.List.copyOf(queries);
        this.httpSources = java.util.Set.copyOf(httpSources);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        FileTransferService transfers = exchange.beans().lookup(
                TesseraqlProperties.FILE_TRANSFER_BEAN,
                FileTransferService.class);
        if (transfers == null) {
            throw new TqlException(NO_SERVICE, "File transfer service is not configured");
        }
        Map<String, Object> params = exchange.getProperty(
                TesseraqlProperties.SQL_PARAMS, Map.of(), Map.class);
        // Judged before startExport: a refusal here leaves no transfer row, no execution row
        // and no spool — the caller gets a 400 where a 202-then-FAILED used to hide the reason.
        FileWriteSpec formatted = writeSpec.withFormatting(
                RequestFormats.locale(exchange, locale),
                RequestFormats.timezone(exchange, timezone));
        // Each named source's params: resolve here, on the request, because the extraction
        // runs off it (docs/export-pipeline.md decision 2).
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT,
                Map.of(), Map.class);
        java.util.List<io.tesseraql.core.files.ExportQuery> resolvedQueries = queries.stream()
                .map(query -> query.resolved(context)).toList();
        // The download name is fixed here, before the transfer row is written, so the status
        // JSON, the HEAD, the GET and the download all say the name this request asked for and
        // nothing resolves it a second time (docs/route-filename-placeholders.md decision 1).
        String resolvedFilename = io.tesseraql.core.files.FilenamePlaceholders.resolve(filename,
                new io.tesseraql.core.expr.EvaluationContext(context));
        String transferId = transfers.startExport(new FileTransferService.ExportRequest(
                routeId, appName, format, formatted,
                resolvedFilename, querySqlFile, Map.copyOf(params), afterTiming, afterSqlFile,
                rowCap, resolvedQueries, ExportSources.values(exchange, httpSources),
                ExportEnrichment.enricher(exchange, enrichments),
                ExportEnrichment.window(enrichments))
                // The route's topics and tables travel with the request because the run
                // outlives it: an extraction-timed follow-up announces itself when its
                // transaction commits on the background thread, not when this response goes
                // out (docs/list-export.md, the after: commit). The download-timed one is
                // announced from the transfer row, which records them at start.
                .announcing(emit, TransferTopics.tenant(exchange))
                .invalidating(invalidates)
                // And whose it is (docs/job-inbox.md decision 1): the owner a surface lists
                // by, read here because the run has no principal to read it from later.
                .by(TransferOwner.of(exchange))
                .on(TransferPools.of(exchange)));
        // An htmx kick-off gets the running card, a browser's form post lands on the transfer's
        // page (docs/list-export.md decision 4); scripted callers keep the JSON 202 this recipe
        // has always answered.
        TransferKickoff.respondExportStarted(exchange, urlPath, transferId, appHome,
                defaultLocaleTag, transfers);
    }
}
