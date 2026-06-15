package io.tesseraql.yaml.scaffold;

import java.util.List;

/**
 * The introspected shape of a whole database catalog (documentation portal v3): every user table and
 * view, with the columns, primary key, foreign keys, and unique indexes the portal's schema view
 * renders. It is the catalog-wide counterpart to the single-table {@link TableSchema} the CRUD
 * scaffolder works from.
 *
 * <p>Tables are sorted by name and columns follow ordinal position (see {@link CatalogIntrospector}),
 * so the model — and the {@code schema.json} built from it — is deterministic for a given database
 * state.
 *
 * @param tables every table and view in the connection's current schema, sorted by name
 */
public record CatalogSchema(List<Table> tables) {

    public CatalogSchema {
        tables = List.copyOf(tables);
    }

    /**
     * One introspected table or view.
     *
     * @param name          the object name as the database reports it
     * @param type          {@code TABLE} or {@code VIEW}
     * @param schema        the database schema/namespace the object lives in, may be {@code null}
     * @param columns       the columns in ordinal order
     * @param primaryKey    the primary-key column names in key-sequence order (empty for views/none)
     * @param foreignKeys   the imported foreign keys, sorted by constraint name
     * @param uniqueIndexes the unique indexes excluding the one backing the primary key, sorted by name
     */
    public record Table(String name, String type, String schema, List<Column> columns,
            List<String> primaryKey, List<ForeignKey> foreignKeys, List<Index> uniqueIndexes) {

        public Table {
            columns = List.copyOf(columns);
            primaryKey = List.copyOf(primaryKey);
            foreignKeys = List.copyOf(foreignKeys);
            uniqueIndexes = List.copyOf(uniqueIndexes);
        }
    }

    /**
     * One introspected column.
     *
     * @param name          the column name as the database reports it
     * @param jdbcType      the {@link java.sql.Types} code
     * @param sqlTypeName   the database-specific type name (informational)
     * @param size          the column size (length for character types, precision for numerics)
     * @param nullable      whether the column accepts NULL
     * @param autoincrement whether the database generates the value (identity / auto-increment)
     * @param defaultValue  the declared default expression, or {@code null} when there is none
     */
    public record Column(String name, int jdbcType, String sqlTypeName, int size, boolean nullable,
            boolean autoincrement, String defaultValue) {
    }

    /**
     * One imported foreign key.
     *
     * @param name       the constraint name
     * @param columns    the referencing columns, in key-sequence order
     * @param refTable   the referenced table
     * @param refColumns the referenced columns, aligned with {@code columns}
     */
    public record ForeignKey(String name, List<String> columns, String refTable,
            List<String> refColumns) {

        public ForeignKey {
            columns = List.copyOf(columns);
            refColumns = List.copyOf(refColumns);
        }
    }

    /**
     * One index.
     *
     * @param name    the index name
     * @param columns the indexed columns, in ordinal order
     * @param unique  whether the index enforces uniqueness
     */
    public record Index(String name, List<String> columns, boolean unique) {

        public Index {
            columns = List.copyOf(columns);
        }
    }
}
