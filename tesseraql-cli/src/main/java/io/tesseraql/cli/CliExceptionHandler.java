package io.tesseraql.cli;

import io.tesseraql.core.error.TqlException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

/**
 * The CLI's one exception shaper (docs/cli-surface.md decision 10, 10a). Three shapes, two exit
 * codes: a {@link UsageRefusal} — a request that cannot run at all, thrown before any work — is
 * its message as one line on stderr and exit {@code 2}; a could-not-reach-the-database failure
 * is a two-line operator message and exit {@code 1} — the same recoverable-operator-error stance
 * {@code dev} takes for an incompatible {@code --embedded-db} data directory, and 1 because the
 * command did run and met a failure; a {@link TqlException} is the framework's own diagnosis —
 * a code, a sentence written for a reader, the declaration's file and line when it has one —
 * and is its message and exit {@code 2}, because what throws one past a command on this CLI is
 * a declaration the command could not act on (a manifest that does not load, a lock that is
 * missing), and a command whose work can end in a coded failure after side effects maps that to
 * 1 itself, as {@code job run} does. Every other exception is rethrown, which reproduces
 * picocli's default handling (stack trace on stderr, execution exit code), so genuine bugs keep
 * their full diagnostics.
 *
 * <p>The database shape is asked before the coded one, and it walks the cause chain: a coded
 * exception that wraps a refused connection is still the operator message at 1 — the command
 * tried the connection.
 */
final class CliExceptionHandler implements CommandLine.IExecutionExceptionHandler {

    @Override
    public int handleExecutionException(Exception ex, CommandLine commandLine,
            ParseResult parseResult) throws Exception {
        if (ex instanceof UsageRefusal refusal) {
            commandLine.getErr().println(refusal.getMessage());
            commandLine.getErr().flush();
            return CommandLine.ExitCode.USAGE;
        }
        SQLException failure = connectionFailure(ex);
        if (failure == null) {
            if (ex instanceof TqlException coded) {
                commandLine.getErr().println(coded.getMessage());
                commandLine.getErr().flush();
                return CommandLine.ExitCode.USAGE;
            }
            throw ex;
        }
        String message = failure.getMessage() == null
                ? failure.toString()
                : failure.getMessage().trim();
        commandLine.getErr().println("Could not connect to the database: " + message);
        commandLine.getErr().println("Check that it is running and that the app's"
                + " tesseraql.datasources.main.jdbcUrl (or --jdbc-url) points at it; a"
                + " `tesseraql dev --embedded-db` running in another terminal also works —"
                + " database commands pick up its " + EmbeddedDbMarker.RELATIVE_PATH
                + " marker.");
        commandLine.getErr().flush();
        return commandLine.getCommandSpec().exitCodeOnExecutionException();
    }

    /**
     * The first {@link SQLException} in the cause chain that marks a failure to reach the
     * database at all: SQLState class 08 (connection exception, the standard the PostgreSQL
     * driver reports for a refused connection) or a socket-level {@code java.net} cause below
     * it (what H2 and DuckDB style drivers wrap). SQL errors raised over a live connection
     * match neither and keep their stack trace. The walk is depth-capped because cause chains
     * may cycle.
     */
    private static SQLException connectionFailure(Throwable ex) {
        Throwable cause = ex;
        for (int depth = 0; cause != null && depth < 20; cause = cause.getCause(), depth++) {
            if (cause instanceof SQLException sql
                    && (connectionSqlState(sql) || socketCause(sql))) {
                return sql;
            }
        }
        return null;
    }

    private static boolean connectionSqlState(SQLException ex) {
        return ex.getSQLState() != null && ex.getSQLState().startsWith("08");
    }

    private static boolean socketCause(SQLException ex) {
        Throwable cause = ex.getCause();
        for (int depth = 0; cause != null && depth < 20; cause = cause.getCause(), depth++) {
            if (cause instanceof java.net.ConnectException
                    || cause instanceof UnknownHostException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof SocketException) {
                return true;
            }
        }
        return false;
    }
}
