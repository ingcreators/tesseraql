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

    public FileExportStartProcessor(String routeId, String urlPath, String appName, String format,
            FileWriteSpec writeSpec, FormatDeclaration locale,
            FormatDeclaration timezone, String filename, Path querySqlFile,
            String afterTiming, Path afterSqlFile,
            io.tesseraql.core.files.ExportRowCap rowCap,
            java.util.List<io.tesseraql.core.files.ExportQuery> queries,
            java.util.Set<String> httpSources, java.util.List<EnrichProcessor> enrichments) {
        this.enrichments = java.util.List.copyOf(enrichments);
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
        String transferId = transfers.startExport(new FileTransferService.ExportRequest(
                routeId, appName, format, formatted,
                filename, querySqlFile, Map.copyOf(params), afterTiming, afterSqlFile,
                rowCap, queries, ExportSources.values(exchange, httpSources),
                ExportEnrichment.enricher(exchange, enrichments),
                ExportEnrichment.window(enrichments)));
        FileImportProcessor.respondAccepted(exchange, urlPath, transferId, true);
    }
}
