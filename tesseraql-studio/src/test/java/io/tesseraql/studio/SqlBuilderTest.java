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
    void selectByPkProjectsEveryColumnAndBindsTheKeyFromParams() {
        assertThat(SqlBuilder.generate(USERS, "select-by-pk")).isEqualTo("""
                select id, name, active
                from users
                where id = /* params.id */ 0;
                """);
    }

    @Test
    void selectByColumnFiltersOnTheChosenColumnWithATypedBind() {
        assertThat(SqlBuilder.generate(USERS, "select-by-column", "name")).isEqualTo("""
                select id, name, active
                from users
                where name = /* params.name */ 'x';
                """);
        // A boolean filter column gets a boolean dummy.
        assertThat(SqlBuilder.generate(USERS, "select-by-column", "active"))
                .contains("where active = /* params.active */ false;");
        // No column chosen yet -> a TODO predicate to fill in.
        assertThat(SqlBuilder.generate(USERS, "select-by-column", ""))
                .contains("where /* TODO: pick a column */;");
    }

    @Test
    void insertSkipsIdentityColumnsAndBindsFromBodyWithTypedDummies() {
        assertThat(SqlBuilder.generate(USERS, "insert")).isEqualTo("""
                insert into users (name, active)
                values (/* body.name */ 'x', /* body.active */ false);
                """);
    }

    @Test
    void updateByPkSetsNonKeyColumnsAndFiltersByKey() {
        assertThat(SqlBuilder.generate(USERS, "update-by-pk")).isEqualTo("""
                update users
                set name = /* body.name */ 'x',
                  active = /* body.active */ false
                where id = /* params.id */ 0;
                """);
    }

    @Test
    void deleteByPkFiltersByKey() {
        assertThat(SqlBuilder.generate(USERS, "delete-by-pk"))
                .isEqualTo("delete from users\nwhere id = /* params.id */ 0;\n");
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
