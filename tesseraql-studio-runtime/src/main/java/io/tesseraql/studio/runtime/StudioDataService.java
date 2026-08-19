package io.tesseraql.studio.runtime;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

/**
 * The Studio data browser: read-only, paginated row access over the app's declared datasources
 * (Studio backlog; docs/analytics-experience.md track 1). Opt-in via
 * {@code tesseraql.studio.dataBrowser.enabled} because it exposes row data — the opt-in spans
 * every declared datasource. Every query runs on a read-only connection (best effort — a driver
 * that does not support it does not fail the browse; the browser's own SQL is {@code SELECT}
 * over validated identifiers) with a statement timeout, and pagination is done with JDBC
 * {@code setMaxRows} + row skipping (no dialect-specific {@code LIMIT}/{@code OFFSET}), so it
 * works across dialects. The requested datasource is validated against the declared set and the
 * requested table against the live catalog before use, so neither can ever be an injection
 * vector.
 *
 * <p>On a server datasource the table list is the connection's own catalog, as always. A
 * {@code duckdb} connection's own catalog is empty scratch by design — everything interesting
 * is an attach or a lake — so there the browser lists tables and views across every catalog
 * visible on the connection, displayed and addressed as {@code catalog.schema.table}.
 *
 * <p>Row editing stays on {@code main}: non-main data is derived data (a projection, a replica,
 * a query engine's view of files), so the editor never grows a datasource parameter.
 */
final class StudioDataService {

    static final int PAGE_SIZE = 50;

    /** DuckDB catalogs/schemas that are engine furniture, not browsable data. */
    private static final Set<String> ENGINE_CATALOGS = Set.of("system", "temp");
    private static final Set<String> ENGINE_SCHEMAS = Set.of("information_schema", "pg_catalog");

    private final Function<String, DataSource> datasources;
    private final List<String> datasourceNames;
    private final boolean enabled;
    private final int queryTimeoutSeconds;
    private final int maxScan;
    private final boolean editEnabled;

    StudioDataService(Function<String, DataSource> datasources, List<String> datasourceNames,
            boolean enabled, boolean editEnabled, int queryTimeoutSeconds, int maxScan) {
        this.datasources = datasources;
        this.datasourceNames = List.copyOf(datasourceNames);
        this.enabled = enabled;
        this.editEnabled = editEnabled;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.maxScan = Math.max(maxScan, PAGE_SIZE + 1);
    }

    boolean isEnabled() {
        return enabled;
    }

    /** Whether PK-scoped row editing is switched on (its own opt-in beside the browser's). */
    boolean isEditEnabled() {
        return enabled && editEnabled;
    }

    /** The maximum rows a CSV export includes (the scan cap) — surfaced so the cap is not silent. */
    int exportLimit() {
        return maxScan;
    }

    /** The declared datasource names, declaration order ({@code main} first). */
    List<String> datasourceNames() {
        return datasourceNames;
    }

    /** The declared datasource {@code name}, or a refusal — never a fallback to another pool. */
    private DataSource dataSource(String name) {
        if (!datasourceNames.contains(name)) {
            throw new IllegalArgumentException("No such datasource: " + name);
        }
        return datasources.apply(name);
    }

    /** A blank or absent selector means {@code main}, never "whatever comes first". */
    static String normalizeDatasource(String datasource) {
        return datasource == null || datasource.isBlank() ? "main" : datasource;
    }

    /**
     * One browsable table: its JDBC coordinates plus the display form the UI addresses it by
     * (bare name on a server datasource, {@code catalog.schema.table} on a duckdb one).
     */
    private record TableRef(String catalog, String schema, String name, String display) {

        /** The identifier as it appears in generated SQL, each part quoted independently. */
        String quoted(String quote) {
            if (schema == null) {
                return quoteId(quote, name);
            }
            return quoteId(quote, catalog) + "." + quoteId(quote, schema) + "."
                    + quoteId(quote, name);
        }
    }

    /** The browsable tables of {@code datasource}, sorted by display name. */
    List<String> tables(String datasource) {
        try (Connection connection = dataSource(datasource).getConnection()) {
            readOnly(connection);
            List<String> tables = new ArrayList<>();
            for (TableRef ref : listTables(connection)) {
                tables.add(ref.display());
            }
            Collections.sort(tables);
            return tables;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list tables: " + ex.getMessage(), ex);
        }
    }

    private static List<TableRef> listTables(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        List<TableRef> refs = new ArrayList<>();
        if (isDuckDb(metaData)) {
            // The type filter is applied on the read side: DuckDB reports base tables as
            // "BASE TABLE" (the information_schema spelling), and a driver-side filter
            // string would have to guess which spelling the driver normalizes to.
            Set<String> browsable = Set.of("TABLE", "BASE TABLE", "VIEW");
            try (ResultSet rs = metaData.getTables(null, null, "%", null)) {
                while (rs.next()) {
                    String catalog = rs.getString("TABLE_CAT");
                    String schema = rs.getString("TABLE_SCHEM");
                    String name = rs.getString("TABLE_NAME");
                    String type = rs.getString("TABLE_TYPE");
                    if (catalog == null || schema == null || type == null
                            || !browsable.contains(type.toUpperCase(Locale.ROOT))
                            || ENGINE_CATALOGS.contains(catalog.toLowerCase(Locale.ROOT))
                            || ENGINE_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    refs.add(new TableRef(catalog, schema, name,
                            catalog + "." + schema + "." + name));
                }
            }
            return refs;
        }
        try (ResultSet rs = metaData.getTables(connection.getCatalog(), null, "%",
                new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                refs.add(new TableRef(connection.getCatalog(), null, name, name));
            }
        }
        return refs;
    }

    private static boolean isDuckDb(DatabaseMetaData metaData) throws SQLException {
        return "duckdb".equalsIgnoreCase(metaData.getDatabaseProductName());
    }

    /** Resolves a display name against the live listing — membership or refusal, never parsing. */
    private static TableRef resolve(Connection connection, String table) throws SQLException {
        for (TableRef ref : listTables(connection)) {
            if (ref.display().equals(table)) {
                return ref;
            }
        }
        throw new IllegalArgumentException("No such table: " + table);
    }

    /**
     * Read-only is defense in depth, not the boundary: the browser's SQL surface is
     * {@code SELECT} over validated identifiers with bound values, so a driver that cannot
     * switch (DuckDB's) does not fail the browse.
     */
    private static void readOnly(Connection connection) {
        try {
            connection.setReadOnly(true);
        } catch (SQLException unsupported) {
            // Best effort by design.
        }
    }

    /** One filter condition: a validated {@code column}, an {@code op}, and a bound {@code value}. */
    record FilterCond(String column, String op, String value) {
    }

    /**
     * One page of rows of {@code table} on {@code datasource}; {@code page} is zero-based.
     * {@code filters} are conditions (each a validated column + operator + bound value) joined by
     * {@code combinator} (AND/OR), and {@code sortColumn} orders the results. Every column is
     * validated against the table's real columns before use (so it can never be an injection
     * vector) and every value is a bound parameter.
     */
    DataPage browse(String datasource, String table, int page, String sortColumn, String sortDir,
            String combinator, List<FilterCond> filters) {
        int safePage = Math.max(0, page);
        int offset = safePage * PAGE_SIZE;
        try (Connection connection = dataSource(datasource).getConnection()) {
            readOnly(connection);
            TableRef ref = resolve(connection, table);
            String quote = connection.getMetaData().getIdentifierQuoteString();
            Map<String, Integer> columnTypes = columnTypes(connection, ref);
            Query query = buildQuery(quote, ref, columnTypes, filters, combinator, sortColumn,
                    sortDir);
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
                    List<Boolean> numeric = new ArrayList<>();
                    for (int i = 1; i <= columnCount; i++) {
                        columns.add(meta.getColumnLabel(i));
                        numeric.add(isNumericType(meta.getColumnType(i)));
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
                    return new DataPage(ref.display(), columns, numeric, rows, safePage,
                            hasNext);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Query failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * The current view (datasource + table + filters + sort) as CSV, capped at the scan limit.
     * Same column validation + bound values as {@link #browse}; the whole capped result is
     * exported (no pagination), one row per line, RFC-4180 quoting.
     */
    String exportCsv(String datasource, String table, String sortColumn, String sortDir,
            String combinator, List<FilterCond> filters) {
        try (Connection connection = dataSource(datasource).getConnection()) {
            readOnly(connection);
            TableRef ref = resolve(connection, table);
            String quote = connection.getMetaData().getIdentifierQuoteString();
            Map<String, Integer> columnTypes = columnTypes(connection, ref);
            Query query = buildQuery(quote, ref, columnTypes, filters, combinator, sortColumn,
                    sortDir);
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

    /** The columns of the resolved table → their JDBC type (for validation). */
    private static Map<String, Integer> columnTypes(Connection connection, TableRef ref)
            throws SQLException {
        Map<String, Integer> columns = new LinkedHashMap<>();
        try (ResultSet rs = connection.getMetaData().getColumns(ref.catalog(), ref.schema(),
                ref.name(), "%")) {
            while (rs.next()) {
                columns.put(rs.getString("COLUMN_NAME"), rs.getInt("DATA_TYPE"));
            }
        }
        return columns;
    }

    private static String quoteId(String quote, String identifier) {
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    /** A prepared SQL string with the ordered (typed) bind values for its {@code ?} placeholders. */
    private record Query(String sql, List<Object> binds) {
    }

    /**
     * Builds {@code SELECT * FROM table [WHERE cond <combinator> …] [ORDER BY col dir]} from validated
     * filters + sort. Unknown columns and unknown/blank ops are dropped. Text ops (contains/equals/…)
     * compare the text representation ({@code LOWER(CAST(col AS VARCHAR))}); the ordering ops
     * (gt/lt/ge/le) compare the raw column with the value coerced to the column's type (numeric/date/
     * timestamp), so numbers and dates order correctly. Every value is a bound parameter.
     */
    private static Query buildQuery(String quote, TableRef ref, Map<String, Integer> columnTypes,
            List<FilterCond> filters, String combinator, String sortColumn, String sortDir) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(ref.quoted(quote));
        List<String> where = new ArrayList<>();
        List<Object> binds = new ArrayList<>();
        if (filters != null) {
            for (FilterCond filter : filters) {
                if (filter == null || !columnTypes.containsKey(filter.column())) {
                    continue;
                }
                String raw = quoteId(quote, filter.column());
                String text = "LOWER(CAST(" + raw + " AS VARCHAR(4000)))";
                String lower = filter.value() == null
                        ? ""
                        : filter.value().toLowerCase(Locale.ROOT);
                boolean blank = lower.isBlank();
                String orderOp = orderOperator(filter.op());
                if (orderOp != null) {
                    Object typed = blank
                            ? null
                            : coerce(columnTypes.get(filter.column()), filter.value().strip());
                    if (typed != null) {
                        where.add(raw + " " + orderOp + " ?");
                        binds.add(typed);
                    }
                    continue;
                }
                switch (filter.op() == null ? "" : filter.op()) {
                    case "contains" ->
                        maybe(where, binds, !blank, text + " LIKE ?", "%" + lower + "%");
                    case "equals" -> maybe(where, binds, !blank, text + " = ?", lower);
                    case "notEquals" -> maybe(where, binds, !blank, text + " <> ?", lower);
                    case "startsWith" -> maybe(where, binds, !blank, text + " LIKE ?", lower + "%");
                    case "endsWith" -> maybe(where, binds, !blank, text + " LIKE ?", "%" + lower);
                    case "isNull" -> where.add(raw + " IS NULL");
                    case "isNotNull" -> where.add(raw + " IS NOT NULL");
                    default -> {
                    }
                }
            }
        }
        if (!where.isEmpty()) {
            String join = "or".equalsIgnoreCase(combinator) ? " OR " : " AND ";
            sql.append(" WHERE ").append(String.join(join, where));
        }
        if (columnTypes.containsKey(sortColumn)) {
            sql.append(" ORDER BY ").append(quoteId(quote, sortColumn))
                    .append("desc".equalsIgnoreCase(sortDir) ? " DESC" : " ASC");
        }
        return new Query(sql.toString(), binds);
    }

    /** The SQL comparison for an ordering op, or null when {@code op} is not an ordering op. */
    private static String orderOperator(String op) {
        return switch (op == null ? "" : op) {
            case "gt" -> ">";
            case "lt" -> "<";
            case "ge" -> ">=";
            case "le" -> "<=";
            default -> null;
        };
    }

    /**
     * The table's primary-key columns (live JDBC, key-sequence order); empty when none. Editing
     * is a {@code main}-only affordance, so this never takes a datasource.
     */
    List<String> primaryKey(String table) {
        try (Connection connection = dataSource("main").getConnection()) {
            return primaryKey(connection, resolve(connection, table));
        } catch (SQLException | IllegalArgumentException ex) {
            return List.of();
        }
    }

    private static List<String> primaryKey(Connection connection, TableRef ref)
            throws SQLException {
        java.util.TreeMap<Short, String> bySeq = new java.util.TreeMap<>();
        try (ResultSet rs = connection.getMetaData().getPrimaryKeys(ref.catalog(), ref.schema(),
                ref.name())) {
            while (rs.next()) {
                bySeq.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        return new ArrayList<>(bySeq.values());
    }

    /** One editable row (Track J4): every column with its current value, PK fields flagged. */
    record RowView(String table, List<String> pkColumns, List<Map<String, Object>> fields) {
    }

    /** Loads the single row identified by the full primary key, for the edit form (Track J4). */
    RowView row(String table, Map<String, String> pk) {
        if (!isEditEnabled()) {
            throw new IllegalStateException("The data browser row editor is not enabled");
        }
        try (Connection connection = dataSource("main").getConnection()) {
            readOnly(connection);
            TableRef ref = resolve(connection, table);
            String quote = connection.getMetaData().getIdentifierQuoteString();
            Map<String, Integer> columnTypes = columnTypes(connection, ref);
            List<String> pkColumns = requireFullKey(connection, ref, pk, columnTypes);
            StringBuilder sql = new StringBuilder("select * from ")
                    .append(ref.quoted(quote)).append(" where ");
            List<Object> binds = new ArrayList<>();
            appendKeyPredicate(sql, binds, quote, pkColumns, columnTypes, pk);
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                statement.setQueryTimeout(queryTimeoutSeconds);
                statement.setMaxRows(2);
                bindAll(statement, binds);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("No row matches that key");
                    }
                    ResultSetMetaData meta = rs.getMetaData();
                    List<Map<String, Object>> fields = new ArrayList<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        Map<String, Object> field = new java.util.LinkedHashMap<>();
                        String name = meta.getColumnLabel(i);
                        Object value = rs.getObject(i);
                        field.put("name", name);
                        field.put("value", value == null ? null : String.valueOf(value));
                        field.put("pk", containsIgnoreCase(pkColumns, name));
                        fields.add(field);
                    }
                    return new RowView(ref.display(), pkColumns, fields);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Row load failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Applies a PK-scoped single-row UPDATE (Track J4): changed columns are validated against
     * the live catalog, values bind coerced to the column's JDBC type (empty string sets NULL),
     * primary-key columns are never updatable, and exactly one row must be affected. The caller
     * gates this behind the edit atom + an explicit confirm and records the audit entry.
     */
    int updateRow(String table, Map<String, String> pk, Map<String, String> changes) {
        if (!isEditEnabled()) {
            throw new IllegalStateException("The data browser row editor is not enabled");
        }
        try (Connection connection = dataSource("main").getConnection()) {
            TableRef ref = resolve(connection, table);
            String quote = connection.getMetaData().getIdentifierQuoteString();
            Map<String, Integer> columnTypes = columnTypes(connection, ref);
            List<String> pkColumns = requireFullKey(connection, ref, pk, columnTypes);
            List<String> setColumns = new ArrayList<>();
            List<Object> binds = new ArrayList<>();
            for (Map.Entry<String, String> change : changes.entrySet()) {
                String column = change.getKey();
                if (!columnTypes.containsKey(column) || containsIgnoreCase(pkColumns, column)) {
                    continue;
                }
                setColumns.add(column);
                String raw = change.getValue();
                binds.add(raw == null || raw.isEmpty()
                        ? null
                        : coerceStrict(columnTypes.get(column), raw));
            }
            if (setColumns.isEmpty()) {
                throw new IllegalArgumentException("No editable column was selected");
            }
            StringBuilder sql = new StringBuilder("update ").append(ref.quoted(quote))
                    .append(" set ");
            for (int i = 0; i < setColumns.size(); i++) {
                sql.append(i > 0 ? ", " : "").append(quoteId(quote, setColumns.get(i)))
                        .append(" = ?");
            }
            sql.append(" where ");
            appendKeyPredicate(sql, binds, quote, pkColumns, columnTypes, pk);
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                statement.setQueryTimeout(queryTimeoutSeconds);
                bindAll(statement, binds);
                int affected = statement.executeUpdate();
                if (affected != 1) {
                    throw new IllegalStateException(
                            "Expected to update exactly one row, but " + affected + " matched");
                }
                return affected;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Update failed: " + ex.getMessage(), ex);
        }
    }

    /** The pk parameter must name the table's FULL primary key (a PK-less table is not editable). */
    private static List<String> requireFullKey(Connection connection, TableRef ref,
            Map<String, String> pk, Map<String, Integer> columnTypes) throws SQLException {
        List<String> pkColumns = primaryKey(connection, ref);
        if (pkColumns.isEmpty()) {
            throw new IllegalArgumentException(
                    "Table " + ref.display() + " has no primary key; rows are not editable");
        }
        for (String column : pkColumns) {
            if (pk.get(column) == null) {
                throw new IllegalArgumentException("Missing key column: " + column);
            }
        }
        for (String column : pk.keySet()) {
            if (!containsIgnoreCase(pkColumns, column) || !columnTypes.containsKey(column)) {
                throw new IllegalArgumentException("Not a key column: " + column);
            }
        }
        return pkColumns;
    }

    private void appendKeyPredicate(StringBuilder sql, List<Object> binds, String quote,
            List<String> pkColumns, Map<String, Integer> columnTypes, Map<String, String> pk) {
        for (int i = 0; i < pkColumns.size(); i++) {
            String column = pkColumns.get(i);
            sql.append(i > 0 ? " and " : "").append(quoteId(quote, column)).append(" = ?");
            binds.add(coerceStrict(columnTypes.getOrDefault(column, java.sql.Types.VARCHAR),
                    pk.get(column)));
        }
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        return list.stream().anyMatch(item -> item.equalsIgnoreCase(value));
    }

    /** Like {@link #coerce} but a value that will not parse is an error, not a dropped filter. */
    private static Object coerceStrict(int jdbcType, String value) {
        Object coerced = coerce(jdbcType, value);
        if (coerced == null) {
            throw new IllegalArgumentException("Value does not parse for the column type: "
                    + value);
        }
        return coerced;
    }

    /** Coerces a string to the column's JDBC type; null if it won't parse. */
    private static Object coerce(int jdbcType, String value) {
        try {
            return switch (jdbcType) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.DECIMAL,
                        Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE ->
                    new BigDecimal(value);
                case Types.DATE -> Date.valueOf(value);
                case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE ->
                    Timestamp.valueOf(value.replace('T', ' '));
                case Types.BOOLEAN, Types.BIT -> Boolean.valueOf(value);
                default -> value;
            };
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static void maybe(List<String> where, List<Object> binds, boolean include,
            String clause, String bind) {
        if (include) {
            where.add(clause);
            binds.add(bind);
        }
    }

    private static void bindAll(PreparedStatement statement, List<Object> binds)
            throws SQLException {
        for (int i = 0; i < binds.size(); i++) {
            statement.setObject(i + 1, binds.get(i));
        }
    }

    private static String truncate(String value) {
        return value.length() > 200 ? value.substring(0, 200) + "…" : value;
    }

    /** One page of a table's rows: its columns, the rows (null-preserving), page, and hasNext. */
    record DataPage(String table, List<String> columns, List<Boolean> numeric,
            List<List<String>> rows, int page, boolean hasNext) {
    }

    /**
     * Whether a JDBC type renders as a number — the data browser's {@code data-numeric} column
     * hint (hc-briefs.md brief 7): numeric columns end-align and the kit's tabular figures do
     * the digit alignment. From {@link ResultSetMetaData}, so it is the RESULT's truth, not a
     * schema guess.
     */
    private static boolean isNumericType(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.FLOAT,
                    Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL ->
                true;
            default -> false;
        };
    }
}
