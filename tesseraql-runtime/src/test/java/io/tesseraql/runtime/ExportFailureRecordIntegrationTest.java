package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.operations.batch.JobStatus;
import io.tesseraql.operations.files.CsvFileCodec;
import io.tesseraql.operations.files.JdbcFileTransferService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What a failed export says, on the arms the transfer service owns (docs/export-hygiene.md P3):
 * the reason is recorded with its code on the async and job arms as it always was on the route,
 * within its column, and the failure is logged with its stack. Before this, the async arm
 * recorded the raw exception text with no code, a reason over 2,000 characters left the transfer
 * execution RUNNING for the reaper to mis-diagnose, a database error at the start of the
 * extraction or in the follow-up statement recorded the driver's text, and no stack was logged
 * for any failure.
 */
@Testcontainers
class ExportFailureRecordIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static DataSource dataSource;
    private static JobRepository jobs;
    private static Path sqlDir;

    @BeforeAll
    static void prepare() throws Exception {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("create table failing_source (id int primary key, name text)");
            statement.execute(
                    "insert into failing_source (id, name) values (1, 'alpha'), (2, 'beta')");
        }
        jobs = new JobRepository(dataSource);
        jobs.ensureSchema();
        sqlDir = Files.createTempDirectory("export-failure-sql");
        Files.writeString(sqlDir.resolve("all.sql"),
                "select id, name from failing_source order by id\n");
        Files.writeString(sqlDir.resolve("divide.sql"), "select 1 / 0 as id, 'x' as name\n");
        Files.writeString(sqlDir.resolve("after-bad.sql"), "update no_such_table set x = 1\n");
    }

    private static JdbcFileTransferService service(FileCodec codec) throws IOException {
        JdbcFileTransferService transfers = new JdbcFileTransferService(jobs,
                new io.tesseraql.operations.batch.ExecutionHeartbeats(jobs,
                        java.time.Duration.ofSeconds(30)),
                new FileTempStore(Files.createTempDirectory("export-failure-spool")), dataSource,
                FileCodecs.of(codec), io.tesseraql.core.expr.ExpressionFunctions.processDefault());
        transfers.ensureSchema();
        return transfers;
    }

    private static FileWriteSpec spec() {
        return new FileWriteSpec(List.of(ColumnMapping.of("id"), ColumnMapping.of("name")), null,
                null, null);
    }

    /** A codec failure on the async arm records the document code, the file, and logs the stack. */
    @Test
    void aFailedAsyncExportRecordsItsCodeAndLogsItsStack() throws Exception {
        JdbcFileTransferService transfers = service(new Broken("broken",
                () -> new IllegalStateException("the codec broke")));
        JobExecution execution;
        String log;
        try {
            String[] id = new String[1];
            log = captureStderr(() -> {
                id[0] = transfers.startExport(new FileTransferService.ExportRequest(
                        "items.broken", "app", "broken", spec(), "items.csv",
                        sqlDir.resolve("all.sql"), Map.of(), null, null,
                        io.tesseraql.core.files.ExportRowCap.unbounded(), List.of(), Map.of()));
                awaitTerminal(id[0]);
            });
            execution = jobs.findExecution(id[0]).orElseThrow();
        } finally {
            transfers.close();
        }
        assertThat(execution.status()).isEqualTo(JobStatus.FAILED);
        assertThat(execution.exitMessage())
                .startsWith("TQL-LD-2802: Writing the broken document failed after the query ran:"
                        + " the codec broke")
                .contains("[items.csv]");
        assertThat(log).as("the WARN line").contains("File export " + execution.id() + " failed");
        assertThat(log).as("the stack, not only the message").contains("\tat ")
                .contains("ExportFailureRecordIntegrationTest");
    }

    /** The job arm records the same code, within the column: a long reason is cut, not lost. */
    @Test
    void aFailedJobArmExportRecordsItsCodeWithinTheColumn() throws Exception {
        String reason = "x".repeat(2_100);
        JdbcFileTransferService transfers = service(new Broken("verbose",
                () -> new IllegalStateException(reason)));
        try {
            assertThatThrownBy(() -> transfers.exportInline(
                    new FileTransferService.InlineExport("items.verbose", "app", "verbose", spec(),
                            "items.csv", sql("select id, name from failing_source order by id"),
                            null, io.tesseraql.core.files.ExportRowCap.unbounded(), Map.of()),
                    dataSource))
                    .hasMessageContaining("TQL-LD-2810").hasMessageContaining("TQL-LD-2802");
            JobExecution execution = jobs.listExecutions(50).stream()
                    .filter(e -> "items.verbose".equals(e.jobId())).findFirst().orElseThrow();
            assertThat(execution.status()).as("recorded, not left for the reaper")
                    .isEqualTo(JobStatus.FAILED);
            assertThat(execution.exitMessage()).startsWith("TQL-LD-2802: Writing the verbose")
                    .hasSize(2_000);
        } finally {
            transfers.close();
        }
    }

    /** A step's failure is recorded within its column too. */
    @Test
    void aStepFailureIsRecordedWithinItsColumn() {
        String executionId = jobs.startExecution("long.step", "app", "manual", null);
        String stepId = jobs.startStep(executionId, "s1");
        jobs.failStep(stepId, "y".repeat(2_100));
        assertThat(jobs.findSteps(executionId)).singleElement()
                .extracting(step -> step.errorMessage()).asString().hasSize(2_000);
        jobs.failExecution(executionId, "z".repeat(2_100));
        assertThat(jobs.findExecution(executionId).orElseThrow().exitMessage()).hasSize(2_000);
    }

    /** A database error at the start of the extraction is the export's failure, coded. */
    @Test
    void aDatabaseErrorAtTheStartOfTheExtractionIsCoded() throws Exception {
        JdbcFileTransferService transfers = service(new CsvFileCodec());
        try {
            String id = transfers.startExport(new FileTransferService.ExportRequest(
                    "items.divide", "app", "csv", spec(), "items.csv", sqlDir.resolve("divide.sql"),
                    Map.of(), null, null, io.tesseraql.core.files.ExportRowCap.unbounded(),
                    List.of(), Map.of()));
            JobExecution execution = awaitTerminal(id);
            assertThat(execution.status()).isEqualTo(JobStatus.FAILED);
            assertThat(execution.exitMessage()).startsWith("TQL-LD-2810: Export query failed:")
                    .contains("division by zero");
        } finally {
            transfers.close();
        }
    }

    /** A follow-up statement that fails is the export's failure, coded. */
    @Test
    void aFailingFollowUpStatementIsCoded() throws Exception {
        JdbcFileTransferService transfers = service(new CsvFileCodec());
        try {
            String id = transfers.startExport(new FileTransferService.ExportRequest(
                    "items.afterBad", "app", "csv", spec(), "items.csv", sqlDir.resolve("all.sql"),
                    Map.of(), "extract", sqlDir.resolve("after-bad.sql"),
                    io.tesseraql.core.files.ExportRowCap.unbounded(), List.of(), Map.of()));
            JobExecution execution = awaitTerminal(id);
            assertThat(execution.status()).isEqualTo(JobStatus.FAILED);
            assertThat(execution.exitMessage())
                    .startsWith("TQL-LD-2810: Export follow-up statement failed:")
                    .contains("no_such_table");
        } finally {
            transfers.close();
        }
    }

    private static JobExecution awaitTerminal(String executionId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (true) {
            JobExecution execution = jobs.findExecution(executionId).orElse(null);
            if (execution != null && execution.status() != JobStatus.RUNNING) {
                return execution;
            }
            assertThat(System.currentTimeMillis()).as("the export finishes").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private static BoundSql sql(String statement) {
        return SqlRenderer.render(Sql2WayParser.parse(statement), Map.of());
    }

    private static String captureStderr(ThrowingRunnable body) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            body.run();
            Thread.sleep(300);
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** A csv-shaped codec whose write throws what it is told to. */
    private static final class Broken implements FileCodec {
        private final String format;
        private final java.util.function.Supplier<RuntimeException> failure;
        private final CsvFileCodec csv = new CsvFileCodec();

        Broken(String format, java.util.function.Supplier<RuntimeException> failure) {
            this.format = format;
            this.failure = failure;
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
        public void read(InputStream in, FileReadSpec spec, RowHandler handler) throws Exception {
            csv.read(in, spec, handler);
        }

        @Override
        public void write(OutputStream out, FileWriteSpec spec, ExportModel model) {
            throw failure.get();
        }
    }
}
