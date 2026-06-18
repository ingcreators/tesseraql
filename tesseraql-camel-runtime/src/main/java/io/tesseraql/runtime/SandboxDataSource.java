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
 * A read-only, capped {@link DataSource} wrapper used to sandbox the Studio test runner (backlog
 * A2): the editor may run a route's declarative {@code sql} test cases against the dev datasource,
 * so every handed-out connection is forced read-only with manual commit, every statement gets a
 * query timeout and a max-row cap, and the connection rolls back (never commits) when closed. Even
 * if a case's SQL tries to write, the read-only transaction rejects it and nothing persists.
 *
 * <p>The wrapper is intentionally thin: the test runner only acquires a connection, prepares a
 * statement, and reads a result set, so the connection proxy special-cases statement creation,
 * commit/read-only locking, and close; everything else delegates to the pooled connection.
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
            real.setReadOnly(true);
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
                            // Keep the sandbox locked on: writes never commit, never go read-write.
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
