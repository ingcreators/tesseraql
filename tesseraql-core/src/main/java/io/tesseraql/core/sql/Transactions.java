package io.tesseraql.core.sql;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * One JDBC transaction, owned in one place: take autocommit off, run the body, commit — and on
 * <em>any</em> failure roll back before autocommit is restored.
 *
 * <p>It exists because that bracket was written out fourteen times and got the failure half wrong
 * almost everywhere. Restoring autocommit <b>commits an open transaction</b> — that is the
 * {@link Connection#setAutoCommit(boolean)} contract, and what pgjdbc and Connector/J both do — so
 * a handler that catches less than everything commits the work it was told to abandon. The
 * narrowest catch found was none at all, on a CSV import whose own comments say a partial import
 * must never land; the commonest listed {@code SQLException} and {@code RuntimeException} and let
 * an {@code Error} through (docs/two-way-sql-parser.md decision 17).
 *
 * <p>An {@code Error} is rethrown exactly as it arrived. A caller that dressed one as a coded,
 * catchable, retryable failure would invite a retry of an {@code OutOfMemoryError}, so the rule
 * lives here rather than at fourteen call sites — which also means no caller needs a widened catch
 * of its own, and none has one.
 */
public final class Transactions {

    private static final System.Logger LOG = System.getLogger(Transactions.class.getName());

    /**
     * The body of a transaction.
     *
     * <p>{@code E} is the one further checked exception a body may throw, because five of the
     * bodies this replaces throw {@code IOException} or wider — a codec reading a spooled file
     * inside the import's transaction, for instance. A {@code SQLException}-only body interface
     * does not compile at those sites.
     */
    @FunctionalInterface
    public interface Work<T, E extends Exception> {
        T run(Connection connection) throws SQLException, E;
    }

    private Transactions() {
    }

    /**
     * Runs {@code work} in a transaction on {@code connection} and returns its value.
     *
     * <p>Named {@code call} rather than overloading {@code run}, because a value form and a void
     * form of one name are ambiguous for a lambda whose body is a statement expression — the
     * compiler says so, and this campaign has already paid once for two contracts wearing one
     * name (decision 2). {@link Runnable} and {@link java.util.concurrent.Callable} name the same
     * pair the same way.
     *
     * <p>{@code name} names the transaction for the log line a failed autocommit restore writes; a
     * statement's own failure keeps whatever name that statement was given.
     */
    public static <T, E extends Exception> T call(Connection connection, String name,
            Work<T, E> work) throws SQLException, E {
        boolean previous = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = work.run(connection);
            connection.commit();
            return result;
        } catch (Throwable failure) {
            // Everything, not a listed set: the restore below would otherwise commit whatever the
            // body had already written. Precise rethrow keeps the declared throws clause honest —
            // this cannot widen it.
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        } finally {
            restoreQuietly(connection, previous, name);
        }
    }

    /** The same, for a body with nothing to return. */
    public static <E extends Exception> void run(Connection connection, String name,
            VoidWork<E> work) throws SQLException, E {
        call(connection, name, c -> {
            work.run(c);
            return null;
        });
    }

    /** A body with nothing to return; separate from {@link Work} so a lambda is unambiguous. */
    @FunctionalInterface
    public interface VoidWork<E extends Exception> {
        void run(Connection connection) throws SQLException, E;
    }

    /**
     * Restores autocommit without throwing, for a bracket that cannot be a lambda.
     *
     * <p>The transaction is already committed or rolled back by here. A restore that fails would
     * otherwise replace that outcome with a failure the caller acts on — a committed create
     * re-reported as a 500 invites the retry that duplicates it. The pool retires the sick
     * connection.
     *
     * <p>Public because the rule outlives this class: a hand-rolled bracket restores autocommit in
     * a {@code finally}, and a {@code finally} that throws discards the enclosing {@code return}.
     * Four owners lost a committed outcome that way. Calling this is the whole fix; copying the
     * body is how the rollback half came to be written out fourteen times.
     */
    public static void restoreQuietly(Connection connection, boolean previous, String name) {
        try {
            connection.setAutoCommit(previous);
        } catch (SQLException restore) {
            LOG.log(System.Logger.Level.WARNING,
                    "Could not restore autocommit after transaction {0}: {1}", name,
                    restore.getMessage());
        }
    }
}
