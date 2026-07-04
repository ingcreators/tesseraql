package io.tesseraql.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Executes a SQL script bundled as a classpath resource. The framework keeps its own DDL in plain
 * {@code V1__*.sql} migration files next to the code that owns the tables - never inline in Java -
 * so the schema is readable in SQL tools and the same files double as Flyway migrations at runtime
 * (design ch. 8, 31).
 */
public final class SqlScripts {

    private SqlScripts() {
    }

    /**
     * Executes the bundled migration script matching the datasource's vendor: when a
     * {@code <dir>-<vendor>/<file>} sibling of {@code <dir>/<file>} exists (e.g.
     * {@code operations-oracle/V1__framework_operations.sql}), it replaces the common script
     * entirely - vendors whose DDL diverges keep complete scripts of their own (design ch. 42).
     */
    public static void applyForVendor(DataSource dataSource, Class<?> anchor, String resourcePath)
            throws SQLException {
        String path = resourcePath;
        String vendor = DatabaseVendors.vendor(dataSource).orElse(null);
        if (vendor != null) {
            int slash = resourcePath.lastIndexOf('/');
            String variant = resourcePath.substring(0, slash) + "-" + vendor
                    + resourcePath.substring(slash);
            if (anchor.getResource(variant) != null) {
                path = variant;
            }
        }
        apply(dataSource, anchor, path);
    }

    /**
     * Executes the resource (resolved against the anchor class) on the datasource, one statement
     * at a time - drivers like MySQL's reject multi-statement strings.
     */
    public static void apply(DataSource dataSource, Class<?> anchor, String resourcePath)
            throws SQLException {
        String script = read(anchor, resourcePath);
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String sql : statements(script)) {
                try {
                    statement.execute(sql);
                } catch (SQLException ex) {
                    // Statements without an IF NOT EXISTS form (Oracle DDL, column/index adds
                    // everywhere but PostgreSQL) get their idempotency from tolerated
                    // already-exists errors instead: ORA-00955/-01430, MySQL 1060/1061
                    // (duplicate column/key), and the duplicate-column/-index SQLStates of
                    // PostgreSQL (42701/42P07) and H2 (42121/42111). Everything else still
                    // fails the bootstrap.
                    int code = ex.getErrorCode();
                    String state = ex.getSQLState();
                    boolean tolerated = code == 955 || code == 1430 || code == 1060
                            || code == 1061
                            || "42701".equals(state) || "42P07".equals(state)
                            || "42121".equals(state) || "42111".equals(state);
                    if (!tolerated) {
                        throw ex;
                    }
                }
            }
        }
    }

    /** Splits a script into statements: line comments stripped, separated on {@code ;}. */
    public static java.util.List<String> statements(String script) {
        String withoutComments = script.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce(new StringBuilder(), (sb, line) -> sb.append(line).append('\n'),
                        StringBuilder::append)
                .toString();
        return java.util.Arrays.stream(withoutComments.split(";"))
                .map(String::strip)
                .filter(sql -> !sql.isEmpty())
                .toList();
    }

    /** Reads a classpath SQL resource as UTF-8. */
    public static String read(Class<?> anchor, String resourcePath) {
        try (InputStream in = anchor.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled SQL script: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
