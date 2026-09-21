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
        applyScript(dataSource, read(anchor, resourcePath));
    }

    /**
     * Executes an already-read script on the datasource with the same statement splitting and
     * tolerated already-exists handling as {@link #apply} - for callers whose DDL is not a
     * classpath resource (e.g. a pack's assembled schema).
     */
    public static void applyScript(DataSource dataSource, String script) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String sql : statements(script)) {
                try {
                    statement.execute(sql);
                } catch (SQLException ex) {
                    // Statements without an IF NOT EXISTS form (Oracle DDL, column/index adds
                    // everywhere but PostgreSQL) get their idempotency from tolerated
                    // already-exists errors instead: ORA-00955/-01430, MySQL 1060/1061
                    // (duplicate column/key), SQL Server 2714/2705/1913 (duplicate
                    // object/column/index), the duplicate-column/-table SQLStates of
                    // PostgreSQL (42701/42P07) and H2's duplicate-column/-index error CODES
                    // 42121/42111 — H2 reports those as getErrorCode() under the generic
                    // states 42S21/42S11, and reading them as states left every column add
                    // failing a second boot on H2 (docs/audit-low-leads.md slice 4). A table
                    // create spells IF NOT EXISTS where the vendor takes it; MySQL 1050 and H2
                    // 42101 are deliberately not here. Everything else still fails the
                    // bootstrap.
                    int code = ex.getErrorCode();
                    String state = ex.getSQLState();
                    boolean tolerated = code == 955 || code == 1430 || code == 1060
                            || code == 1061 || code == 2714 || code == 2705 || code == 1913
                            || code == 42121 || code == 42111
                            || "42701".equals(state) || "42P07".equals(state)
                            || ("23505".equals(state) && creates(sql));
                    if (!tolerated) {
                        throw ex;
                    }
                }
            }
        }
    }

    /**
     * Whether the statement creates an object. Two replicas booting together on a fresh
     * PostgreSQL both pass the {@code IF NOT EXISTS} check and both create, and the loser is
     * told a unique violation on the catalogue ({@code pg_type_typname_nsp_index}) rather
     * than the duplicate-table state above — the other replica having won, which on a create
     * is the tolerated already-exists case and on any other statement is data, and still
     * fails (docs/deployment-maturity.md, S5: one of two pods died at boot on it).
     */
    private static boolean creates(String sql) {
        return sql.stripLeading().regionMatches(true, 0, "create", 0, "create".length());
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
