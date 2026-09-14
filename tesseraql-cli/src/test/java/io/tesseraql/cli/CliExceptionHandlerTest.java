package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * The CLI's operator-error stance for an unreachable database: one clear message and exit code
 * 1 instead of a raw driver stack trace; a {@link UsageRefusal} is one line and exit 2
 * ({@code UsageRefusalTest} drives the commands that throw it); everything else keeps picocli's
 * default diagnostics.
 */
class CliExceptionHandlerTest {

    @Test
    void refusedConnectionIsAOneLineMessageNotAStackTrace() {
        CommandLine commandLine = TesseraqlCli.commandLine();
        StringWriter err = new StringWriter();
        commandLine.setErr(new PrintWriter(err, true));

        // Port 1 answers nothing; the driver reports SQLState 08xxx over a ConnectException.
        int exit = commandLine.execute("identity-schema", "--jdbc-url",
                "jdbc:postgresql://localhost:1/nowhere?connectTimeout=2");

        assertThat(exit).isEqualTo(1);
        assertThat(err.toString())
                .contains("Could not connect to the database")
                .contains("work/embedded-db.jdbc")
                .doesNotContain("at org.postgresql");
    }

    @Test
    void sqlState08IsShapedEvenWithoutASocketCause() throws Exception {
        CommandLine commandLine = TesseraqlCli.commandLine();
        StringWriter err = new StringWriter();
        commandLine.setErr(new PrintWriter(err, true));

        int exit = new CliExceptionHandler().handleExecutionException(
                new SQLException("the database is starting up", "08001"), commandLine, null);

        assertThat(exit).isEqualTo(1);
        assertThat(err.toString()).contains("the database is starting up");
    }

    @Test
    void sqlErrorsOverALiveConnectionAreRethrown() {
        SQLException syntaxError = new SQLException("syntax error at or near", "42601");
        assertThatThrownBy(() -> new CliExceptionHandler()
                .handleExecutionException(syntaxError, TesseraqlCli.commandLine(), null))
                .isSameAs(syntaxError);
    }

    @Test
    void aUsageRefusalIsItsMessageAndExit2() throws Exception {
        CommandLine commandLine = TesseraqlCli.commandLine();
        StringWriter err = new StringWriter();
        commandLine.setErr(new PrintWriter(err, true));

        int exit = new CliExceptionHandler().handleExecutionException(
                new UsageRefusal("--jdbc-url is required"), commandLine, null);

        assertThat(exit).isEqualTo(2);
        assertThat(err.toString()).isEqualTo("--jdbc-url is required" + System.lineSeparator());
    }

    /** A plain IllegalArgumentException is a bug's, not the operator's: the stack trace stays. */
    @Test
    void aPlainIllegalArgumentExceptionIsRethrown() {
        IllegalArgumentException bug = new IllegalArgumentException("negative capacity");
        assertThatThrownBy(() -> new CliExceptionHandler()
                .handleExecutionException(bug, TesseraqlCli.commandLine(), null))
                .isSameAs(bug);
    }

    @Test
    void nonDatabaseExceptionsAreRethrown() {
        IllegalStateException boom = new IllegalStateException("boom");
        assertThatThrownBy(() -> new CliExceptionHandler()
                .handleExecutionException(boom, TesseraqlCli.commandLine(), null))
                .isSameAs(boom);
    }
}
