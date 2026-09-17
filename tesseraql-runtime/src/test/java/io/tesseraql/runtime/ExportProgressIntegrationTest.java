package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.files.ColumnMapping;
import io.tesseraql.core.files.ExportModel;
import io.tesseraql.core.files.FileCodec;
import io.tesseraql.core.files.FileCodecs;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.core.files.FileWriteSpec;
import io.tesseraql.core.files.RowHandler;
import io.tesseraql.core.jdbc.DriverManagerDataSource;
import io.tesseraql.core.spool.FileTempStore;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.operations.batch.JobStatus;
import io.tesseraql.operations.files.CsvFileCodec;
import io.tesseraql.operations.files.JdbcFileTransferService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * An export says how far it got, and stops when told (docs/audit-low-leads.md slice 15). The
 * row source under the codec publishes its counter on the import's two-second tick through a
 * connection of its own and reads the cancel flag on the same tick; the runs here are paced by
 * the codec, forty milliseconds a row, so the tick lands mid-run whatever the database does.
 *
 * <p>Before this every export read {@code 0 rows} for its whole life and recorded 0 when it
 * failed, an own-route Cancel set a flag nothing read, an {@code Error} out of the codec left
 * the writer's spool behind, and a runtime closing under a running export left it RUNNING for
 * the reaper.
 */
@Testcontainers
class ExportProgressIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static DataSource dataSource;
    private static JobRepository jobs;
    private static Path sqlDir;

    @BeforeAll
    static void prepare() throws Exception {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        jobs = new JobRepository(dataSource);
        jobs.ensureSchema();
        sqlDir = Files.createTempDirectory("export-progress-sql");
        Files.writeString(sqlDir.resolve("rows.sql"),
                "select g as id, 'row-' || g as name from generate_series(1, 200) g\n");
    }

    /** A running export publishes the rows it reached, and a failed one keeps them. */
    @Test
    void aRunningExportSaysHowFarItGotAndAFailedOneKeepsIt() throws Exception {
        Path spools = Files.createTempDirectory("export-progress-spool");
        JdbcFileTransferService transfers = service(new Paced("paced", 40, 80, false), spools);
        try {
            String id = start(transfers, "items.paced", "paced");
            long seenRunning = 0;
            JobExecution execution;
            while (true) {
                execution = jobs.findExecution(id).orElseThrow();
                if (execution.status() != JobStatus.RUNNING) {
                    break;
                }
                seenRunning = Math.max(seenRunning, transfers.status(id).orElseThrow().rows());
                Thread.sleep(100);
            }
            assertThat(seenRunning).as("rows published while RUNNING").isPositive();
            assertThat(execution.status()).isEqualTo(JobStatus.FAILED);
            assertThat(execution.exitMessage()).startsWith("TQL-LD-2802")
                    .contains("gave up at row 80");
            assertThat(transfers.status(id).orElseThrow().rows())
                    .as("the rows reached when the export failed").isEqualTo(80);
        } finally {
            transfers.close();
        }
    }

    /** Cancel stops the run at a row boundary: STOPPED, no file, no spool left behind. */
    @Test
    void aCancelledExportStopsAtARowBoundaryAndLeavesNoSpool() throws Exception {
        Path spools = Files.createTempDirectory("export-progress-spool");
        JdbcFileTransferService transfers = service(new Paced("slow", 40, 0, false), spools);
        try {
            String id = start(transfers, "items.slow", "slow");
            Thread.sleep(300);
            assertThat(jobs.findExecution(id).orElseThrow().status()).isEqualTo(JobStatus.RUNNING);
            assertThat(transfers.cancel(id)).as("the flag is set on a RUNNING export").isTrue();

            JobExecution execution = awaitTerminal(id);
            assertThat(execution.status()).as("the run's answer: %s", execution.exitMessage())
                    .isEqualTo(JobStatus.STOPPED);
            assertThat(execution.exitMessage()).isEqualTo("Export cancelled; nothing was written");
            FileTransferService.TransferStatus status = transfers.status(id).orElseThrow();
            assertThat(status.rows()).as("rows reached at the stop").isPositive().isLessThan(200);
            assertThat(transfers.download(id)).as("no file to download").isEmpty();
            assertThat(spoolFiles(spools)).as("the partial spool is discarded").isEmpty();
        } finally {
            transfers.close();
        }
    }

    /** An {@code Error} escaping the codec still releases the writer's spool. */
    @Test
    void anErrorEscapingTheCodecLeavesNoSpoolBehind() throws Exception {
        Path spools = Files.createTempDirectory("export-progress-spool");
        JdbcFileTransferService transfers = service(new Paced("erring", 0, 3, true), spools);
        try {
            String id = start(transfers, "items.erring", "erring");
            JobExecution execution = awaitTerminal(id);
            assertThat(execution.status()).isEqualTo(JobStatus.FAILED);
            assertThat(execution.exitMessage()).contains("SyntheticError");
            assertThat(spoolFiles(spools)).as("the writer's spool is discarded").isEmpty();
        } finally {
            transfers.close();
        }
    }

    /** A service closed under a running export asks it to stop, and waits for its answer. */
    @Test
    void aCloseUnderARunningExportRecordsTheStop() throws Exception {
        Path spools = Files.createTempDirectory("export-progress-spool");
        JdbcFileTransferService transfers = service(new Paced("closing", 40, 0, false), spools);
        String id = start(transfers, "items.closing", "closing");
        Thread.sleep(300);
        assertThat(jobs.findExecution(id).orElseThrow().status()).isEqualTo(JobStatus.RUNNING);

        transfers.close();

        JobExecution execution = jobs.findExecution(id).orElseThrow();
        assertThat(execution.status()).as("after close returns: %s", execution.exitMessage())
                .isEqualTo(JobStatus.STOPPED);
        assertThat(execution.exitMessage())
                .isEqualTo("stopped: the runtime is shutting down (cooperative stop)");
        assertThat(spoolFiles(spools)).isEmpty();
    }

    private static JdbcFileTransferService service(FileCodec codec, Path spools) {
        JdbcFileTransferService transfers = new JdbcFileTransferService(jobs,
                new io.tesseraql.operations.batch.ExecutionHeartbeats(jobs,
                        java.time.Duration.ofSeconds(30)),
                new FileTempStore(spools), dataSource, FileCodecs.of(codec),
                io.tesseraql.core.expr.ExpressionFunctions.processDefault());
        transfers.ensureSchema();
        return transfers;
    }

    private static String start(JdbcFileTransferService transfers, String routeId,
            String format) {
        return transfers.startExport(new FileTransferService.ExportRequest(routeId, "app", format,
                new FileWriteSpec(List.of(ColumnMapping.of("id"), ColumnMapping.of("name")),
                        null, null, null),
                "items.csv", sqlDir.resolve("rows.sql"), Map.of(), null, null,
                io.tesseraql.core.files.ExportRowCap.unbounded(), List.of(), Map.of()));
    }

    private static JobExecution awaitTerminal(String executionId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            JobExecution execution = jobs.findExecution(executionId).orElse(null);
            if (execution != null && execution.status() != JobStatus.RUNNING) {
                return execution;
            }
            assertThat(System.currentTimeMillis()).as("the export ends").isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    private static List<Path> spoolFiles(Path spools) throws IOException {
        try (Stream<Path> files = Files.list(spools)) {
            return files.toList();
        }
    }

    /** An {@code Error} no JVM raises, thrown to prove the arm without provoking a real one. */
    private static final class SyntheticError extends Error {
        private static final long serialVersionUID = 1L;

        SyntheticError() {
            super("synthetic");
        }
    }

    /**
     * A streaming csv-shaped codec that sleeps {@code paceMillis} per row and, when
     * {@code failAt} is positive, throws on that row — a {@code RuntimeException}, or an
     * {@code Error} when asked.
     */
    private static final class Paced implements FileCodec {
        private final String format;
        private final long paceMillis;
        private final int failAt;
        private final boolean asError;
        private final CsvFileCodec csv = new CsvFileCodec();

        Paced(String format, long paceMillis, int failAt, boolean asError) {
            this.format = format;
            this.paceMillis = paceMillis;
            this.failAt = failAt;
            this.asError = asError;
        }

        @Override
        public String format() {
            return format;
        }

        @Override
        public String contentType() {
            return csv.contentType();
        }

        @Override
        public String extension() {
            return csv.extension();
        }

        @Override
        public boolean streams(FileWriteSpec spec) {
            return true;
        }

        @Override
        public void read(InputStream in, FileReadSpec spec, RowHandler handler) throws Exception {
            csv.read(in, spec, handler);
        }

        @Override
        public void write(OutputStream out, FileWriteSpec spec, ExportModel model)
                throws IOException {
            Iterator<Map<String, Object>> rows = model.rows();
            int n = 0;
            while (rows.hasNext()) {
                Map<String, Object> row = rows.next();
                n++;
                out.write((row.get("id") + "," + row.get("name") + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                if (failAt > 0 && n == failAt) {
                    if (asError) {
                        throw new SyntheticError();
                    }
                    throw new IllegalStateException("the codec gave up at row " + n);
                }
                if (paceMillis > 0) {
                    try {
                        Thread.sleep(paceMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }
}
