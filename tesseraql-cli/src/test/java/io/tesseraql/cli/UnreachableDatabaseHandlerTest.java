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
 * 1 instead of a raw driver stack trace; everything else keeps picocli's default diagnostics.
 */
class UnreachableDatabaseHandlerTest {

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

        int exit = new UnreachableDatabaseHandler().handleExecutionException(
                new SQLException("the database is starting up", "08001"), commandLine, null);

        assertThat(exit).isEqualTo(1);
        assertThat(err.toString()).contains("the database is starting up");
    }

    @Test
    void sqlErrorsOverALiveConnectionAreRethrown() {
        SQLException syntaxError = new SQLException("syntax error at or near", "42601");
        assertThatThrownBy(() -> new UnreachableDatabaseHandler()
                .handleExecutionException(syntaxError, TesseraqlCli.commandLine(), null))
                .isSameAs(syntaxError);
    }

    @Test
    void nonDatabaseExceptionsAreRethrown() {
        IllegalStateException boom = new IllegalStateException("boom");
        assertThatThrownBy(() -> new UnreachableDatabaseHandler()
                .handleExecutionException(boom, TesseraqlCli.commandLine(), null))
                .isSameAs(boom);
    }
}
