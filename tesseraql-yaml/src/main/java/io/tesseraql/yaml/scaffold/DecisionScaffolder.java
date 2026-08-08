package io.tesseraql.yaml.scaffold;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.Map;

/**
 * Scaffolds a table-backed decision (docs/decision-tables.md "Scaffolder and gallery
 * adoption"): the {@code decisions/} declaration and the typed backing-table migration —
 * columns derived from the contract, one nullable column (or pair, or child table) per input,
 * never a generic EAV table. The maintenance surface is one {@code scaffold crud} run over the
 * generated table; the CLI prints the follow-ups.
 */
public final class DecisionScaffolder {

    /** TQL-DECISION-4730: the scaffold request itself is malformed. */
    private static final TqlErrorCode BAD_REQUEST = new TqlErrorCode(TqlDomain.DECISION, 4730);

    /**
     * Generates the declaration and migration for one decision.
     *
     * @param name    the decision name — used verbatim as the file stem and the
     *                {@code <name>_rules} table prefix (docs/unicode-identifiers.md)
     * @param inputs  input name to match kind ({@code eq}, {@code between}, {@code in},
     *                {@code bool}, {@code subtree}), in declaration order
     * @param outputs output names, in declaration order
     * @param unique  {@code hitPolicy: unique} (no priority column) instead of {@code first}
     * @param effective whether the rows are dated ({@code valid_from}/{@code valid_to})
     * @param migrationVersion the next free {@code V<n>} under {@code db/migration}
     */
    public List<ScaffoldedFile> scaffold(String name, Map<String, String> inputs,
            List<String> outputs, boolean unique, boolean effective, int migrationVersion) {
        if (!io.tesseraql.core.sql.SqlIdentifiers.isIdentifier(name)) {
            throw new TqlException(BAD_REQUEST, "Decision name '" + name
                    + "' must be a plain identifier");
        }
        if (inputs.isEmpty() || outputs.isEmpty()) {
            throw new TqlException(BAD_REQUEST,
                    "A decision needs at least one input and one output");
        }
        for (String field : inputs.keySet()) {
            requireName("input", field);
        }
        for (String field : outputs) {
            requireName("output", field);
        }
        String table = name + "_rules";
        return List.of(
                new ScaffoldedFile("decisions/" + name + ".yml",
                        declaration(name, table, inputs, outputs, unique, effective)),
                new ScaffoldedFile("db/migration/V" + migrationVersion + "__decision_"
                        + name + ".sql",
                        migration(name, table, inputs, outputs, unique, effective)));
    }

    private static void requireName(String what, String field) {
        if (!io.tesseraql.core.sql.SqlIdentifiers.isIdentifier(field)) {
            throw new TqlException(BAD_REQUEST, "Decision " + what + " '" + field
                    + "' must be a plain identifier");
        }
    }

    private String declaration(String name, String table, Map<String, String> inputs,
            List<String> outputs, boolean unique, boolean effective) {
        StringBuilder yml = new StringBuilder("""
                # The %s decision (docs/decision-tables.md): the contract routes reference with
                # decide:, backed by the %s table business users maintain at runtime. A NULL
                # cell in any mapped column is the wildcard. Tighten the generated types and
                # enums to the business vocabulary — an enum-typed output buys the
                # exhaustiveness lints.
                version: tesseraql/v1

                decisions:
                  %s:
                    inputs:
                """.formatted(name, table, name));
        inputs.forEach((input, kind) -> yml.append("      ").append(input).append(": { type: ")
                .append(inputType(kind)).append(", match: ").append(kind).append(" }\n"));
        yml.append("    outputs:\n");
        outputs.forEach(output -> yml.append("      ").append(output)
                .append(": { type: string }\n"));
        yml.append("    hitPolicy: ").append(unique ? "unique" : "first").append('\n');
        yml.append("    source:\n      table: ").append(table).append("\n      match:\n");
        inputs.forEach((input, kind) -> {
            switch (kind) {
                case "between" -> yml.append("        ").append(input).append(": { between: [")
                        .append(input).append("_min, ").append(input)
                        .append("_max] }\n");
                case "subtree" -> yml.append("        ").append(input)
                        .append(": { subtree: ").append(input).append("_unit }\n");
                case "in" -> {
                    // in inputs map under set:, appended below.
                }
                default -> yml.append("        ").append(input).append(": { eq: ")
                        .append(input).append(" }\n");
            }
        });
        if (inputs.containsValue("in")) {
            yml.append("      set:\n");
            inputs.forEach((input, kind) -> {
                if ("in".equals(kind)) {
                    yml.append("        ").append(input).append(": { table: ").append(table)
                            .append('_').append(input).append(", key: rule_id, value: ")
                            .append(input).append(" }\n");
                }
            });
        }
        if (!unique) {
            yml.append("      priority: priority\n");
        }
        if (effective) {
            yml.append("      effective: [valid_from, valid_to]\n");
        }
        yml.append("      outputs:\n");
        outputs.forEach(output -> yml.append("        ").append(output).append(": ")
                .append(output).append('\n'));
        return yml.toString();
    }

    private String migration(String name, String table, Map<String, String> inputs,
            List<String> outputs, boolean unique, boolean effective) {
        StringBuilder sql = new StringBuilder("-- The " + name
                + " decision's backing table (docs/decision-tables.md): one nullable column"
                + " per cell,\n-- NULL = wildcard. Run `tesseraql scaffold crud --table "
                + table + "` for the maintenance surface.\n");
        sql.append("create table ").append(table).append(" (\n");
        sql.append("  id bigint primary key,\n");
        inputs.forEach((input, kind) -> {
            switch (kind) {
                case "between" -> sql.append("  ").append(input)
                        .append("_min numeric,\n  ").append(input)
                        .append("_max numeric,\n");
                case "bool" -> sql.append("  ").append(input).append(" boolean,\n");
                case "subtree" -> sql.append("  ").append(input)
                        .append("_unit varchar(40),\n");
                case "in" -> {
                    // in inputs live in the child table below.
                }
                default -> sql.append("  ").append(input).append(" varchar(100),\n");
            }
        });
        if (effective) {
            sql.append("  valid_from timestamp,\n  valid_to timestamp,\n");
        }
        if (!unique) {
            sql.append("  priority int not null,\n");
        }
        for (int i = 0; i < outputs.size(); i++) {
            sql.append("  ").append(outputs.get(i)).append(" varchar(100) not null")
                    .append(i < outputs.size() - 1 ? ",\n" : "\n");
        }
        sql.append(");\n");
        inputs.forEach((input, kind) -> {
            if ("in".equals(kind)) {
                sql.append("\n-- Membership rows of the ").append(input)
                        .append(" cell: no rows = wildcard.\ncreate table ").append(table)
                        .append('_').append(input).append(" (\n  rule_id bigint not null")
                        .append(" references ").append(table).append("(id),\n  ")
                        .append(input).append(" varchar(100) not null\n);\n");
            }
        });
        return sql.toString();
    }

    private static String inputType(String kind) {
        return switch (kind) {
            case "between" -> "number";
            case "bool" -> "boolean";
            case "eq", "in", "subtree" -> "string";
            default -> throw new TqlException(BAD_REQUEST, "Unknown match kind '" + kind
                    + "' — one of eq, between, in, bool, subtree");
        };
    }

}
