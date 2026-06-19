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
 * {@code /* id *}{@code / 0} (the dummy literal keeps the template runnable in a plain SQL tool) and
 * an {@code in (…)} or optional {@code /*%if *}{@code /} filter is spelled correctly.
 *
 * <p>Each bind references a name the route's {@code sql.params} maps to its source, so the snippet is
 * prefixed with a {@code -- sql.params} comment listing those mappings — each from
 * {@code params.<name>}, the coerced declared inputs (so a typed column binds a typed value, not the
 * raw body string; the route declares the field in {@code input:} for that coercion), matching the
 * {@code scaffold crud} convention. The route author copies the SQL into the {@code .sql} file and
 * the mappings into the route. It is schema-driven: the
 * columns and the {@code where} key come from the table's introspected columns and primary key
 * (identity columns are skipped on insert), and each bind's dummy literal is typed from the column
 * ({@code 0} for a number, {@code false} for a boolean, {@code 'x'} otherwise).
 */
public final class SqlBuilder {

    private SqlBuilder() {
    }

    /** One bind: the name a directive references and the {@code sql.params} source it maps to. */
    private record Bind(String name, String source) {
    }

    /** The 2-way SQL for {@code operation} on {@code table} (no filter column). */
    public static String generate(CatalogSchema.Table table, String operation) {
        return generate(table, operation, null);
    }

    /**
     * The 2-way SQL for {@code operation} on {@code table}; an empty string for an unknown operation.
     * {@code column} is the filter column for the {@code select-by-column*} operations, else ignored.
     */
    public static String generate(CatalogSchema.Table table, String operation, String column) {
        return switch (operation == null ? "" : operation) {
            case "select-by-pk" -> selectByPk(table);
            case "select-by-column" -> selectByColumn(table, column, "eq");
            case "select-by-column-in" -> selectByColumn(table, column, "in");
            case "select-by-column-optional" -> selectByColumn(table, column, "optional");
            case "insert" -> insert(table);
            case "update-by-pk" -> updateByPk(table);
            case "delete-by-pk" -> deleteByPk(table);
            default -> "";
        };
    }

    private static String selectByPk(CatalogSchema.Table table) {
        List<Bind> binds = new ArrayList<>();
        String where = keyPredicate(table, binds);
        return render(binds,
                "select " + projection(table) + "\nfrom " + table.name() + "\nwhere " + where
                        + ";\n");
    }

    /** A select filtered by one column: an equality ({@code eq}), an {@code in} list, or optional. */
    private static String selectByColumn(CatalogSchema.Table table, String column, String mode) {
        String filter = column == null ? "" : column.strip();
        String head = "select " + projection(table) + "\nfrom " + table.name();
        if (filter.isEmpty()) {
            return head + "\nwhere /* TODO: pick a column */;\n";
        }
        String dummy = dummy(typeOf(table, filter));
        String where = switch (mode) {
            case "in" -> filter + " in /* " + filter + " */ (" + dummy + ")";
            case "optional" -> "1 = 1\n/*%if " + filter + " != null */\n  and " + filter + " = /* "
                    + filter + " */ " + dummy + "\n/*%end*/";
            default -> filter + " = /* " + filter + " */ " + dummy;
        };
        return render(List.of(new Bind(filter, "params." + filter)),
                head + "\nwhere " + where + ";\n");
    }

    private static String insert(CatalogSchema.Table table) {
        List<Bind> binds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (CatalogSchema.Column column : table.columns()) {
            if (column.autoincrement()) {
                continue; // the database generates identity/serial columns
            }
            names.add(column.name());
            values.add("/* " + column.name() + " */ " + dummy(column.jdbcType()));
            binds.add(new Bind(column.name(), "params." + column.name()));
        }
        if (names.isEmpty()) {
            return "insert into " + table.name() + " (/* TODO: columns */)\nvalues ();\n";
        }
        return render(binds, "insert into " + table.name() + " (" + String.join(", ", names)
                + ")\nvalues (" + String.join(", ", values) + ");\n");
    }

    private static String updateByPk(CatalogSchema.Table table) {
        Set<String> key = new LinkedHashSet<>(table.primaryKey());
        List<Bind> binds = new ArrayList<>();
        List<String> sets = new ArrayList<>();
        for (CatalogSchema.Column column : table.columns()) {
            if (key.contains(column.name()) || column.autoincrement()) {
                continue;
            }
            sets.add(column.name() + " = /* " + column.name() + " */ " + dummy(column.jdbcType()));
            binds.add(new Bind(column.name(), "params." + column.name()));
        }
        String setClause = sets.isEmpty() ? "/* TODO: columns */" : String.join(",\n  ", sets);
        String where = keyPredicate(table, binds);
        return render(binds,
                "update " + table.name() + "\nset " + setClause + "\nwhere " + where + ";\n");
    }

    private static String deleteByPk(CatalogSchema.Table table) {
        List<Bind> binds = new ArrayList<>();
        String where = keyPredicate(table, binds);
        return render(binds, "delete from " + table.name() + "\nwhere " + where + ";\n");
    }

    /** The {@code where} predicate over the primary key (each bound from {@code params}), or a TODO. */
    private static String keyPredicate(CatalogSchema.Table table, List<Bind> binds) {
        if (table.primaryKey().isEmpty()) {
            return "1 = 1 /* TODO: add a key predicate */";
        }
        List<String> predicates = new ArrayList<>();
        for (String key : table.primaryKey()) {
            predicates.add(key + " = /* " + key + " */ " + dummy(typeOf(table, key)));
            binds.add(new Bind(key, "params." + key));
        }
        return String.join("\n  and ", predicates);
    }

    /** The comma-separated list of every column name, for a select projection. */
    private static String projection(CatalogSchema.Table table) {
        List<String> columns = new ArrayList<>();
        table.columns().forEach(column -> columns.add(column.name()));
        return String.join(", ", columns);
    }

    /** Prefixes the SQL with the {@code sql.params} mapping each bind needs (none -> just the SQL). */
    private static String render(List<Bind> binds, String sql) {
        if (binds.isEmpty()) {
            return sql;
        }
        StringBuilder out = new StringBuilder("-- sql.params (add to the route's sql block):\n");
        for (Bind bind : binds) {
            out.append("--   ").append(bind.name()).append(": ").append(bind.source()).append('\n');
        }
        return out.append(sql).toString();
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
