package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * The CLI's operator-error stance for an unreachable database: one clear message and exit code
 * 1 instead of a raw driver stack trace; a {@link UsageRefusal} is one line and exit 2
 * ({@code UsageRefusalTest} drives the commands that throw it); a coded exception is its
 * message and exit 2 ({@code CodedRefusalTest} drives the verbs that let one escape); everything
 * else keeps picocli's default diagnostics.
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

    /**
     * The framework's own diagnosis — a code and a sentence written for a reader — is never a
     * bug's: its message, no trace, exit 2 (docs/cli-surface.md decision 10a). Measured before
     * it was shaped: twenty-two verbs printed a route that does not parse as a 55-line trace
     * with exit 1, while {@code dev}, {@code host} and {@code mcp} printed the same exception's
     * sentence with exit 2.
     */
    @Test
    void aCodedExceptionIsItsMessageAndExit2() throws Exception {
        CommandLine commandLine = TesseraqlCli.commandLine();
        StringWriter err = new StringWriter();
        commandLine.setErr(new PrintWriter(err, true));
        TqlException coded = TqlException.builder(new TqlErrorCode(TqlDomain.APP, 9999))
                .message("Application 'x' declares modules but has no lock")
                .source("config/tesseraql.yml").line(4).build();

        int exit = new CliExceptionHandler().handleExecutionException(coded, commandLine, null);

        assertThat(exit).isEqualTo(2);
        assertThat(err.toString())
                .isEqualTo("TQL-APP-9999: Application 'x' declares modules but has no lock"
                        + " [config/tesseraql.yml:4]" + System.lineSeparator());
    }

    /**
     * The order of the shapes: a coded exception that wraps a refused connection is still the
     * operator message at exit 1 — the command tried the connection — because the database shape
     * walks the cause chain before the coded shape is asked. Red with the two clauses swapped.
     */
    @Test
    void aCodedExceptionWrappingARefusedConnectionIsStillTheDatabaseMessageAtExit1()
            throws Exception {
        CommandLine commandLine = TesseraqlCli.commandLine();
        StringWriter err = new StringWriter();
        commandLine.setErr(new PrintWriter(err, true));
        TqlException wrapped = new TqlException(new TqlErrorCode(TqlDomain.APP, 9999),
                "datasource main could not be opened",
                new SQLException("Connection to localhost:1 refused", "08001"));

        int exit = new CliExceptionHandler().handleExecutionException(wrapped, commandLine,
                null);

        assertThat(exit).isEqualTo(1);
        assertThat(err.toString()).startsWith("Could not connect to the database: Connection to"
                + " localhost:1 refused").doesNotContain("TQL-APP-9999");
    }

    @Test
    void nonDatabaseExceptionsAreRethrown() {
        IllegalStateException boom = new IllegalStateException("boom");
        assertThatThrownBy(() -> new CliExceptionHandler()
                .handleExecutionException(boom, TesseraqlCli.commandLine(), null))
                .isSameAs(boom);
    }
}
