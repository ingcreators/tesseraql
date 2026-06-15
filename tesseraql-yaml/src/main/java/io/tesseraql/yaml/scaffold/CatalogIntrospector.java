package io.tesseraql.yaml.scaffold;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads an entire database catalog (every user table and view) through plain JDBC metadata for the
 * documentation portal's schema view (v3). It generalises {@link TableIntrospector} from a single
 * table to catalog scope: tables are sorted by name and columns follow ordinal position, so the
 * resulting {@link CatalogSchema} — and the {@code schema.json} built from it — is deterministic for
 * a given database state.
 *
 * <p>Scope is the connection's current catalog/schema; only {@code TABLE} and {@code VIEW} object
 * types are read, so driver system catalogs are excluded.
 */
public final class CatalogIntrospector {

    private static final TqlErrorCode INTROSPECT_ERROR = new TqlErrorCode(TqlDomain.APP, 5204);

    /** Introspects the catalog reachable through {@code connection}'s current schema. */
    public CatalogSchema introspect(Connection connection) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();
            String schema = connection.getSchema();
            List<CatalogSchema.Table> tables = new ArrayList<>();
            for (TableRef ref : readTables(metaData, catalog, schema)) {
                tables.add(readTable(metaData, ref));
            }
            tables.sort(Comparator.comparing(CatalogSchema.Table::name,
                    String.CASE_INSENSITIVE_ORDER));
            return new CatalogSchema(tables);
        } catch (SQLException ex) {
            throw new TqlException(INTROSPECT_ERROR,
                    "Failed to introspect database catalog: " + ex.getMessage());
        }
    }

    private record TableRef(String catalog, String schema, String name, String type) {
    }

    private static List<TableRef> readTables(DatabaseMetaData metaData, String catalog,
            String schema)
            throws SQLException {
        List<TableRef> refs = new ArrayList<>();
        try (ResultSet tables = metaData.getTables(catalog, schema, "%",
                new String[]{"TABLE", "VIEW"})) {
            while (tables.next()) {
                String type = "VIEW".equalsIgnoreCase(tables.getString("TABLE_TYPE"))
                        ? "VIEW"
                        : "TABLE";
                refs.add(
                        new TableRef(tables.getString("TABLE_CAT"), tables.getString("TABLE_SCHEM"),
                                tables.getString("TABLE_NAME"), type));
            }
        }
        return refs;
    }

    private static CatalogSchema.Table readTable(DatabaseMetaData metaData, TableRef table)
            throws SQLException {
        List<CatalogSchema.Column> columns = readColumns(metaData, table);
        List<String> primaryKey = readPrimaryKey(metaData, table);
        List<CatalogSchema.ForeignKey> foreignKeys = readForeignKeys(metaData, table);
        List<CatalogSchema.Index> uniqueIndexes = readUniqueIndexes(metaData, table, primaryKey);
        return new CatalogSchema.Table(table.name(), table.type(), table.schema(), columns,
                primaryKey, foreignKeys, uniqueIndexes);
    }

    private static List<CatalogSchema.Column> readColumns(DatabaseMetaData metaData, TableRef table)
            throws SQLException {
        // Keyed by ordinal so columns follow the table's declared order regardless of driver order.
        Map<Integer, CatalogSchema.Column> byOrdinal = new TreeMap<>();
        try (ResultSet columns = metaData.getColumns(table.catalog(), table.schema(), table.name(),
                null)) {
            while (columns.next()) {
                byOrdinal.put(columns.getInt("ORDINAL_POSITION"), new CatalogSchema.Column(
                        columns.getString("COLUMN_NAME"),
                        columns.getInt("DATA_TYPE"),
                        columns.getString("TYPE_NAME"),
                        columns.getInt("COLUMN_SIZE"),
                        columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        "YES".equalsIgnoreCase(columns.getString("IS_AUTOINCREMENT")),
                        columns.getString("COLUMN_DEF")));
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

    /** Imported foreign keys grouped by constraint name, member columns ordered by key sequence. */
    private static List<CatalogSchema.ForeignKey> readForeignKeys(DatabaseMetaData metaData,
            TableRef table) throws SQLException {
        Map<String, Map<Short, String[]>> membersByName = new TreeMap<>();
        Map<String, String> refTableByName = new LinkedHashMap<>();
        try (ResultSet fks = metaData.getImportedKeys(table.catalog(), table.schema(),
                table.name())) {
            while (fks.next()) {
                String name = fks.getString("FK_NAME");
                if (name == null || name.isBlank()) {
                    name = table.name() + "_" + fks.getString("FKCOLUMN_NAME") + "_fkey";
                }
                membersByName.computeIfAbsent(name, key -> new TreeMap<>())
                        .put(fks.getShort("KEY_SEQ"), new String[]{
                                fks.getString("FKCOLUMN_NAME"), fks.getString("PKCOLUMN_NAME")});
                refTableByName.put(name, fks.getString("PKTABLE_NAME"));
            }
        }
        List<CatalogSchema.ForeignKey> result = new ArrayList<>();
        membersByName.forEach((name, members) -> {
            List<String> columns = new ArrayList<>();
            List<String> refColumns = new ArrayList<>();
            for (String[] pair : members.values()) {
                columns.add(pair[0]);
                refColumns.add(pair[1]);
            }
            result.add(new CatalogSchema.ForeignKey(name, columns, refTableByName.get(name),
                    refColumns));
        });
        return result;
    }

    /** Unique indexes, columns ordered by ordinal, excluding the index that backs the primary key. */
    private static List<CatalogSchema.Index> readUniqueIndexes(DatabaseMetaData metaData,
            TableRef table, List<String> primaryKey) throws SQLException {
        Map<String, Map<Short, String>> columnsByIndex = new TreeMap<>();
        try (ResultSet indexes = metaData.getIndexInfo(table.catalog(), table.schema(),
                table.name(), true, false)) {
            while (indexes.next()) {
                String indexName = indexes.getString("INDEX_NAME");
                String columnName = indexes.getString("COLUMN_NAME");
                if (indexName == null || columnName == null) {
                    continue;
                }
                columnsByIndex.computeIfAbsent(indexName, key -> new TreeMap<>())
                        .put(indexes.getShort("ORDINAL_POSITION"), columnName);
            }
        }
        List<CatalogSchema.Index> result = new ArrayList<>();
        columnsByIndex.forEach((indexName, columns) -> {
            List<String> ordered = new ArrayList<>(columns.values());
            if (!sameColumns(ordered, primaryKey)) {
                result.add(new CatalogSchema.Index(indexName, ordered, true));
            }
        });
        return result;
    }

    private static boolean sameColumns(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equalsIgnoreCase(b.get(i))) {
                return false;
            }
        }
        return true;
    }
}
