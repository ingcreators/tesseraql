package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.util.SqlScripts;
import io.tesseraql.identity.DefaultIdentityPack;
import io.tesseraql.scim.ScimException;
import io.tesseraql.scim.ScimGroup;
import io.tesseraql.scim.ScimGroupPack;
import io.tesseraql.scim.ScimGroupService;
import io.tesseraql.scim.ScimListResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;

/**
 * Shared assertion for the dialect portability tests (docs/contract-sql-execution.md structural
 * decision 6): the bundled managed Group contract set creates, reads, pages, renames, reconciles
 * and deletes against this dialect's managed schema. "Portable" is the whole claim of the set —
 * it exists <em>because</em> MySQL and SQL Server could not be served before — and the only way
 * to know is to run it.
 */
final class DialectScimGroupChecks {

    private DialectScimGroupChecks() {
    }

    static void bundledGroupSetRoundTrip(DataSource dataSource, String dialect) throws Exception {
        ensureIdentitySchema(dataSource, dialect);
        ScimGroupService groups = new ScimGroupService(dataSource,
                ScimGroupPack.contract(dialect))
                .idSupplier(ScimGroupPack.idSupplier())
                .dialect(dialect);

        ScimGroup created = groups.create(new ScimGroup(null, null, "scim-ext-9",
                "scim-pack-engineers", List.of(
                        new ScimGroup.Member("u-alpha", null, null),
                        new ScimGroup.Member("u-beta", null, null))));
        assertThat(created.id()).startsWith("grp-");
        assertThat(created.displayName()).isEqualTo("scim-pack-engineers");
        assertThat(created.externalId()).isEqualTo("scim-ext-9");
        assertThat(created.members()).extracting(ScimGroup.Member::value)
                .containsExactlyInAnyOrder("u-alpha", "u-beta");
        // Structural decision 6: the code an assignment rule joins on follows the display name.
        assertThat(groupCode(dataSource, created.id())).isEqualTo("scim-pack-engineers");

        assertThat(groups.findById(created.id())).isPresent();
        ScimListResponse<ScimGroup> page = groups.list(1, 100);
        assertThat(page.resources()).extracting(ScimGroup::id).contains(created.id());

        ScimGroup replaced = groups.replace(created.id(), new ScimGroup(null, created.id(),
                "scim-ext-9", "scim-pack-renamed", List.of(
                        new ScimGroup.Member("u-beta", null, null),
                        new ScimGroup.Member("u-gamma", null, null))));
        assertThat(replaced.displayName()).isEqualTo("scim-pack-renamed");
        assertThat(replaced.members()).extracting(ScimGroup.Member::value)
                .containsExactlyInAnyOrder("u-beta", "u-gamma");
        assertThat(groupCode(dataSource, created.id())).isEqualTo("scim-pack-renamed");

        groups.delete(created.id());
        assertThat(groups.findById(created.id())).isEmpty();
        assertThatThrownBy(() -> groups.delete(created.id()))
                .isInstanceOf(ScimException.class);
    }

    /**
     * Applies the managed identity schema, tolerating a container where another check already
     * did: PostgreSQL and MySQL say {@code if not exists}, Oracle and SQL Server answer
     * "name already used", and either answer means the schema is there.
     */
    private static void ensureIdentitySchema(DataSource dataSource, String dialect)
            throws SQLException {
        // Tolerated application instead of a swallow-everything loop: only the known
        // already-exists errors pass; a genuinely broken statement still fails the test.
        SqlScripts.applyScript(dataSource, DefaultIdentityPack.schema(dialect));
    }

    private static String groupCode(DataSource dataSource, String groupId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select group_code from tql_groups where group_id = ?")) {
            statement.setString(1, groupId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }
}
