package io.tesseraql.runtime;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.Step;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Feeds one polled file into the file-import pipeline (roadmap Phase 26): the consumer's body is
 * the file content, which the {@link FileTransferService} spools off-heap and imports
 * asynchronously (the same path an HTTP upload takes), tracked as a transfer in the operations
 * console. The spool completes before this returns, so the polling consumer can then move the
 * file to its done/failed sub-directory.
 */
final class PollImportProcessor implements Step {

    private static final TqlErrorCode EMPTY_FILE = new TqlErrorCode(TqlDomain.LD, 2824);
    private static final TqlErrorCode NO_SERVICE = new TqlErrorCode(TqlDomain.LD, 2825);
    /** TQL-LD-2849: a polled file's import did not complete, so the file moves to moveFailed. */
    private static final TqlErrorCode IMPORT_FAILED = new TqlErrorCode(TqlDomain.LD, 2849);

    private static final System.Logger LOG = System
            .getLogger(PollImportProcessor.class.getName());

    private final String jobId;
    private final String appName;
    private final String format;
    private final FileReadSpec readSpec;
    private final Path rowSqlFile;
    private final String onError;
    private final io.tesseraql.opsui.PollSourceStatus status;

    /** How often the poll thread re-reads the transfer's status while it runs. */
    private static final long POLL_INTERVAL_MILLIS = 100;

    PollImportProcessor(String jobId, String appName, String format, FileReadSpec readSpec,
            Path rowSqlFile, String onError, io.tesseraql.opsui.PollSourceStatus status) {
        this.jobId = jobId;
        this.appName = appName;
        this.format = format;
        this.readSpec = readSpec;
        this.rowSqlFile = rowSqlFile;
        this.onError = onError;
        this.status = status;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        FileTransferService transfers = exchange.beans().lookup(
                TesseraqlProperties.FILE_TRANSFER_BEAN,
                FileTransferService.class);
        if (transfers == null) {
            throw new TqlException(NO_SERVICE, "File transfer service is not configured");
        }
        String fileName = exchange.getMessage().getHeader(Headers.FILE_NAME, String.class);
        try (InputStream content = exchange.getMessage().getBody(InputStream.class)) {
            if (content == null) {
                throw new TqlException(EMPTY_FILE,
                        "Polled file '" + fileName + "' had no readable content");
            }
            // startImport spools the stream off-heap before returning, so a large file never
            // materializes in memory and the consumer can safely move it afterwards.
            String transferId = transfers.startImport(new FileTransferService.ImportRequest(
                    jobId, appName, format, readSpec, rowSqlFile, onError), content);
            awaitImport(transfers, transferId, fileName);
            LOG.log(System.Logger.Level.INFO,
                    "Polled file {0} ingested for job {1} as transfer {2}",
                    fileName, jobId, transferId);
            status.imported(jobId, "'" + fileName + "' imported");
        } catch (Exception ex) {
            // The failure still moves the file per moveFailed:; the registry is what makes
            // it visible on the console (docs/poll-source-status.md).
            status.failed(jobId, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Waits for the import to resolve, and fails the exchange when it did not.
     *
     * <p>This is what makes {@code move:}/{@code moveFailed:} mean what
     * docs/connectors.md says they mean. {@code startImport} spools, records the transfer and
     * hands the work to an executor, so without this the exchange completed the instant the
     * bytes were on disk — and the consumer archived the file into {@code .done} before a single
     * row of SQL had run. A file that failed every row still landed in {@code .done}, and
     * {@code .error} could only ever collect the three synchronous failures (no service, empty
     * body, an IO error while spooling). An operator reconciling by directory, which is the
     * model the documentation describes, concluded a rejected file had been ingested.
     *
     * <p>The wait is the poll consumer's own thread, which is the right place for it: a poll job
     * processes one file at a time by construction, and the file's fate is the whole point of
     * the cycle. It also gives the loop natural backpressure.
     */
    private void awaitImport(FileTransferService transfers, String transferId, String fileName) {
        while (true) {
            FileTransferService.TransferStatus transfer = transfers.status(transferId).orElse(null);
            String status = transfer == null ? "UNKNOWN" : transfer.status();
            switch (status) {
                case "COMPLETED" -> {
                    // Under onError: skip a wholly-rejected file completes with zero rows applied
                    // and every row in errors — it used to archive to the success directory and
                    // reset the consecutive-failure streak, hiding a broken feed. Route it to the
                    // failure directory instead; a partial import (some rows applied) stays a
                    // success, matching the documented skip contract.
                    if (transfer != null && transfer.rows() == 0 && !transfer.errors().isEmpty()) {
                        throw new TqlException(IMPORT_FAILED, "Polled file '" + fileName
                                + "' imported no rows; all " + transfer.errors().size()
                                + " were rejected (transfer " + transferId
                                + "); it moves to the failure directory");
                    }
                    return;
                }
                case "FAILED", "STOPPED" -> throw new TqlException(IMPORT_FAILED,
                        "Polled file '" + fileName + "' failed to import (transfer " + transferId
                                + "); it moves to the failure directory");
                default -> sleepBriefly();
            }
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TqlException(IMPORT_FAILED, "Interrupted while awaiting the import");
        }
    }
}
