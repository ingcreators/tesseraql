package io.tesseraql.operations.org;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.org.OrgUnitStore;
import io.tesseraql.core.util.SqlScripts;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * JDBC-backed {@link OrgUnitStore} (roadmap Phase 29 slice 2): the managed {@code tql_org_unit}
 * hierarchy and its {@code tql_org_closure} transitive closure. The closure is recomputed from the
 * parent graph in Java and rewritten wholesale, so {@link #rebuildClosure} is dialect-agnostic (no
 * recursive CTE) and the hot-path subtree SELECT a scope fragment issues stays a plain, portable
 * {@code in (select … from tql_org_closure …)}.
 */
public final class JdbcOrgUnitStore implements OrgUnitStore {

    /** TQL-BATCH-5314: the org-unit store could not complete an operation. */
    private static final TqlErrorCode STORE_ERROR = new TqlErrorCode(TqlDomain.BATCH, 5314);
    /** Defensive bound on a closure walk, so an accidental parent cycle cannot loop forever. */
    private static final int MAX_DEPTH = 1024;

    private final DataSource dataSource;

    public JdbcOrgUnitStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates {@code tql_org_unit} and {@code tql_org_closure} (per dialect) if they do not exist. */
    public void ensureSchema() {
        try {
            SqlScripts.applyForVendor(dataSource, JdbcOrgUnitStore.class,
                    "/tesseraql/db/migration/orgunit/V1__org_unit.sql");
        } catch (SQLException ex) {
            throw error("Failed to create org-unit schema", ex);
        }
    }

    @Override
    public void upsert(OrgUnit unit) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement update = connection.prepareStatement(
                    "update tql_org_unit set parent_id = ?, name = ?, tenant_id = ? "
                            + "where unit_id = ?")) {
                update.setString(1, unit.parentId());
                update.setString(2, unit.name());
                update.setString(3, unit.tenantId());
                update.setString(4, unit.id());
                if (update.executeUpdate() > 0) {
                    return;
                }
            }
            // Portable upsert: update first, insert only when no row matched (no ON CONFLICT/MERGE).
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into tql_org_unit (unit_id, parent_id, name, tenant_id) "
                            + "values (?, ?, ?, ?)")) {
                insert.setString(1, unit.id());
                insert.setString(2, unit.parentId());
                insert.setString(3, unit.name());
                insert.setString(4, unit.tenantId());
                insert.executeUpdate();
            }
        } catch (SQLException ex) {
            throw error("Failed to upsert org unit '" + unit.id() + "'", ex);
        }
    }

    @Override
    public void delete(String unitId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "delete from tql_org_unit where unit_id = ?")) {
            ps.setString(1, unitId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw error("Failed to delete org unit '" + unitId + "'", ex);
        }
    }

    @Override
    public void rebuildClosure() {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Map<String, String> parents = readParents(connection);
                connection.createStatement().executeUpdate("delete from tql_org_closure");
                try (PreparedStatement insert = connection.prepareStatement(
                        "insert into tql_org_closure (ancestor_id, descendant_id, depth) "
                                + "values (?, ?, ?)")) {
                    for (String descendant : parents.keySet()) {
                        String ancestor = descendant;
                        int depth = 0;
                        Set<String> seen = new HashSet<>();
                        while (ancestor != null && seen.add(ancestor) && depth <= MAX_DEPTH) {
                            insert.setString(1, ancestor);
                            insert.setString(2, descendant);
                            insert.setInt(3, depth);
                            insert.addBatch();
                            ancestor = parents.get(ancestor);
                            depth++;
                        }
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException ex) {
            throw error("Failed to rebuild org-unit closure", ex);
        }
    }

    private static Map<String, String> readParents(Connection connection) throws SQLException {
        Map<String, String> parents = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "select unit_id, parent_id from tql_org_unit");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                parents.put(rs.getString("unit_id"), rs.getString("parent_id"));
            }
        }
        return parents;
    }

    @Override
    public Set<String> descendants(Collection<String> ancestorIds) {
        if (ancestorIds == null || ancestorIds.isEmpty()) {
            return Set.of();
        }
        List<String> ancestors = new ArrayList<>(ancestorIds);
        String placeholders = String.join(", ",
                java.util.Collections.nCopies(ancestors.size(), "?"));
        Set<String> result = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select distinct descendant_id from tql_org_closure "
                                + "where ancestor_id in (" + placeholders + ")")) {
            for (int i = 0; i < ancestors.size(); i++) {
                ps.setString(i + 1, ancestors.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString("descendant_id"));
                }
            }
        } catch (SQLException ex) {
            throw error("Failed to read org-unit descendants", ex);
        }
        return result;
    }

    private static TqlException error(String message, SQLException ex) {
        return TqlException.builder(STORE_ERROR).message(message + ": " + ex.getMessage()).cause(ex)
                .build();
    }
}
