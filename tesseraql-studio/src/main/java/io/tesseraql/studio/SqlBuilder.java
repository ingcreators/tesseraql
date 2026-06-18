package io.tesseraql.studio;

import io.tesseraql.yaml.scaffold.CatalogSchema;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates a 2-way SQL DML snippet for one operation on an introspected table (Studio backlog:
 * migration authoring follow-on — the 2-way SQL builder). It pairs with the schema portal: pick a
 * table and an operation and it writes the {@code select}/{@code insert}/{@code update}/{@code delete}
 * with the right <em>2-way directives</em> — the part that is fiddly to hand-write — so a bind reads
 * {@code /* params.id *}{@code / 0} (the dummy literal keeps the template runnable in a plain SQL
 * tool) rather than a bare {@code ?}.
 *
 * <p>It is schema-driven: the projected/inserted/updated columns and the {@code where} key come from
 * the table's introspected columns and primary key, and each bind's dummy literal is typed from the
 * column ({@code 0} for a number, {@code false} for a boolean, {@code 'x'} otherwise). It is a
 * starting point dropped into the route's {@code .sql} file to review and refine.
 */
public final class SqlBuilder {

    private SqlBuilder() {
    }

    /** The 2-way SQL for {@code operation} on {@code table}; an empty string for an unknown operation. */
    public static String generate(CatalogSchema.Table table, String operation) {
        return switch (operation == null ? "" : operation) {
            case "select-by-pk" -> selectByPk(table);
            case "insert" -> insert(table);
            case "update-by-pk" -> updateByPk(table);
            case "delete-by-pk" -> deleteByPk(table);
            default -> "";
        };
    }

    private static String selectByPk(CatalogSchema.Table table) {
        List<String> columns = new ArrayList<>();
        table.columns().forEach(column -> columns.add(column.name()));
        return "select " + String.join(", ", columns) + "\nfrom " + table.name() + "\nwhere "
                + keyPredicate(table) + ";\n";
    }

    private static String insert(CatalogSchema.Table table) {
        List<String> names = new ArrayList<>();
        List<String> binds = new ArrayList<>();
        for (CatalogSchema.Column column : table.columns()) {
            if (column.autoincrement()) {
                continue; // the database generates identity/serial columns
            }
            names.add(column.name());
            binds.add("/* body." + column.name() + " */ " + dummy(column.jdbcType()));
        }
        if (names.isEmpty()) {
            return "insert into " + table.name() + " (/* TODO: columns */)\nvalues ();\n";
        }
        return "insert into " + table.name() + " (" + String.join(", ", names) + ")\nvalues ("
                + String.join(", ", binds) + ");\n";
    }

    private static String updateByPk(CatalogSchema.Table table) {
        Set<String> key = new LinkedHashSet<>(table.primaryKey());
        List<String> sets = new ArrayList<>();
        for (CatalogSchema.Column column : table.columns()) {
            if (key.contains(column.name()) || column.autoincrement()) {
                continue;
            }
            sets.add(column.name() + " = /* body." + column.name() + " */ "
                    + dummy(column.jdbcType()));
        }
        String setClause = sets.isEmpty() ? "/* TODO: columns */" : String.join(",\n  ", sets);
        return "update " + table.name() + "\nset " + setClause + "\nwhere " + keyPredicate(table)
                + ";\n";
    }

    private static String deleteByPk(CatalogSchema.Table table) {
        return "delete from " + table.name() + "\nwhere " + keyPredicate(table) + ";\n";
    }

    /** The {@code where} predicate over the primary key (bound from {@code params}), or a TODO. */
    private static String keyPredicate(CatalogSchema.Table table) {
        if (table.primaryKey().isEmpty()) {
            return "1 = 1 /* TODO: add a key predicate */";
        }
        List<String> predicates = new ArrayList<>();
        for (String key : table.primaryKey()) {
            predicates.add(key + " = /* params." + key + " */ " + dummy(typeOf(table, key)));
        }
        return String.join("\n  and ", predicates);
    }

    private static int typeOf(CatalogSchema.Table table, String column) {
        return table.columns().stream().filter(c -> c.name().equals(column)).findFirst()
                .map(CatalogSchema.Column::jdbcType).orElse(Types.VARCHAR);
    }

    /** A runnable dummy literal for a bind, typed from the column so the template runs as plain SQL. */
    private static String dummy(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.DECIMAL,
                    Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE ->
                "0";
            case Types.BOOLEAN, Types.BIT -> "false";
            default -> "'x'";
        };
    }
}
