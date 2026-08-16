package io.tesseraql.core.migration;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;

/**
 * The Flyway history table an application's migrations record into: {@code tql_schema_history_<app>}
 * (design ch. 31, 32).
 *
 * <p>One derivation, in core, because there are three entry points into migrating an application —
 * the runtime at mount time, {@code tesseraql migrate}, and the {@code tesseraql:migrate} Maven goal
 * — and they cannot disagree about which table holds the history. They did: the runtime keyed on
 * {@code tesseraql.app.name}, the CLI on the application's directory name and the goal on
 * {@code ${project.artifactId}}, so a CLI migration wrote a history the runtime ignored and re-ran
 * everything under its own. The name itself is resolved from configuration by the caller (the yaml
 * module owns that); this class owns what the name becomes and whether the result can be stored.
 */
public final class SchemaHistory {

    /**
     * TQL-APP-4208: the history table name does not fit the database's identifier limit.
     *
     * <p>A refusal rather than a truncation, because truncation is what the database does and it
     * does it silently. Measured against PostgreSQL 16: a 109-byte table name was stored as 61
     * bytes by {@code create table} with no error and no warning, so two applications whose names
     * share a long prefix would record into one history table — each reading the other's applied
     * versions, and {@code validate} reporting success over the mixture.
     */
    private static final TqlErrorCode NAME_TOO_LONG = new TqlErrorCode(TqlDomain.APP, 4208);

    /** The prefix every history table carries, so the framework's tables are recognisable. */
    public static final String PREFIX = "tql_schema_history_";

    private SchemaHistory() {
    }

    /** The history table for an application's {@code main} migrations. */
    public static String table(String historyName) {
        return PREFIX + sanitize(historyName);
    }

    /**
     * The history table for {@code datasource}'s migration set — {@code main} records into
     * {@link #table(String)}, a named datasource into its own suffixed table.
     */
    public static String table(String historyName, String datasource) {
        return "main".equals(datasource)
                ? table(historyName)
                : table(historyName) + "__" + sanitize(datasource);
    }

    /**
     * Refuses a table name the database would silently truncate.
     *
     * <p>The comparison is <b>UTF-8 bytes against the driver's reported maximum</b>, not characters.
     * PostgreSQL reports 63 and enforces 63 <em>bytes</em>: a 49-character Japanese name passes a
     * character count and is still cut in half, which is how an ASCII-only reading of this limit
     * would reintroduce the collision {@link #sanitize} already had to be fixed for. A driver
     * reporting {@code 0} declares no limit it knows of, and nothing is checked.
     */
    public static void requireFits(String table, DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            requireFits(table, connection.getMetaData().getMaxTableNameLength());
        } catch (SQLException unreachable) {
            // Not this method's failure to report: the migration itself is about to say so.
        }
    }

    /**
     * The check itself, against a limit already read. Separate so the rule is testable without a
     * database — the rule is arithmetic and the interesting cases are multi-byte names.
     *
     * @param limit the driver's reported maximum; {@code 0} means it declares none
     */
    static void requireFits(String table, int limit) {
        int bytes = table.getBytes(StandardCharsets.UTF_8).length;
        if (limit > 0 && bytes > limit) {
            throw new TqlException(NAME_TOO_LONG, "Migration history table '" + table + "' is "
                    + bytes + " bytes and this database allows " + limit
                    + "; the database would truncate it silently and share the history with any"
                    + " other application truncating to the same name. Set"
                    + " tesseraql.migrations.historyName to a shorter value.");
        }
    }

    /**
     * The identifier-safe form of a name.
     *
     * <p>Unicode letters survive (docs/unicode-identifiers.md) — an ASCII-only class mapped every
     * Japanese application name to underscores, so two applications shared one history table.
     */
    private static String sanitize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}_]", "_");
    }
}
