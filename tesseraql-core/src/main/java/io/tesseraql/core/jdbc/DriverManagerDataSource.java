package io.tesseraql.core.jdbc;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A minimal {@link DataSource} backed by {@link DriverManager}, so any JDBC driver on the classpath
 * works without an extra pool dependency. Shared by the CLI's operator commands, the Maven plugin's
 * mojos, and the test/report tooling — which is why it lives here rather than in any of them
 * (docs/runtime-footprint.md decision 1: the deployment distribution carries the operator commands
 * but not the test tooling).
 */
public final class DriverManagerDataSource implements DataSource {

    private final String url;
    private final String user;
    private final String password;

    public DriverManagerDataSource(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** The JDBC URL this datasource connects with. */
    public String url() {
        return url;
    }

    /** The username it connects as, or {@code null} when the URL carries the identity. */
    public String user() {
        return user;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    public Connection getConnection(String username, String pwd) throws SQLException {
        return DriverManager.getConnection(url, username, pwd);
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // no-op
    }

    @Override
    public void setLoginTimeout(int seconds) {
        // no-op
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getGlobal();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
