package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import picocli.CommandLine;

/**
 * The external-scheduler execution contract (docs/batch-platform.md track D):
 * {@code tesseraql job list/run/rerun} runs in-process against PostgreSQL and exits with codes
 * a scheduler can branch on — 0 completed, 1 failed, 3 calendar-filtered.
 */
@Testcontainers
class JobCommandIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final Pattern EXECUTION = Pattern.compile("Execution (\\S+):");

    @Test
    void listRunRerunAndTheExitCodeContract(@TempDir Path dir) throws Exception {
        assertThat(execute("new", "demo", "--stack", dir.toString())).isZero();
        Path app = dir.resolve("demo");
        writeJobs(app);
        assertThat(execute(args(app, "migrate", "apply"))).isZero();

        // list: every declared job with its trigger story.
        Captured list = executeCapturing(args(app, "job", "list"));
        assertThat(list.exitCode()).isZero();
        assertThat(list.stdout()).contains("demo.touch").contains("demo.gated")
                .contains("demo.flaky").contains("after demo.touch");

        // run: COMPLETED exits 0; the after: chain fires and both executions print.
        Captured touch = executeCapturing(
                args(app, "job", "run", "demo.touch", "--business-date", "2026-08-03"));
        assertThat(touch.exitCode()).isZero();
        assertThat(touch.stdout()).contains("demo.touch COMPLETED")
                .contains("demo.chained COMPLETED")
                .contains("business date 2026-08-03");

        // run: the business-day calendar filters today out — exit 3, distinct from failure,
        // and --ignore-calendar forces the run.
        assertThat(execute(args(app, "job", "run", "demo.gated"))).isEqualTo(3);
        assertThat(execute(args(app, "job", "run", "demo.gated", "--ignore-calendar")))
                .isZero();

        // run: the shifted nominal day — yesterday (a holiday) shifts to today, and the run
        // records the NOMINAL date; the sibling whose nominal day is tomorrow exits 3.
        Captured payday = executeCapturing(args(app, "job", "run", "demo.payday"));
        assertThat(payday.exitCode()).isZero();
        assertThat(payday.stdout())
                .contains("business date " + java.time.LocalDate.now().minusDays(1));
        assertThat(execute(args(app, "job", "run", "demo.paydayGated"))).isEqualTo(3);

        // run: a failing pipeline exits 1 after its first step committed.
        Captured failed = executeCapturing(args(app, "job", "run", "demo.flaky"));
        assertThat(failed.exitCode()).isEqualTo(1);
        assertThat(failed.stdout()).contains("demo.flaky FAILED");
        Matcher matcher = EXECUTION.matcher(failed.stdout());
        assertThat(matcher.find()).isTrue();
        String executionId = matcher.group(1);
        assertThat(markCount()).isEqualTo(1);

        // rerun --from-failed-step: the source's completed step is recorded SKIPPED (its side
        // effect does not double-run) and the fixed second step completes — exit 0.
        execSql("create table flaky_target (id int)");
        Captured rerun = executeCapturing(
                args(app, "job", "rerun", executionId, "--from-failed-step"));
        assertThat(rerun.exitCode()).isZero();
        assertThat(rerun.stdout()).contains("SKIPPED").contains("demo.flaky COMPLETED");
        assertThat(markCount()).isEqualTo(1);

        // Unknown targets cannot run at all: exit 2.
        assertThat(execute(args(app, "job", "run", "demo.no-such"))).isEqualTo(2);
        assertThat(execute(args(app, "job", "rerun", "no-such-execution"))).isEqualTo(2);

        // cancel: the cooperative stop travels through the shared database - a RUNNING
        // execution accepts the request, a finished one has nothing left to stop.
        execSql("insert into tql_job_execution (job_execution_id, job_id, app_name, status,"
                + " start_time, created_at) values ('cli-cancel-1', 'demo.touch', 'demo',"
                + " 'RUNNING', now(), now())");
        assertThat(execute(args(app, "job", "cancel", "cli-cancel-1"))).isZero();
        execSql("update tql_job_execution set status = 'COMPLETED'"
                + " where job_execution_id = 'cli-cancel-1'");
        assertThat(execute(args(app, "job", "cancel", "cli-cancel-1"))).isEqualTo(2);
        assertThat(execute(args(app, "job", "cancel", "no-such-execution"))).isEqualTo(2);
    }

    /**
     * The job arm of the export-declaration refusal on this runner (docs/export-declarations.md
     * decision 1): {@code job run} fills its own job map and never reaches the serving runtime's
     * registration, so it judges every job itself — a mistyped step literal is one line and
     * exit 2 before any execution row exists, where it used to record a FAILED execution with
     * {@code TQL-LD-2810} (a zone) or COMPLETE in {@code Locale.ROOT} (a locale). {@code list}
     * never refuses; the valid twin runs.
     */
    @Test
    void aMistypedExportStepLiteralIsRefusedBeforeAnyExecutionRowExists(@TempDir Path dir)
            throws Exception {
        assertThat(execute("new", "demo", "--stack", dir.toString())).isZero();
        Path app = dir.resolve("demo");
        assertThat(execute(args(app, "migrate", "apply"))).isZero();
        Files.createDirectories(app.resolve("batch/report"));
        Files.writeString(app.resolve("batch/report/report.sql"),
                "select 1 as id, now() as created, 1234.5 as amount\n");
        writeReportJob(app, "      locale: ja_JP\n");
        long before = executionCount("report.daily");

        Captured refused = executeCapturingErr(args(app, "job", "run", "report.daily"));
        assertThat(refused.exitCode()).isEqualTo(2);
        assertThat(refused.stdout()).contains("TQL-YAML-1063", "app 'demo'",
                "job 'report.daily' step 'report'", "export.locale", "'ja_JP'");
        assertThat(execute(args(app, "job", "list"))).isZero();
        assertThat(executionCount("report.daily")).isEqualTo(before);

        writeReportJob(app, "      locale: ja-JP\n      timezone: Asia/Tokyo\n");
        Captured ran = executeCapturing(args(app, "job", "run", "report.daily"));
        assertThat(ran.exitCode()).isZero();
        assertThat(ran.stdout()).contains("report.daily COMPLETED");
        assertThat(executionCount("report.daily")).isEqualTo(before + 1);
    }

    /**
     * The app-wide keys, judged on the same runner before any job runs (decision 12): a step
     * that declares no {@code timezone:} falls back to {@code tesseraql.files.timezone}, and a
     * mistyped key used to reach the step's first write as {@code TQL-LD-2810 ... Unknown
     * time-zone ID}, naming neither the key nor the configuration. Now one line, exit 2, no
     * execution row; the valid key runs the same job to COMPLETED.
     */
    @Test
    void aMistypedFilesConfigKeyIsRefusedBeforeAnyExecutionRowExists(@TempDir Path dir)
            throws Exception {
        assertThat(execute("new", "demo", "--stack", dir.toString())).isZero();
        Path app = dir.resolve("demo");
        assertThat(execute(args(app, "migrate", "apply"))).isZero();
        Files.createDirectories(app.resolve("batch/report"));
        Files.writeString(app.resolve("batch/report/report.sql"),
                "select 1 as id, now() as created, 1234.5 as amount\n");
        writeReportJob(app, "");
        writeFilesTimezone(app, "Asia/Tokio");
        long before = executionCount("report.daily");

        Captured refused = executeCapturingErr(args(app, "job", "run", "report.daily"));
        assertThat(refused.exitCode()).isEqualTo(2);
        assertThat(refused.stdout()).contains("TQL-YAML-1063", "app 'demo'", "config",
                "tesseraql.files.timezone", "'Asia/Tokio'");
        assertThat(executionCount("report.daily")).isEqualTo(before);

        writeFilesTimezone(app, "Asia/Tokyo");
        Captured ran = executeCapturing(args(app, "job", "run", "report.daily"));
        assertThat(ran.exitCode()).isZero();
        assertThat(ran.stdout()).contains("report.daily COMPLETED");
        assertThat(executionCount("report.daily")).isEqualTo(before + 1);
    }

    /**
     * The job arm of the fallback chain on this runner (docs/export-declarations.md decision
     * 28): {@code job run} builds its own executor, and an export step that declares neither
     * key renders in the configured {@code tesseraql.files.timezone} and
     * {@code tesseraql.files.locale} — red when only the served runtime's executor is wired,
     * or when the step reads the zone but not the locale. The JVM is pinned to UTC and
     * {@code en-US} for the run, so a document that followed the host would say {@code 22:30}
     * and {@code 1,234.50}; the configured Kolkata and {@code de} say {@code 04:00} the next
     * day and {@code 1.234,50}.
     */
    @Test
    void theCliJobRunReadsBothConfiguredKeys(@TempDir Path dir) throws Exception {
        assertThat(execute("new", "demo", "--stack", dir.toString())).isZero();
        Path app = dir.resolve("demo");
        assertThat(execute(args(app, "migrate", "apply"))).isZero();
        Files.createDirectories(app.resolve("batch/report"));
        Files.writeString(app.resolve("batch/report/report.sql"),
                "select 1 as id, timestamptz '2026-01-15 22:30:00+00' as created,"
                        + " 1234.5 as amount\n");
        writeReportJob(app, "");
        writeFilesDefaults(app, "Asia/Kolkata", "de");
        long before = executionCount("report.daily");
        String previous = latestTransferSpool("report.daily#report");

        java.util.TimeZone zone = java.util.TimeZone.getDefault();
        java.util.Locale locale = java.util.Locale.getDefault();
        Captured ran;
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Etc/UTC"));
            java.util.Locale.setDefault(java.util.Locale.US);
            ran = executeCapturing(args(app, "job", "run", "report.daily"));
        } finally {
            java.util.TimeZone.setDefault(zone);
            java.util.Locale.setDefault(locale);
        }

        assertThat(ran.exitCode()).isZero();
        assertThat(ran.stdout()).contains("report.daily COMPLETED");
        assertThat(executionCount("report.daily")).isEqualTo(before + 1);
        String spool = latestTransferSpool("report.daily#report");
        assertThat(spool).as("a new transfer for the step").isNotNull().isNotEqualTo(previous);
        String document = Files.readString(Path.of(java.net.URI.create(spool)),
                StandardCharsets.UTF_8);
        assertThat(document).contains("2026-01-16 04:00:00").contains("\"1.234,50\"");
    }

    /** Sets the app-wide {@code tesseraql.files.*} keys of a freshly scaffolded app. */
    private static void writeFilesDefaults(Path app, String zone, String locale)
            throws Exception {
        Path config = app.resolve("config/tesseraql.yml");
        String text = Files.readString(config);
        assertThat(text).as("a fresh scaffold declares no files: block")
                .doesNotContain("\n  files:\n");
        Files.writeString(config, text.replaceFirst("^tesseraql:\n",
                "tesseraql:\n  files:\n    timezone: " + zone + "\n    locale: " + locale + "\n"));
    }

    /** The spool URI of the newest transfer a job step wrote, or null before any. */
    private static String latestTransferSpool(String routeId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select spool_uri from tql_file_transfer where route_id = '" + routeId
                                + "' order by created_at desc limit 1")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (java.sql.SQLException undefinedTable) {
            if ("42P01".equals(undefinedTable.getSQLState())) {
                return null;
            }
            throw undefinedTable;
        }
    }

    /** Sets (or replaces) the app-wide {@code tesseraql.files.timezone} of the scaffolded app. */
    private static void writeFilesTimezone(Path app, String zone) throws Exception {
        Path config = app.resolve("config/tesseraql.yml");
        String text = Files.readString(config);
        String block = "tesseraql:\n  files:\n    timezone: ";
        int at = text.indexOf(block);
        if (at >= 0) {
            int end = text.indexOf('\n', at + block.length());
            text = text.substring(0, at + block.length()) + zone + text.substring(end);
        } else {
            text = text.replaceFirst("^tesseraql:\n", block + zone + "\n");
        }
        Files.writeString(config, text);
    }

    private static void writeReportJob(Path app, String exportTail) throws Exception {
        Files.writeString(app.resolve("batch/report/job.yml"), """
                version: tesseraql/v1
                id: report.daily
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: report
                    sql:
                      file: report.sql
                      mode: query
                    export:
                      format: csv
                      columns:
                        - { name: created, type: datetime }
                        - { name: amount, type: number, format: '#,##0.00' }
                %s""".formatted(exportTail));
    }

    /**
     * The execution rows a job left; the refusal precedes the wiring that creates the
     * bookkeeping schema, so a table that is not there yet is zero rows by construction. The
     * methods share one database and one job id, so each asserts against its own start count.
     */
    private static long executionCount(String jobId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select count(*) from tql_job_execution where job_id = '" + jobId
                                + "'")) {
            return rs.next() ? rs.getLong(1) : -1;
        } catch (java.sql.SQLException undefinedTable) {
            if ("42P01".equals(undefinedTable.getSQLState())) {
                return 0;
            }
            throw undefinedTable;
        }
    }

    private static Captured executeCapturingErr(String... args) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            int exitCode = execute(args);
            return new Captured(exitCode, buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(original);
        }
    }

    /** The demo app's batch surface: a chained pair, a calendar-gated job, a failing pipeline. */
    private void writeJobs(Path app) throws Exception {
        Files.createDirectories(app.resolve("batch/demo"));
        Files.writeString(app.resolve("batch/demo/touch.yml"), """
                version: tesseraql/v1
                id: demo.touch
                kind: job
                recipe: batch-pipeline
                sql: { file: noop.sql, mode: query }
                """);
        Files.writeString(app.resolve("batch/demo/chained.yml"), """
                version: tesseraql/v1
                id: demo.chained
                kind: job
                recipe: batch-pipeline
                trigger:
                  after: demo.touch
                sql: { file: noop.sql, mode: query }
                """);
        Files.writeString(app.resolve("batch/demo/gated.yml"), """
                version: tesseraql/v1
                id: demo.gated
                kind: job
                recipe: batch-pipeline
                trigger:
                  schedule:
                    cron: "0 0 2 * * ?"
                    calendar: never
                sql: { file: noop.sql, mode: query }
                """);
        Files.writeString(app.resolve("batch/demo/flaky.yml"), """
                version: tesseraql/v1
                id: demo.flaky
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: mark
                    sql: { file: mark.sql, mode: update }
                  - id: sendOut
                    sql: { file: send.sql, mode: update }
                """);
        Files.writeString(app.resolve("batch/demo/noop.sql"), "select 1\n");
        Files.writeString(app.resolve("batch/demo/mark.sql"),
                "insert into job_marks (note) values ('ran')\n");
        Files.writeString(app.resolve("batch/demo/send.sql"),
                "insert into flaky_target values (1)\n");
        // A calendar under which no day counts, and one where yesterday is a holiday so a
        // shifted nominal day lands on today.
        java.time.LocalDate nominal = java.time.LocalDate.now().minusDays(1);
        Files.createDirectories(app.resolve("calendars"));
        Files.writeString(app.resolve("calendars/test.yml"), """
                version: tesseraql/v1
                calendars:
                  never:
                    weekend: [monday, tuesday, wednesday, thursday, friday, saturday, sunday]
                  pay-cal:
                    weekend: []
                    holidays:
                      dates: [%s]
                """.formatted(nominal));
        Files.writeString(app.resolve("batch/demo/payday.yml"), """
                version: tesseraql/v1
                id: demo.payday
                kind: job
                recipe: batch-pipeline
                trigger:
                  schedule:
                    cron: "0 0 8 * * ?"
                    calendar: pay-cal
                    dayOfMonth: %d
                sql: { file: noop.sql, mode: query }
                """.formatted(nominal.getDayOfMonth()));
        Files.writeString(app.resolve("batch/demo/payday-gated.yml"), """
                version: tesseraql/v1
                id: demo.paydayGated
                kind: job
                recipe: batch-pipeline
                trigger:
                  schedule:
                    cron: "0 0 8 * * ?"
                    calendar: pay-cal
                    dayOfMonth: %d
                sql: { file: noop.sql, mode: query }
                """.formatted(java.time.LocalDate.now().plusDays(1).getDayOfMonth()));
        Files.writeString(app.resolve("db/migration/V90__job_marks.sql"),
                "create table job_marks (note varchar(32));\n");
    }

    private static long markCount() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select count(*) from job_marks")) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private static void execSql(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private String[] args(Path app, String... command) {
        return Stream.concat(Stream.of(command),
                Stream.of("--app", app.toString(),
                        "--jdbc-url", POSTGRES.getJdbcUrl(),
                        "--username", POSTGRES.getUsername(),
                        "--password", POSTGRES.getPassword()))
                .toArray(String[]::new);
    }

    private static int execute(String... args) {
        return new CommandLine(new TesseraqlCli()).execute(args);
    }

    private static Captured executeCapturing(String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            int exitCode = execute(args);
            return new Captured(exitCode, buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(original);
        }
    }

    private record Captured(int exitCode, String stdout) {
    }
}
