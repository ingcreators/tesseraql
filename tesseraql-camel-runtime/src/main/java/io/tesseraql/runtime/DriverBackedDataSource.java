package io.tesseraql.runtime;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import javax.sql.DataSource;

/**
 * A {@link DataSource} over one {@link Driver} instance — the seam that takes
 * {@code DriverManager} off a pool's load-bearing path (docs/module-scope.md). Hikari falls back
 * to {@code DriverManager} when only a JDBC URL is set, and {@code DriverManager} is JVM-global
 * and first-wins per URL; holding the driver object directly lets each application's pool bind
 * the driver its own module loader defines, so two applications can carry the same driver at
 * different versions.
 *
 * <p>The properties carry the pool's {@code dataSourceProperties} plus {@code user}/{@code
 * password}, exactly what Hikari's own {@code DriverManager} path would pass.
 */
final class DriverBackedDataSource implements DataSource {

    private final Driver driver;
    private final String url;
    private final Properties properties;
    private PrintWriter logWriter;
    private int loginTimeoutSeconds;

    DriverBackedDataSource(Driver driver, String url, Properties properties) {
        this.driver = driver;
        this.url = url;
        this.properties = properties;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = driver.connect(url, properties);
        if (connection == null) {
            // Per the Driver contract null means "not my URL" — impossible here, since the
            // driver was selected by acceptsURL, so surface it as the error it is.
            throw new SQLException("Driver " + driver.getClass().getName()
                    + " refused its own URL " + url);
        }
        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Properties overridden = new Properties();
        overridden.putAll(properties);
        if (username != null) {
            overridden.setProperty("user", username);
        }
        if (password != null) {
            overridden.setProperty("password", password);
        }
        Connection connection = driver.connect(url, overridden);
        if (connection == null) {
            throw new SQLException("Driver " + driver.getClass().getName()
                    + " refused its own URL " + url);
        }
        return connection;
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        this.loginTimeoutSeconds = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeoutSeconds;
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return driver.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
