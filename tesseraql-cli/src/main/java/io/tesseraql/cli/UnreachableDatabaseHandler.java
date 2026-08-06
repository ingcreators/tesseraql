package io.tesseraql.cli;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

/**
 * Shapes a could-not-reach-the-database failure into a two-line operator message instead of a
 * stack trace — the same recoverable-operator-error stance {@code serve} takes for an
 * incompatible {@code --embedded-db} data directory. Every other exception is rethrown, which
 * reproduces picocli's default handling (stack trace on stderr, execution exit code), so
 * genuine bugs keep their full diagnostics.
 */
final class UnreachableDatabaseHandler implements CommandLine.IExecutionExceptionHandler {

    @Override
    public int handleExecutionException(Exception ex, CommandLine commandLine,
            ParseResult parseResult) throws Exception {
        SQLException failure = connectionFailure(ex);
        if (failure == null) {
            throw ex;
        }
        String message = failure.getMessage() == null
                ? failure.toString()
                : failure.getMessage().trim();
        commandLine.getErr().println("Could not connect to the database: " + message);
        commandLine.getErr().println("Check that it is running and that the app's"
                + " tesseraql.datasources.main.jdbcUrl (or --jdbc-url) points at it; a"
                + " `tesseraql serve --embedded-db` running in another terminal also works —"
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
