package io.tesseraql.runtime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * The Studio data browser: read-only, paginated row access over the app's {@code main} datasource
 * (Studio backlog). Opt-in via {@code tesseraql.studio.dataBrowser.enabled} because it exposes row
 * data. Every query runs on a read-only connection with a statement timeout, and pagination is done
 * with JDBC {@code setMaxRows} + row skipping (no dialect-specific {@code LIMIT}/{@code OFFSET}), so
 * it works across dialects. The requested table is validated against the live catalog before use, so
 * the name can never be an injection vector.
 */
final class StudioDataService {

    static final int PAGE_SIZE = 50;

    private final Function<String, DataSource> datasources;
    private final boolean enabled;
    private final int queryTimeoutSeconds;
    private final int maxScan;

    StudioDataService(Function<String, DataSource> datasources, boolean enabled,
            int queryTimeoutSeconds, int maxScan) {
        this.datasources = datasources;
        this.enabled = enabled;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.maxScan = Math.max(maxScan, PAGE_SIZE + 1);
    }

    boolean isEnabled() {
        return enabled;
    }

    /** The user tables in the {@code main} datasource's catalog, sorted. */
    List<String> tables() {
        try (Connection connection = datasources.apply("main").getConnection()) {
            connection.setReadOnly(true);
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = connection.getMetaData().getTables(connection.getCatalog(), null,
                    "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            Collections.sort(tables);
            return tables;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list tables: " + ex.getMessage(), ex);
        }
    }

    /**
     * One page of rows of {@code table}; {@code page} is zero-based. An optional {@code filterColumn}
     * with a {@code filterValue} keeps rows whose (text-cast) value contains it (case-insensitive);
     * an optional {@code sortColumn} orders the results. Both columns are validated against the
     * table's real columns before use (so they can never be an injection vector), and the filter
     * value is a bound parameter.
     */
    DataPage browse(String table, int page, String sortColumn, String sortDir, String filterColumn,
            String filterValue) {
        if (!tables().contains(table)) {
            throw new IllegalArgumentException("No such table: " + table);
        }
        int safePage = Math.max(0, page);
        int offset = safePage * PAGE_SIZE;
        try (Connection connection = datasources.apply("main").getConnection()) {
            connection.setReadOnly(true);
            String quote = connection.getMetaData().getIdentifierQuoteString();
            List<String> tableColumns = columnsOf(connection, table);
            boolean hasFilter = tableColumns.contains(filterColumn)
                    && filterValue != null && !filterValue.isBlank();
            boolean hasSort = tableColumns.contains(sortColumn);
            StringBuilder sql = new StringBuilder("SELECT * FROM ")
                    .append(quoteId(quote, table));
            if (hasFilter) {
                sql.append(" WHERE LOWER(CAST(").append(quoteId(quote, filterColumn))
                        .append(" AS VARCHAR(4000))) LIKE ?");
            }
            if (hasSort) {
                sql.append(" ORDER BY ").append(quoteId(quote, sortColumn))
                        .append("desc".equalsIgnoreCase(sortDir) ? " DESC" : " ASC");
            }
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                statement.setQueryTimeout(queryTimeoutSeconds);
                // Cap the scan so a browse never pulls a whole large table; one extra row past the
                // page detects whether a next page exists.
                statement.setMaxRows(Math.min(offset + PAGE_SIZE + 1, maxScan));
                if (hasFilter) {
                    statement.setString(1, "%" + filterValue.toLowerCase(Locale.ROOT) + "%");
                }
                try (ResultSet rs = statement.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columnCount = meta.getColumnCount();
                    List<String> columns = new ArrayList<>();
                    for (int i = 1; i <= columnCount; i++) {
                        columns.add(meta.getColumnLabel(i));
                    }
                    int skipped = 0;
                    while (skipped < offset && rs.next()) {
                        skipped++;
                    }
                    List<List<String>> rows = new ArrayList<>();
                    boolean hasNext = false;
                    while (rs.next()) {
                        if (rows.size() == PAGE_SIZE) {
                            hasNext = true;
                            break;
                        }
                        List<String> row = new ArrayList<>();
                        for (int i = 1; i <= columnCount; i++) {
                            Object value = rs.getObject(i);
                            row.add(value == null ? null : truncate(String.valueOf(value)));
                        }
                        rows.add(row);
                    }
                    return new DataPage(table, columns, rows, safePage, hasNext);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Query failed: " + ex.getMessage(), ex);
        }
    }

    /** The column names of {@code table} in the {@code main} catalog (for sort/filter validation). */
    private static List<String> columnsOf(Connection connection, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getColumns(connection.getCatalog(), null,
                table, "%")) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    private static String quoteId(String quote, String identifier) {
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    private static String truncate(String value) {
        return value.length() > 200 ? value.substring(0, 200) + "…" : value;
    }

    /** One page of a table's rows: its columns, the rows (null-preserving), page, and hasNext. */
    record DataPage(String table, List<String> columns, List<List<String>> rows, int page,
            boolean hasNext) {
    }
}
