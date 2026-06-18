package io.tesseraql.studio;

import io.tesseraql.yaml.scaffold.CatalogSchema;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates migration DDL from the difference between two introspected schemas (Studio backlog:
 * migration authoring — schema-diff generation): a captured <em>baseline</em> {@code schema.json} and
 * the <em>current</em> one. Its use is to capture changes made directly to a database (a column or
 * table added by hand or another tool) back into a migration so the schema stays reproducible.
 *
 * <p>It is additive-and-safe by design: a table or column present in current but not the baseline
 * becomes a real {@code CREATE TABLE} / {@code ALTER TABLE … ADD COLUMN} statement, while a
 * <em>destructive</em> difference — a table or column removed since the baseline — is emitted only as
 * a commented-out {@code -- DROP …} line for the author to review and uncomment deliberately. Column
 * type changes are likewise surfaced as comments (a type change is dialect-specific and risky). The
 * output is a starting point the author reviews and refines before creating the migration, never
 * applied automatically.
 */
public final class SchemaDiff {

    private SchemaDiff() {
    }

    /**
     * The DDL transforming {@code baseline} into {@code current}, across every datasource of
     * {@code current}. Empty when there is no difference; a {@code null} current yields empty.
     */
    public static String generate(SchemaOverlay baseline, SchemaOverlay current) {
        if (current == null) {
            return "";
        }
        StringBuilder ddl = new StringBuilder();
        boolean multipleDatasources = current.datasources().size() > 1;
        for (Map.Entry<String, CatalogSchema> entry : current.datasources().entrySet()) {
            CatalogSchema base = baseline == null ? null : baseline.datasource(entry.getKey());
            String section = diffCatalog(base, entry.getValue());
            if (!section.isEmpty() && multipleDatasources) {
                ddl.append("-- datasource: ").append(entry.getKey()).append('\n');
            }
            ddl.append(section);
        }
        return ddl.toString();
    }

    private static String diffCatalog(CatalogSchema baseline, CatalogSchema current) {
        Map<String, CatalogSchema.Table> base = byName(baseline);
        Map<String, CatalogSchema.Table> cur = byName(current);
        StringBuilder out = new StringBuilder();
        for (CatalogSchema.Table table : current.tables()) {
            CatalogSchema.Table before = base.get(table.name());
            if (before == null) {
                out.append(createTable(table));
            } else {
                out.append(diffTable(before, table));
            }
        }
        for (CatalogSchema.Table table : tables(baseline)) {
            if (!cur.containsKey(table.name())) {
                out.append("-- DROP TABLE ").append(table.name())
                        .append("; (removed since the baseline — review)\n");
            }
        }
        return out.toString();
    }

    private static String createTable(CatalogSchema.Table table) {
        StringBuilder out = new StringBuilder("CREATE TABLE ").append(table.name()).append(" (\n");
        List<String> elements = new java.util.ArrayList<>();
        for (CatalogSchema.Column column : table.columns()) {
            elements.add("  " + columnDef(column));
        }
        if (!table.primaryKey().isEmpty()) {
            elements.add("  PRIMARY KEY (" + String.join(", ", table.primaryKey()) + ")");
        }
        return out.append(String.join(",\n", elements)).append("\n);\n").toString();
    }

    private static String diffTable(CatalogSchema.Table baseline, CatalogSchema.Table current) {
        Set<String> baseColumns = columnNames(baseline);
        Set<String> currentColumns = columnNames(current);
        StringBuilder out = new StringBuilder();
        for (CatalogSchema.Column column : current.columns()) {
            if (!baseColumns.contains(column.name())) {
                out.append("ALTER TABLE ").append(current.name()).append(" ADD COLUMN ")
                        .append(columnDef(column)).append(";\n");
            }
        }
        for (CatalogSchema.Column column : baseline.columns()) {
            if (!currentColumns.contains(column.name())) {
                out.append("-- ALTER TABLE ").append(current.name()).append(" DROP COLUMN ")
                        .append(column.name()).append("; (removed since the baseline — review)\n");
            }
        }
        // A type change between two columns of the same name is surfaced as a comment, not applied.
        Map<String, CatalogSchema.Column> baseByName = columnsByName(baseline);
        for (CatalogSchema.Column column : current.columns()) {
            CatalogSchema.Column before = baseByName.get(column.name());
            if (before != null && !type(before).equals(type(column))) {
                out.append("-- ALTER TABLE ").append(current.name()).append(" ALTER COLUMN ")
                        .append(column.name()).append(" -- type ").append(type(before))
                        .append(" -> ").append(type(column)).append(" (review)\n");
            }
        }
        return out.toString();
    }

    /** A column definition: {@code name type [DEFAULT literal] [NOT NULL]}. */
    private static String columnDef(CatalogSchema.Column column) {
        StringBuilder def = new StringBuilder(column.name()).append(' ').append(type(column));
        String dflt = column.defaultValue();
        // Skip sequence-backed defaults (they tie to a sequence that may not exist yet); keep literals.
        if (dflt != null && !dflt.isBlank() && !dflt.toLowerCase(java.util.Locale.ROOT)
                .contains("nextval")) {
            def.append(" DEFAULT ").append(dflt.strip());
        }
        if (!column.nullable()) {
            def.append(" NOT NULL");
        }
        return def.toString();
    }

    /** A readable column type: the SQL type name, with a length for character types. */
    private static String type(CatalogSchema.Column column) {
        if (column.size() > 0 && isCharacter(column.jdbcType())) {
            return column.sqlTypeName() + "(" + column.size() + ")";
        }
        return column.sqlTypeName();
    }

    private static boolean isCharacter(int jdbcType) {
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR -> true;
            default -> false;
        };
    }

    private static Map<String, CatalogSchema.Table> byName(CatalogSchema catalog) {
        Map<String, CatalogSchema.Table> map = new LinkedHashMap<>();
        for (CatalogSchema.Table table : tables(catalog)) {
            map.put(table.name(), table);
        }
        return map;
    }

    private static List<CatalogSchema.Table> tables(CatalogSchema catalog) {
        return catalog == null ? List.of() : catalog.tables();
    }

    private static Set<String> columnNames(CatalogSchema.Table table) {
        Set<String> names = new LinkedHashSet<>();
        table.columns().forEach(column -> names.add(column.name()));
        return names;
    }

    private static Map<String, CatalogSchema.Column> columnsByName(CatalogSchema.Table table) {
        Map<String, CatalogSchema.Column> map = new LinkedHashMap<>();
        table.columns().forEach(column -> map.put(column.name(), column));
        return map;
    }
}
