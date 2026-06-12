package io.tesseraql.yaml.scaffold;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads a table's shape through plain JDBC metadata (roadmap Phase 23). The introspector is the
 * only piece of the scaffolder that talks to a database; everything downstream works from the
 * returned {@link TableSchema}, so generation stays deterministic and testable without a
 * connection.
 *
 * <p>Table names resolve case-insensitively: the name is tried as given, lowercased (PostgreSQL
 * folding), and uppercased (H2/Oracle folding), and the first match's catalog and schema scope the
 * column, key, and index lookups.
 */
public final class TableIntrospector {

    private static final TqlErrorCode INTROSPECT_ERROR = new TqlErrorCode(TqlDomain.APP, 5201);

    /** Introspects {@code tableName} through the connection's metadata. */
    public TableSchema introspect(Connection connection, String tableName) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            TableRef table = resolveTable(metaData, tableName);
            List<TableSchema.Column> columns = readColumns(metaData, table);
            if (columns.isEmpty()) {
                throw new TqlException(INTROSPECT_ERROR,
                        "Table '" + tableName + "' has no readable columns");
            }
            List<String> primaryKey = readPrimaryKey(metaData, table);
            Map<String, String> uniqueIndexes = readUniqueIndexes(metaData, table, primaryKey);
            return new TableSchema(table.name(), columns, primaryKey, uniqueIndexes);
        } catch (SQLException ex) {
            throw new TqlException(INTROSPECT_ERROR,
                    "Failed to introspect table '" + tableName + "': " + ex.getMessage());
        }
    }

    private record TableRef(String catalog, String schema, String name) {
    }

    private static TableRef resolveTable(DatabaseMetaData metaData, String tableName)
            throws SQLException {
        for (String candidate : candidates(tableName)) {
            try (ResultSet tables = metaData.getTables(null, null, candidate,
                    new String[]{"TABLE"})) {
                if (tables.next()) {
                    return new TableRef(tables.getString("TABLE_CAT"),
                            tables.getString("TABLE_SCHEM"), tables.getString("TABLE_NAME"));
                }
            }
        }
        throw new TqlException(INTROSPECT_ERROR, "Table '" + tableName
                + "' was not found in the connected database (checked as given, lowercased,"
                + " and uppercased)");
    }

    private static List<String> candidates(String tableName) {
        List<String> candidates = new ArrayList<>();
        candidates.add(tableName);
        String lower = tableName.toLowerCase(java.util.Locale.ROOT);
        String upper = tableName.toUpperCase(java.util.Locale.ROOT);
        if (!candidates.contains(lower)) {
            candidates.add(lower);
        }
        if (!candidates.contains(upper)) {
            candidates.add(upper);
        }
        return candidates;
    }

    private static List<TableSchema.Column> readColumns(DatabaseMetaData metaData, TableRef table)
            throws SQLException {
        // Collected keyed by ordinal so the schema (and with it every generated artifact)
        // follows the table's declared column order regardless of driver result ordering.
        Map<Integer, TableSchema.Column> byOrdinal = new TreeMap<>();
        try (ResultSet columns = metaData.getColumns(table.catalog(), table.schema(),
                table.name(), null)) {
            while (columns.next()) {
                byOrdinal.put(columns.getInt("ORDINAL_POSITION"), new TableSchema.Column(
                        columns.getString("COLUMN_NAME"),
                        columns.getInt("DATA_TYPE"),
                        columns.getString("TYPE_NAME"),
                        columns.getInt("COLUMN_SIZE"),
                        columns.getInt("DECIMAL_DIGITS"),
                        columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        "YES".equalsIgnoreCase(columns.getString("IS_AUTOINCREMENT")),
                        columns.getString("COLUMN_DEF") != null));
            }
        }
        return List.copyOf(byOrdinal.values());
    }

    private static List<String> readPrimaryKey(DatabaseMetaData metaData, TableRef table)
            throws SQLException {
        Map<Short, String> byKeySeq = new TreeMap<>();
        try (ResultSet keys = metaData.getPrimaryKeys(table.catalog(), table.schema(),
                table.name())) {
            while (keys.next()) {
                byKeySeq.put(keys.getShort("KEY_SEQ"), keys.getString("COLUMN_NAME"));
            }
        }
        return List.copyOf(byKeySeq.values());
    }

    /**
     * Single-column unique indexes, excluding the primary key: the constraint names a violation
     * reports, mapped to the column whose form field should carry the error (Phase 18
     * constraint-violation mapping).
     */
    private static Map<String, String> readUniqueIndexes(DatabaseMetaData metaData, TableRef table,
            List<String> primaryKey) throws SQLException {
        Map<String, List<String>> columnsByIndex = new TreeMap<>();
        try (ResultSet indexes = metaData.getIndexInfo(table.catalog(), table.schema(),
                table.name(), true, false)) {
            while (indexes.next()) {
                String indexName = indexes.getString("INDEX_NAME");
                String columnName = indexes.getString("COLUMN_NAME");
                if (indexName == null || columnName == null) {
                    continue;
                }
                columnsByIndex.computeIfAbsent(indexName, key -> new ArrayList<>())
                        .add(columnName);
            }
        }
        Map<String, String> unique = new LinkedHashMap<>();
        columnsByIndex.forEach((indexName, columns) -> {
            if (columns.size() != 1) {
                return;
            }
            String column = columns.get(0);
            if (primaryKey.stream().anyMatch(key -> key.equalsIgnoreCase(column))) {
                return;
            }
            unique.put(indexName, column);
        });
        return unique;
    }
}
