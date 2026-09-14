package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * A request the CLI cannot run at all is one line on stderr and exit 2, before any work — the
 * shape every hand-written refusal in this CLI already has (docs/cli-surface.md decision 10).
 * The shared option sets and the commands' own pre-flight checks used to throw
 * {@code IllegalArgumentException} into picocli's default handling: a twenty-line stack trace
 * and exit 1, the code a genuine failure exits with, so a script could not tell a mistyped
 * invocation from a broken bootstrap — and {@code identity-schema} applied the schema before
 * noticing the administrator had no password.
 */
class UsageRefusalTest {

    /** The first-login step the product's own login page teaches, run without a database. */
    @Test
    void identitySchemaWithoutADatabaseIsOneLineAndExit2() {
        Run run = run("identity-schema", "--admin-login", "admin");

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.stderr().strip().lines()).hasSize(1);
        assertThat(run.stderr()).contains("--app").contains("--jdbc-url")
                .doesNotContain("Exception").doesNotContain("\tat ");
    }

    /**
     * A database but no password source: one line naming both sources. That the refusal comes
     * before the schema is applied is {@code AppLifecycleDbCommandsIntegrationTest}'s, on a
     * PostgreSQL the DDL can run against.
     */
    @Test
    void identitySchemaRefusesTheMissingPasswordInOneLine() {
        Run run = run("identity-schema", "--jdbc-url", "jdbc:h2:mem:usage-refusal",
                "--admin-login", "admin");

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.stderr().strip().lines()).hasSize(1);
        assertThat(run.stderr()).contains("--admin-password-file")
                .contains("TESSERAQL_ADMIN_PASSWORD").doesNotContain("Exception");
    }

    @Test
    void aMissingAdminPasswordFileIsOneLineAndExit2(@TempDir Path dir) {
        Run run = run("identity-schema", "--jdbc-url", "jdbc:h2:mem:pwfile",
                "--admin-login", "admin", "--admin-password-file",
                dir.resolve("absent.pw").toString());

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.stderr().strip().lines()).hasSize(1);
        assertThat(run.stderr()).contains("absent.pw").doesNotContain("Exception");
    }

    @Test
    void aTokenLifetimeThatIsNotADurationIsOneLineAndExit2(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: ttl-test
                  security:
                    jwt:
                      secret: unit-test-secret
                """);

        Run zero = run("token", "--app", dir.toString(), "--sub", "aoki", "--ttl", "0h");
        assertThat(zero.exit()).isEqualTo(2);
        assertThat(zero.stderr().strip().lines()).hasSize(1);
        assertThat(zero.stderr()).contains("--ttl").contains("0h").doesNotContain("Exception");

        Run text = run("token", "--app", dir.toString(), "--sub", "aoki", "--ttl", "soon");
        assertThat(text.exit()).isEqualTo(2);
        assertThat(text.stderr().strip().lines()).hasSize(1);
        assertThat(text.stderr()).contains("--ttl").contains("soon")
                .doesNotContain("NumberFormatException");
    }

    @Test
    void aModuleCoordinateThatDoesNotParseIsOneLineAndExit2(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: m\n");

        Run run = run("modules", "add", "not a coordinate", "--app", dir.toString());

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.stderr().strip().lines()).hasSize(1);
        assertThat(run.stderr()).contains("not a coordinate").doesNotContain("Exception");
        assertThat(Files.readString(dir.resolve("config/tesseraql.yml")))
                .as("nothing was written").doesNotContain("modules");
    }

    /** An unreachable database is still the shaped operator error at 1 — not a refusal. */
    @Test
    void anUnreachableDatabaseStaysAtExit1() {
        Run run = run("identity-schema", "--jdbc-url",
                "jdbc:postgresql://localhost:1/nowhere?connectTimeout=2");

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.stderr()).contains("Could not connect to the database");
    }

    private static Run run(String... args) {
        CommandLine commandLine = TesseraqlCli.commandLine();
        StringWriter err = new StringWriter();
        commandLine.setErr(new PrintWriter(err, true));
        int exit = commandLine.execute(args);
        return new Run(exit, err.toString());
    }

    private record Run(int exit, String stderr) {
    }
}
