package io.tesseraql.runtime;

import java.io.IOException;
import java.io.StringWriter;
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
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

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

    /** The maximum rows a CSV export includes (the scan cap) — surfaced so the cap is not silent. */
    int exportLimit() {
        return maxScan;
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

    /** One filter condition: a validated {@code column}, an {@code op}, and a bound {@code value}. */
    record FilterCond(String column, String op, String value) {
    }

    /**
     * One page of rows of {@code table}; {@code page} is zero-based. {@code filters} are AND-combined
     * conditions (each a validated column + operator + bound value) and {@code sortColumn} orders the
     * results. Every column is validated against the table's real columns before use (so it can never
     * be an injection vector) and every value is a bound parameter.
     */
    DataPage browse(String table, int page, String sortColumn, String sortDir,
            List<FilterCond> filters) {
        if (!tables().contains(table)) {
            throw new IllegalArgumentException("No such table: " + table);
        }
        int safePage = Math.max(0, page);
        int offset = safePage * PAGE_SIZE;
        try (Connection connection = datasources.apply("main").getConnection()) {
            connection.setReadOnly(true);
            String quote = connection.getMetaData().getIdentifierQuoteString();
            List<String> tableColumns = columnsOf(connection, table);
            Query query = buildQuery(quote, table, tableColumns, filters, sortColumn, sortDir);
            try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
                statement.setQueryTimeout(queryTimeoutSeconds);
                // Cap the scan so a browse never pulls a whole large table; one extra row past the
                // page detects whether a next page exists.
                statement.setMaxRows(Math.min(offset + PAGE_SIZE + 1, maxScan));
                bindAll(statement, query.binds());
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

    /**
     * The current view (table + filters + sort) as CSV, capped at the scan limit. Same column
     * validation + bound values as {@link #browse}; the whole capped result is exported (no
     * pagination), one row per line, RFC-4180 quoting.
     */
    String exportCsv(String table, String sortColumn, String sortDir, List<FilterCond> filters) {
        if (!tables().contains(table)) {
            throw new IllegalArgumentException("No such table: " + table);
        }
        try (Connection connection = datasources.apply("main").getConnection()) {
            connection.setReadOnly(true);
            String quote = connection.getMetaData().getIdentifierQuoteString();
            List<String> tableColumns = columnsOf(connection, table);
            Query query = buildQuery(quote, table, tableColumns, filters, sortColumn, sortDir);
            try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
                statement.setQueryTimeout(queryTimeoutSeconds);
                statement.setMaxRows(maxScan);
                bindAll(statement, query.binds());
                try (ResultSet rs = statement.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columnCount = meta.getColumnCount();
                    // RFC 4180 via Apache Commons CSV — the same writer the framework's CSV file
                    // codec uses (comma delimiter, double-quote quoting, doubled inner quotes, CRLF),
                    // so a browser export is byte-for-byte consistent with query-export.
                    StringWriter out = new StringWriter();
                    try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.RFC4180)) {
                        List<String> header = new ArrayList<>(columnCount);
                        for (int i = 1; i <= columnCount; i++) {
                            header.add(meta.getColumnLabel(i));
                        }
                        printer.printRecord(header);
                        while (rs.next()) {
                            List<String> cells = new ArrayList<>(columnCount);
                            for (int i = 1; i <= columnCount; i++) {
                                Object value = rs.getObject(i);
                                cells.add(value == null ? "" : String.valueOf(value));
                            }
                            printer.printRecord(cells);
                        }
                    }
                    return out.toString();
                }
            }
        } catch (SQLException | IOException ex) {
            throw new IllegalStateException("Export failed: " + ex.getMessage(), ex);
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

    /** A prepared SQL string with the ordered bind values for its {@code ?} placeholders. */
    private record Query(String sql, List<String> binds) {
    }

    /**
     * Builds {@code SELECT * FROM table [WHERE cond AND …] [ORDER BY col dir]} from validated
     * filters + sort. Unknown columns and unknown/blank ops are dropped; the comparisons run on the
     * text representation ({@code LOWER(CAST(col AS VARCHAR))}) so they are type-agnostic, and every
     * value is a bind parameter.
     */
    private static Query buildQuery(String quote, String table, List<String> columns,
            List<FilterCond> filters, String sortColumn, String sortDir) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(quoteId(quote, table));
        List<String> where = new ArrayList<>();
        List<String> binds = new ArrayList<>();
        if (filters != null) {
            for (FilterCond filter : filters) {
                if (filter == null || !columns.contains(filter.column())) {
                    continue;
                }
                String text = "LOWER(CAST(" + quoteId(quote, filter.column())
                        + " AS VARCHAR(4000)))";
                String raw = quoteId(quote, filter.column());
                String value = filter.value() == null
                        ? ""
                        : filter.value().toLowerCase(Locale.ROOT);
                boolean blank = value.isBlank();
                switch (filter.op() == null ? "" : filter.op()) {
                    case "contains" ->
                        maybe(where, binds, !blank, text + " LIKE ?", "%" + value + "%");
                    case "equals" -> maybe(where, binds, !blank, text + " = ?", value);
                    case "notEquals" -> maybe(where, binds, !blank, text + " <> ?", value);
                    case "startsWith" -> maybe(where, binds, !blank, text + " LIKE ?", value + "%");
                    case "endsWith" -> maybe(where, binds, !blank, text + " LIKE ?", "%" + value);
                    case "isNull" -> where.add(raw + " IS NULL");
                    case "isNotNull" -> where.add(raw + " IS NOT NULL");
                    default -> {
                    }
                }
            }
        }
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", where));
        }
        if (columns.contains(sortColumn)) {
            sql.append(" ORDER BY ").append(quoteId(quote, sortColumn))
                    .append("desc".equalsIgnoreCase(sortDir) ? " DESC" : " ASC");
        }
        return new Query(sql.toString(), binds);
    }

    private static void maybe(List<String> where, List<String> binds, boolean include,
            String clause, String bind) {
        if (include) {
            where.add(clause);
            binds.add(bind);
        }
    }

    private static void bindAll(PreparedStatement statement, List<String> binds)
            throws SQLException {
        for (int i = 0; i < binds.size(); i++) {
            statement.setString(i + 1, binds.get(i));
        }
    }

    private static String truncate(String value) {
        return value.length() > 200 ? value.substring(0, 200) + "…" : value;
    }

    /** One page of a table's rows: its columns, the rows (null-preserving), page, and hasNext. */
    record DataPage(String table, List<String> columns, List<List<String>> rows, int page,
            boolean hasNext) {
    }
}
