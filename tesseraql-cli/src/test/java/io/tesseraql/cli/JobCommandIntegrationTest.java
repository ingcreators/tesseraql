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
