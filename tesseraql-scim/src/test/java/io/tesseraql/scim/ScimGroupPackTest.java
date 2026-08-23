package io.tesseraql.scim;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The bundled managed Group contract set (docs/contract-sql-execution.md structural decision 6):
 * the statements exist, page per dialect, and — the guard the design names — none of them says
 * {@code returning}, because that is the statement that would work on the maintainer's
 * PostgreSQL and nowhere else.
 */
class ScimGroupPackTest {

    private static final List<String> RESOURCES = List.of("create.sql", "find-by-id.sql",
            "list.sql", "list.oracle.sql", "list.sqlserver.sql", "replace.sql", "delete.sql",
            "list-members.sql", "add-member.sql", "remove-member.sql", "count.sql");

    @Test
    void theBundledSetIsCompleteAndDeclaresNoKeys() {
        ScimGroupContract contract = ScimGroupPack.contract(null);

        assertThat(List.of(contract.createSql(), contract.findByIdSql(), contract.listSql(),
                contract.replaceSql(), contract.deleteSql(), contract.listMembersSql(),
                contract.addMemberSql(), contract.removeMemberSql(), contract.countSql()))
                .allSatisfy(sql -> assertThat(sql).isNotBlank());
        // The id is minted, not generated: a supplied varchar has nothing for a driver to
        // hand back, so the bundled set declares no key columns.
        assertThat(contract.keys()).isEmpty();
        assertThat(ScimGroupPack.idSupplier().get()).startsWith("grp-");
    }

    @Test
    void paginationIsTheOnlyDialectVariant() {
        assertThat(ScimGroupPack.contract(null).listSql()).contains("limit");
        assertThat(ScimGroupPack.contract("postgres").listSql()).contains("limit");
        assertThat(ScimGroupPack.contract("mysql").listSql()).contains("limit");
        assertThat(ScimGroupPack.contract("oracle").listSql()).contains("fetch next");
        assertThat(ScimGroupPack.contract("sqlserver").listSql()).contains("fetch next");
        // Everything else is one statement for all four dialects.
        assertThat(ScimGroupPack.contract("oracle").createSql())
                .isEqualTo(ScimGroupPack.contract("mysql").createSql());
    }

    @Test
    void noBundledStatementSaysReturning() throws IOException {
        for (String resource : RESOURCES) {
            assertThat(read(resource).toLowerCase(Locale.ROOT))
                    .as("%s must stay a plain statement — `returning` exists only on"
                            + " PostgreSQL and Oracle", resource)
                    .doesNotContain("returning");
        }
    }

    @Test
    void theCodeAndTheNameBothComeFromDisplayName() throws IOException {
        String create = read("create.sql");
        String replace = read("replace.sql");

        // Structural decision 6: group_code is what assignment rules join on, so it follows
        // the name an administrator recognises — on create and on rename alike.
        assertThat(create).contains("group_code").contains("group_name");
        assertThat(replace).contains("group_code").contains("group_name");
        assertThat(create.split("/\\* displayName \\*/")).hasSize(3);
        assertThat(replace.split("/\\* displayName \\*/")).hasSize(3);
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = ScimGroupPack.class.getResourceAsStream(
                "/io/tesseraql/scim/pack/groups/" + resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
