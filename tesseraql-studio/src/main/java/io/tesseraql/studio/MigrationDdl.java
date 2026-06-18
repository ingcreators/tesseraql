package io.tesseraql.studio;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates the DDL for a couple of common migration operations from structured form input (Studio
 * backlog: migration authoring — the form-driven DDL builder). It produces standard SQL that the New
 * migration page drops into the editable DDL field, so the author gets the right
 * {@code ALTER TABLE … ADD COLUMN …} / {@code CREATE [UNIQUE] INDEX …} shape (with {@code NOT NULL}/
 * {@code DEFAULT} placement and a conventional auto index name) without hand-writing it, then reviews
 * and refines it before creating the migration.
 *
 * <p>It is a forgiving helper, not a validator: it trims inputs and rejects only an empty required
 * field or an embedded {@code ;} (so one builder action yields one statement) — it does not enforce
 * identifier syntax, since the generated DDL is shown in the editor for review. The output is
 * dialect-agnostic standard SQL (these two operations are spelled the same across the supported
 * databases).
 */
public final class MigrationDdl {

    private static final TqlErrorCode INVALID = new TqlErrorCode(TqlDomain.STUDIO, 4224);

    private MigrationDdl() {
    }

    /**
     * {@code ALTER TABLE <table> ADD COLUMN <column> <type> [DEFAULT <default>] [NOT NULL];} — the
     * default and the not-null clause are emitted only when applicable.
     */
    public static String addColumn(String table, String column, String type, boolean nullable,
            String defaultValue) {
        StringBuilder ddl = new StringBuilder("ALTER TABLE ").append(required(table, "table"))
                .append(" ADD COLUMN ").append(required(column, "column")).append(' ')
                .append(required(type, "type"));
        String def = oneValue(clean(defaultValue), "default");
        if (!def.isEmpty()) {
            ddl.append(" DEFAULT ").append(def);
        }
        if (!nullable) {
            ddl.append(" NOT NULL");
        }
        return ddl.append(";\n").toString();
    }

    /**
     * {@code CREATE [UNIQUE] INDEX <name> ON <table> (<columns>);} — the comma-separated columns are
     * split and trimmed, and the name defaults to a conventional {@code <table>_<cols>_idx}.
     */
    public static String createIndex(String table, String columns, boolean unique, String name) {
        String t = required(table, "table");
        List<String> cols = new ArrayList<>();
        for (String column : required(columns, "columns").split(",")) {
            String trimmed = column.strip();
            if (!trimmed.isEmpty()) {
                cols.add(oneValue(trimmed, "column"));
            }
        }
        if (cols.isEmpty()) {
            throw new TqlException(INVALID, "An index needs at least one column");
        }
        String index = clean(name).isEmpty()
                ? t + "_" + String.join("_", cols) + "_idx"
                : oneValue(clean(name), "index name");
        return "CREATE " + (unique ? "UNIQUE " : "") + "INDEX " + index + " ON " + t + " ("
                + String.join(", ", cols) + ");\n";
    }

    private static String required(String value, String field) {
        String cleaned = clean(value);
        if (cleaned.isEmpty()) {
            throw new TqlException(INVALID, "A " + field + " is required");
        }
        return oneValue(cleaned, field);
    }

    /** Rejects an embedded {@code ;} so one builder action produces exactly one statement. */
    private static String oneValue(String value, String field) {
        if (value.contains(";")) {
            throw new TqlException(INVALID, "The " + field + " must be a single value (no ';')");
        }
        return value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
