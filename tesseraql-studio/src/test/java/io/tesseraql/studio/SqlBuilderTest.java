package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.scaffold.CatalogSchema;
import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlBuilderTest {

    private static CatalogSchema.Column col(String name, int jdbc, boolean auto) {
        return new CatalogSchema.Column(name, jdbc, "t", 0, true, auto, null);
    }

    // users(id bigint identity PK, name varchar, active boolean)
    private static final CatalogSchema.Table USERS = new CatalogSchema.Table("users", "TABLE",
            "public",
            List.of(col("id", Types.BIGINT, true), col("name", Types.VARCHAR, false),
                    col("active", Types.BOOLEAN, false)),
            List.of("id"), List.of(), List.of());

    @Test
    void selectByPkBindsTheKeyByNameAndDocumentsTheParamsMapping() {
        // Binds reference the param NAME (resolved against sql.params at render), and the snippet
        // documents the mapping the route needs.
        assertThat(SqlBuilder.generate(USERS, "select-by-pk")).isEqualTo("""
                -- sql.params (add to the route's sql block):
                --   id: params.id
                select id, name, active
                from users
                where id = /* id */ 0;
                """);
    }

    @Test
    void selectByColumnSupportsEqualityInListAndOptionalFilters() {
        assertThat(SqlBuilder.generate(USERS, "select-by-column", "name"))
                .contains("--   name: params.name")
                .contains("where name = /* name */ 'x';");
        // IN-list: the directive is followed by a parenthesized typed dummy.
        assertThat(SqlBuilder.generate(USERS, "select-by-column-in", "name"))
                .contains("where name in /* name */ ('x');");
        // Optional: the predicate is wrapped in a /*%if … */ … /*%end*/ so it only applies when set.
        assertThat(SqlBuilder.generate(USERS, "select-by-column-optional", "active")).contains("""
                where 1 = 1
                /*%if active != null */
                  and active = /* active */ false
                /*%end*/;
                """.stripTrailing());
        // No column chosen yet -> a TODO predicate (and no params mapping).
        assertThat(SqlBuilder.generate(USERS, "select-by-column", ""))
                .contains("where /* TODO: pick a column */;").doesNotContain("sql.params");
    }

    @Test
    void insertSkipsIdentityColumnsAndBindsValuesFromTheBody() {
        assertThat(SqlBuilder.generate(USERS, "insert")).isEqualTo("""
                -- sql.params (add to the route's sql block):
                --   name: body.name
                --   active: body.active
                insert into users (name, active)
                values (/* name */ 'x', /* active */ false);
                """);
    }

    @Test
    void updateByPkSetsNonKeyValuesFromBodyAndFiltersByKeyFromParams() {
        assertThat(SqlBuilder.generate(USERS, "update-by-pk")).isEqualTo("""
                -- sql.params (add to the route's sql block):
                --   name: body.name
                --   active: body.active
                --   id: params.id
                update users
                set name = /* name */ 'x',
                  active = /* active */ false
                where id = /* id */ 0;
                """);
    }

    @Test
    void deleteByPkFiltersByKey() {
        assertThat(SqlBuilder.generate(USERS, "delete-by-pk"))
                .contains("--   id: params.id").contains("delete from users")
                .contains("where id = /* id */ 0;");
    }

    @Test
    void aTableWithoutAPrimaryKeyGetsATodoPredicate() {
        CatalogSchema.Table keyless = new CatalogSchema.Table("log", "TABLE", "public",
                List.of(col("message", Types.VARCHAR, false)), List.of(), List.of(), List.of());

        assertThat(SqlBuilder.generate(keyless, "delete-by-pk"))
                .contains("where 1 = 1 /* TODO: add a key predicate */");
        assertThat(SqlBuilder.generate(keyless, "unknown-op")).isEmpty();
    }
}
