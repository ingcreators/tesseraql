package io.tesseraql.runtime;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * An auto-rollback, capped {@link DataSource} wrapper used to sandbox the Studio test runner
 * (backlog A2): the editor may run a route's or job's declarative test cases against the dev
 * datasource, so every handed-out connection runs in a manual-commit transaction that is rolled
 * back when closed, with commits suppressed — a write case (e.g. an {@code INSERT … RETURNING})
 * executes and its rows are returned, but nothing persists. Every statement also gets a query
 * timeout and a max-row cap, so a query can neither run away nor flood memory.
 *
 * <p>The wrapper is intentionally thin: the test runner acquires a connection, prepares a
 * statement, reads a result set or update count, and manages its own case transaction, so the
 * connection proxy special-cases statement creation, commit/auto-commit/read-only locking, and
 * close; everything else delegates to the pooled connection. The runner's own transaction
 * handling composes cleanly: its {@code setAutoCommit} calls are suppressed (the sandbox is
 * already manual-commit) and its per-case {@code rollback} delegates — one more rollback before
 * the rollback-on-close. (The no-persistence guarantee rests on rollback-on-close plus commit
 * suppression; an explicit {@code COMMIT} statement inside a case's SQL would defeat it, but
 * 2-way SQL files carry single statements, never transaction control.)
 */
final class SandboxDataSource implements DataSource {

    private final DataSource delegate;
    private final int queryTimeoutSeconds;
    private final int maxRows;

    SandboxDataSource(DataSource delegate, int queryTimeoutSeconds, int maxRows) {
        this.delegate = delegate;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.maxRows = maxRows;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return sandbox(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return sandbox(delegate.getConnection(username, password));
    }

    private Connection sandbox(Connection real) throws SQLException {
        try {
            real.setAutoCommit(false);
        } catch (SQLException ex) {
            real.close();
            throw ex;
        }
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    try {
                        switch (method.getName()) {
                            case "prepareStatement", "prepareCall", "createStatement" -> {
                                Object statement = method.invoke(real, args);
                                applyCaps((Statement) statement);
                                return statement;
                            }
                            // Lock the sandbox transaction: ignore commit (suppressed so writes
                            // never persist) and any attempt to flip auto-commit or read-only.
                            case "commit", "setReadOnly", "setAutoCommit" -> {
                                return null;
                            }
                            case "close" -> {
                                rollbackQuietly(real);
                                real.close();
                                return null;
                            }
                            default -> {
                                return method.invoke(real, args);
                            }
                        }
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });
    }

    private void applyCaps(Statement statement) throws SQLException {
        if (queryTimeoutSeconds > 0) {
            statement.setQueryTimeout(queryTimeoutSeconds);
        }
        if (maxRows > 0) {
            statement.setMaxRows(maxRows);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best-effort: the connection is being discarded to the pool, which resets it anyway
        }
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() {
        throw new UnsupportedOperationException("getParentLogger");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }
}
