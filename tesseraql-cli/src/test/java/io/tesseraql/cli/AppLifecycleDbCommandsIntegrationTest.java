package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import picocli.CommandLine;

/**
 * The database-backed app-lifecycle CLI surface — {@code migrate} (apply/info/validate),
 * {@code test --report}, {@code coverage}, {@code schema} and {@code identity-schema} — drives the
 * same engines as the Maven goals against PostgreSQL, over a freshly scaffolded app.
 */
@Testcontainers
class AppLifecycleDbCommandsIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void migrateTestCoverageSchemaAndIdentityOverPostgres(@TempDir Path dir) throws Exception {
        assertThat(execute("new", "demo", "--stack", dir.toString())).isZero();
        Path app = dir.resolve("demo");

        // Apply, then the read-only operations over the same per-app history.
        assertThat(execute(args(app, "migrate", "apply"))).isZero();
        assertThat(execute(args(app, "migrate", "validate"))).isZero();
        assertThat(execute(args(app, "migrate", "info"))).isZero();

        // The smoke suite runs over the migrated, seeded database; --report writes the overlay.
        assertThat(execute(args(app, "test"))).isZero();
        assertThat(execute(args(app, "test", "--report"))).isZero();
        assertThat(app.resolve(".tesseraql/docs/report.json")).exists();
        assertThat(app.resolve(".tesseraql/docs/history.json")).exists();

        // The editor test-run contract (Phase 55): complete per-case results plus per-file
        // SQL coverage with the 1-based covered/coverable line lists, one JSON object.
        Captured json = executeCapturing(args(app, "test", "--format", "json"));
        assertThat(json.exitCode()).isZero();
        JsonNode document = new ObjectMapper().readTree(json.stdout());
        assertThat(document.get("failed").asLong()).isZero();
        assertThat(document.get("passed").asLong()).isEqualTo(document.get("results").size());
        assertThat(document.get("results").get(0).get("name").asText()).isNotBlank();
        assertThat(document.get("results").get(0).get("passed").asBoolean()).isTrue();
        assertThat(document.get("sql").size()).isPositive();
        JsonNode sqlFile = document.get("sql").get(0);
        assertThat(sqlFile.get("file").asText()).endsWith(".sql");
        assertThat(sqlFile.get("coveredLines").isArray()).isTrue();
        assertThat(sqlFile.get("coverableLines").isArray()).isTrue();

        // Single-case granularity (Phase 56): --case runs exactly the named case.
        Captured single = executeCapturing(args(app, "test", "--format", "json",
                "--case", "the items search returns the seeded row"));
        assertThat(single.exitCode()).isZero();
        JsonNode filtered = new ObjectMapper().readTree(single.stdout());
        assertThat(filtered.get("results").size()).isEqualTo(1);
        assertThat(filtered.get("results").get(0).get("name").asText())
                .isEqualTo("the items search returns the seeded row");

        // Coverage gate (default thresholds are 0, so it passes).
        assertThat(execute(args(app, "coverage"))).isZero();

        // Schema overlay from the live catalog.
        assertThat(execute(args(app, "schema"))).isZero();
        assertThat(app.resolve(".tesseraql/docs/schema.json")).exists();

        // A refusal comes before any work (docs/cli-surface.md decision 10): a database but no
        // password source is one line and exit 2, and the schema is NOT applied — it used to be
        // applied first, with the refusal thrown after it as a stack trace.
        CommandLine refusing = TesseraqlCli.commandLine();
        java.io.StringWriter err = new java.io.StringWriter();
        refusing.setErr(new java.io.PrintWriter(err, true));
        assertThat(refusing.execute(args(app, "identity-schema", "--admin-login", "admin")))
                .isEqualTo(2);
        assertThat(err.toString().strip().lines()).hasSize(1);
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                var tables = connection.getMetaData().getTables(null, null, "tql_users", null)) {
            assertThat(tables.next()).as("the schema must not have been applied").isFalse();
        }

        // The managed IAM schema applies idempotently.
        assertThat(execute(args(app, "identity-schema"))).isZero();
        assertThat(execute(args(app, "identity-schema"))).isZero();

        // The admin seed accepts a PowerShell-5.1-style password file (UTF-16LE with a BOM).
        Path passwordFile = dir.resolve("admin.pw");
        Files.write(passwordFile, "\uFEFFs3cr3t\r\n".getBytes(StandardCharsets.UTF_16LE));
        assertThat(execute(args(app, "identity-schema", "--admin-login", "admin",
                "--admin-password-file", passwordFile.toString()))).isZero();
    }

    /**
     * The runner says what it proved (docs/audit-low-leads.md slice 12): the regression gate
     * answers 3 — the suites ran and passed, a policy said no (decision 10b) — where it used to
     * answer 2, the number the CLI publishes as "nothing ran" (F115).
     */
    @Test
    void theRegressionGateAnswersThreeAndTheUsageRefusalsKeepTheirTwo(@TempDir Path dir)
            throws Exception {
        Path app = scaffolded(dir);

        // A baseline: the whole smoke suite, then one case of it — SQL coverage drops.
        assertThat(execute(args(app, "test", "--report", "--run-id", "r1"))).isZero();
        Captured regressed = executeCapturing(args(app, "test", "--report", "--run-id", "r2",
                "--fail-on-regression", "--case", "the items search returns the seeded row"));
        assertThat(regressed.exitCode()).as(regressed.stderr()).isEqualTo(3);
        assertThat(regressed.stdout()).contains("1 passed, 0 failed");
        assertThat(regressed.stderr()).contains("Coverage regression: SQL");
        // The usage refusals keep their 2, so the gate cannot pass by moving the usage code.
        assertThat(execute(args(app, "test", "--bogus"))).isEqualTo(2);
        assertThat(executeCapturing("test", "--app", dir.resolve("nonexistent").toString(),
                "--jdbc-url", POSTGRES.getJdbcUrl(), "--username", POSTGRES.getUsername(),
                "--password", POSTGRES.getPassword()).exitCode()).isEqualTo(2);
    }

    /** G18: a corrupt history is refused under the gate and left as evidence. */
    @Test
    void aCorruptHistoryIsRefusedUnderTheGateAndWarnedAboutWithoutIt(@TempDir Path dir)
            throws Exception {
        Path app = scaffolded(dir);
        assertThat(execute(args(app, "test", "--report", "--run-id", "r1"))).isZero();
        Path history = app.resolve(".tesseraql/docs/history.json");
        Files.writeString(history, "{\"not\":\"an array\"");

        Captured corrupt = executeCapturing(args(app, "test", "--report", "--run-id", "r3",
                "--fail-on-regression"));
        assertThat(corrupt.exitCode()).as(corrupt.stderr()).isEqualTo(2);
        assertThat(corrupt.stderr()).contains("TQL-REPORT-2006").contains("is unreadable");
        assertThat(Files.readString(history)).isEqualTo("{\"not\":\"an array\"");
        // Without the gate it is one warning and a fresh ring.
        Captured fresh = executeCapturing(args(app, "test", "--report", "--run-id", "r4"));
        assertThat(fresh.exitCode()).isZero();
        assertThat(fresh.stderr()).contains("is unreadable").contains("starting a fresh history");
        assertThat(Files.readString(history)).startsWith("[");
    }

    /** G19 and G16: a --case that names no case is a refusal; a run with no suite says so. */
    @Test
    void aCaseThatNamesNothingIsRefusedAndARunWithNoSuiteSaysSo(@TempDir Path dir)
            throws Exception {
        Path app = scaffolded(dir);

        Captured nothing = executeCapturing(args(app, "test", "--case", "no such case"));
        assertThat(nothing.exitCode()).as(nothing.stderr()).isEqualTo(2);
        assertThat(nothing.stderr()).contains("TQL-YAML-1411").contains("no such case");

        Path tests = app.resolve("tests");
        Path aside = dir.resolve("tests-aside");
        Files.move(tests, aside);
        try {
            Captured none = executeCapturing(args(app, "test"));
            assertThat(none.exitCode()).isZero();
            assertThat(none.stderr()).contains("No suite file under");
            assertThat(none.stdout()).contains("0 passed, 0 failed");
        } finally {
            Files.move(aside, tests);
        }
    }

    /**
     * Unfiled 27 and 10: an unreachable database is the one operator message at 1, for test and
     * schema alike — test used to print N failed cases, schema a coded line at 2.
     */
    @Test
    void anUnreachableDatabaseIsTheOperatorMessageForTest(@TempDir Path dir) throws Exception {
        Captured refused = executeCapturing(unreachable(scaffolded(dir), "test"));
        assertThat(refused.exitCode()).as(refused.stderr()).isEqualTo(1);
        assertThat(refused.stderr()).contains("Could not connect to the database")
                .doesNotContain("FAIL ");
    }

    @Test
    void anUnreachableDatabaseIsTheOperatorMessageForSchema(@TempDir Path dir) throws Exception {
        Captured refused = executeCapturing(unreachable(scaffolded(dir), "schema"));
        assertThat(refused.exitCode()).as(refused.stderr()).isEqualTo(1);
        assertThat(refused.stderr()).contains("Could not connect to the database")
                .doesNotContain("TQL-REPORT-2007");
    }

    /** A command against a port nothing listens on. */
    private static String[] unreachable(Path app, String command) {
        return new String[]{command, "--app", app.toString(), "--jdbc-url",
                "jdbc:postgresql://127.0.0.1:1/never", "--username", "x", "--password", "x"};
    }

    /** A freshly scaffolded app with its migration applied. */
    private Path scaffolded(Path dir) {
        assertThat(execute("new", "demo", "--stack", dir.toString())).isZero();
        Path app = dir.resolve("demo");
        assertThat(execute(args(app, "migrate", "apply"))).isZero();
        return app;
    }

    /** A command (with any positional) plus {@code --app} and the container's datasource flags. */
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

    /** Runs a command capturing stdout and stderr — the process streams and picocli's writer. */
    private static Captured executeCapturing(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            CommandLine commandLine = TesseraqlCli.commandLine();
            commandLine.setErr(new java.io.PrintWriter(
                    new java.io.OutputStreamWriter(err, StandardCharsets.UTF_8), true));
            int exitCode = commandLine.execute(args);
            return new Captured(exitCode, out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Captured(int exitCode, String stdout, String stderr) {
    }
}
