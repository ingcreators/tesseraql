package io.tesseraql.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The managed schema is the same set of tables on every dialect (docs/access-governance.md
 * "Guards").
 *
 * <p>Adding a table means adding it four times, and the campaign that wrote this guard forgot a
 * dialect three times by hand — each time invisibly, because the tests all ran on PostgreSQL and
 * a missing table on Oracle looks exactly like a store that has not been upgraded yet. The
 * comparison is cheap and it is the only thing standing between a forgotten paste and a
 * deployment discovering the gap in production.
 */
class IdentitySchemaParityTest {

    private static final List<String> DIALECTS = List.of("postgres", "mysql", "oracle",
            "sqlserver");

    /** {@code create table [if not exists] <name> (} on every dialect's spelling. */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?im)^\\s*create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-z0-9_]+)");

    @Test
    void everyDialectDeclaresTheSameTables() {
        Set<String> reference = tables("postgres");
        assertThat(reference).contains("tql_users", "tql_roles", "tql_role_conditions");
        for (String dialect : DIALECTS) {
            assertThat(tables(dialect))
                    .as("%s declares the same tables as postgres", dialect)
                    .containsExactlyInAnyOrderElementsOf(reference);
        }
    }

    /**
     * SCIM's {@code externalId} lives in {@code tql_groups.external_id}
     * (docs/contract-sql-execution.md structural decision 6); a column added by hand to four
     * files is the same forgotten-paste risk as a table, so the guard names it.
     */
    @Test
    void everyDialectCarriesTheGroupExternalIdColumn() {
        for (String dialect : DIALECTS) {
            assertThat(DefaultIdentityPack.schema(dialect))
                    .as("%s declares tql_groups.external_id", dialect)
                    .contains("external_id");
        }
    }

    private static Set<String> tables(String dialect) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = CREATE_TABLE.matcher(DefaultIdentityPack.schema(dialect));
        while (matcher.find()) {
            names.add(matcher.group(1).toLowerCase(java.util.Locale.ROOT));
        }
        return names;
    }
}
