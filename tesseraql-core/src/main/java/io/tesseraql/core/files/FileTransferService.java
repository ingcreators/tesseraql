package io.tesseraql.core.files;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generic asynchronous file transfers between uploaded/downloaded tabular files and the database
 * (design ch. 28): the {@code file-import} recipe parses an uploaded file and applies a 2-way SQL
 * statement per row, the {@code file-export} recipe streams a query into a generated file, both
 * tracked as batch executions (visible and app-scoped in the operations console). Exports can run
 * a follow-up statement either with the extraction (same transaction) or on first download.
 */
public interface FileTransferService {

    /** Import behavior on a failing row. */
    String ON_ERROR_ROLLBACK = "rollback";
    String ON_ERROR_SKIP = "skip";

    /** Export follow-up timing. */
    String AFTER_EXTRACT = "extract";
    String AFTER_DOWNLOAD = "download";

    /** An upload to apply: the row statement runs once per parsed row. */
    record ImportRequest(String routeId, String appName, String format, FileReadSpec readSpec,
            Path rowSqlFile, String onError) {
    }

    /** An export to generate: the query streams into the file; {@code afterSqlFile} optional. */
    record ExportRequest(String routeId, String appName, String format, FileWriteSpec writeSpec,
            String filename, Path querySqlFile, Map<String, Object> params,
            String afterTiming, Path afterSqlFile) {
    }

    /** One rejected import row. */
    record RowError(long row, String message) {
    }

    /** The transfer state: the execution status plus transfer-specific detail. */
    record TransferStatus(String transferId, String routeId, String direction, String status,
            long rows, List<RowError> errors, String filename, boolean downloaded) {
    }

    /** A ready file: stream plus response metadata. */
    record Download(String filename, String contentType, InputStream content) {
    }

    /**
     * Starts an asynchronous import of the uploaded content; returns the transfer id. The stream
     * is consumed (spooled off-heap) before this returns, so arbitrarily large uploads never
     * materialize in memory.
     */
    String startImport(ImportRequest request, java.io.InputStream content);

    /** Starts an asynchronous export; returns the transfer id. */
    String startExport(ExportRequest request);

    /** The transfer state, or empty when the id is unknown. */
    Optional<TransferStatus> status(String transferId);

    /**
     * Opens the generated file once the export completed (empty when unknown or not ready). The
     * first successful download triggers the {@code download}-timed follow-up statement.
     */
    Optional<Download> download(String transferId);
}
