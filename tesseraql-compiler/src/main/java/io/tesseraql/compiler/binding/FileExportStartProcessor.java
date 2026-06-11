package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.core.files.FileWriteSpec;
import java.nio.file.Path;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Starts an asynchronous file export (design ch. 28): the route-bound parameters feed the
 * extraction query, the response is 202 with the transfer id, status URL and file URL.
 */
public final class FileExportStartProcessor implements Processor {

    private static final TqlErrorCode NO_SERVICE = new TqlErrorCode(TqlDomain.LD, 2821);

    private final String routeId;
    private final String urlPath;
    private final String appName;
    private final String format;
    private final FileWriteSpec writeSpec;
    private final String localeDeclaration;
    private final String timezoneDeclaration;
    private final String filename;
    private final Path querySqlFile;
    private final String afterTiming;
    private final Path afterSqlFile;

    public FileExportStartProcessor(String routeId, String urlPath, String appName, String format,
            FileWriteSpec writeSpec, String localeDeclaration, String timezoneDeclaration,
            String filename, Path querySqlFile, String afterTiming, Path afterSqlFile) {
        this.routeId = routeId;
        this.urlPath = urlPath;
        this.appName = appName;
        this.format = format;
        this.writeSpec = writeSpec;
        this.localeDeclaration = localeDeclaration;
        this.timezoneDeclaration = timezoneDeclaration;
        this.filename = filename;
        this.querySqlFile = querySqlFile;
        this.afterTiming = afterTiming;
        this.afterSqlFile = afterSqlFile;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        FileTransferService transfers = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.FILE_TRANSFER_BEAN,
                        FileTransferService.class);
        if (transfers == null) {
            throw new TqlException(NO_SERVICE, "File transfer service is not configured");
        }
        Map<String, Object> params = exchange.getProperty(
                TesseraqlProperties.SQL_PARAMS, Map.of(), Map.class);
        String transferId = transfers.startExport(new FileTransferService.ExportRequest(
                routeId, appName, format,
                writeSpec.withFormatting(
                        FormatSources.resolve(exchange, localeDeclaration),
                        FormatSources.resolve(exchange, timezoneDeclaration)),
                filename, querySqlFile, Map.copyOf(params), afterTiming, afterSqlFile));
        FileImportProcessor.respondAccepted(exchange, urlPath, transferId, true);
    }
}
