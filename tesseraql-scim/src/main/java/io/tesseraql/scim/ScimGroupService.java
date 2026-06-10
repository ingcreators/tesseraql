package io.tesseraql.scim;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Executes SCIM inbound provisioning for groups against the {@link ScimGroupContract} SQL
 * (design ch. 10.15): create, look up, list, delete, replace, and PATCH. A group's own row and its
 * membership are rendered through separate statements so members can change without rewriting the
 * group.
 */
public final class ScimGroupService {

    private final DataSource dataSource;
    private final ScimGroupContract contract;

    public ScimGroupService(DataSource dataSource, ScimGroupContract contract) {
        this.dataSource = dataSource;
        this.contract = contract;
    }

    /** Creates a group (and any members supplied), returning the persisted resource. */
    public ScimGroup create(ScimGroup group) {
        try {
            Map<String, Object> row = ScimSql.queryOne(dataSource, contract.createSql(),
                    ScimGroupMapper.toParams(group));
            String id = row == null ? group.id() : string(row.get("id"));
            for (ScimGroup.Member member : group.members()) {
                addMember(id, member.value());
            }
            return findById(id).orElseThrow(
                    () -> new ScimException(500, null, "Group vanished after create: " + id));
        } catch (SQLException ex) {
            if (ex.getSQLState() != null && ex.getSQLState().startsWith("23")) {
                throw new ScimException(409, "uniqueness",
                        "Group already exists: " + group.displayName());
            }
            throw new ScimException(500, null, "SCIM group create failed: " + ex.getMessage());
        }
    }

    /** Looks up a group (with its members) by service-provider id. */
    public Optional<ScimGroup> findById(String id) {
        try {
            Map<String, Object> row = ScimSql.queryOne(dataSource, contract.findByIdSql(),
                    Map.of("id", id));
            return row == null ? Optional.empty()
                    : Optional.of(ScimGroupMapper.fromRow(row, members(id)));
        } catch (SQLException ex) {
            throw new ScimException(500, null, "SCIM group lookup failed: " + ex.getMessage());
        }
    }

    /** Lists a page of groups (with members); {@code startIndex} is 1-based per SCIM. */
    public ScimListResponse<ScimGroup> list(int startIndex, int count) {
        try {
            List<Map<String, Object>> rows = ScimSql.queryAll(dataSource, contract.listSql(),
                    Map.of("startIndex", startIndex, "count", count));
            List<ScimGroup> groups = rows.stream()
                    .map(row -> ScimGroupMapper.fromRow(row, members(string(row.get("id")))))
                    .toList();
            return ScimListResponse.of(groups, total(groups.size()), startIndex);
        } catch (SQLException ex) {
            throw new ScimException(500, null, "SCIM group list failed: " + ex.getMessage());
        }
    }

    /** Total groups from the count contract SQL, or {@code fallback} when none is configured. */
    private int total(int fallback) throws SQLException {
        if (contract.countSql() == null || contract.countSql().isBlank()) {
            return fallback;
        }
        return ScimCount.toInt(ScimSql.queryOne(dataSource, contract.countSql(), Map.of()), fallback);
    }

    /**
     * Replaces a group by id (RFC 7644 §3.5.1): updates its own attributes and reconciles its
     * membership to exactly the supplied members — adding those that are missing and removing those
     * no longer present (bidirectional). Returns the updated group; 404 when it does not exist.
     */
    public ScimGroup replace(String id, ScimGroup group) {
        try {
            Map<String, Object> params = ScimGroupMapper.toParams(group);
            params.put("id", id);
            Map<String, Object> row = ScimSql.queryOne(dataSource, contract.replaceSql(), params);
            if (row == null) {
                throw new ScimException(404, null, "Group not found: " + id);
            }
            reconcileMembers(id, group.members());
            return findById(id)
                    .orElseThrow(() -> new ScimException(404, null, "Group not found: " + id));
        } catch (SQLException ex) {
            if (ex.getSQLState() != null && ex.getSQLState().startsWith("23")) {
                throw new ScimException(409, "uniqueness",
                        "Group already exists: " + group.displayName());
            }
            throw new ScimException(500, null, "SCIM group replace failed: " + ex.getMessage());
        }
    }

    /** Drives the membership to exactly {@code desired}: adds the missing, removes the surplus. */
    private void reconcileMembers(String id, List<ScimGroup.Member> desired) {
        java.util.Set<String> target = new java.util.LinkedHashSet<>();
        desired.forEach(member -> target.add(member.value()));
        java.util.Set<String> current = new java.util.LinkedHashSet<>();
        members(id).forEach(member -> current.add(member.value()));
        target.stream().filter(value -> !current.contains(value)).forEach(value -> addMember(id, value));
        current.stream().filter(value -> !target.contains(value))
                .forEach(value -> removeMember(id, value));
    }

    /** Deletes a group by id; throws 404 when it does not exist. */
    public void delete(String id) {
        try {
            if (ScimSql.queryOne(dataSource, contract.deleteSql(), Map.of("id", id)) == null) {
                throw new ScimException(404, null, "Group not found: " + id);
            }
        } catch (SQLException ex) {
            throw new ScimException(500, null, "SCIM group delete failed: " + ex.getMessage());
        }
    }

    /**
     * Applies a SCIM PATCH (RFC 7644 §3.5.2) by normalizing it against the current group and replacing:
     * {@code displayName}/{@code externalId} are updated and {@code members} are reconciled (add /
     * replace the whole set / remove by value array or {@code members[value eq "..."]} path filter).
     * Returns the updated group.
     */
    public ScimGroup patch(String id, ScimPatchRequest patch) {
        ScimGroup current = findById(id)
                .orElseThrow(() -> new ScimException(404, null, "Group not found: " + id));
        return replace(id, ScimGroupPatch.apply(current, patch));
    }

    private void addMember(String groupId, String memberId) {
        run(contract.addMemberSql(), groupId, memberId, "add member");
    }

    private void removeMember(String groupId, String memberId) {
        run(contract.removeMemberSql(), groupId, memberId, "remove member");
    }

    private void run(String sql, String groupId, String memberId, String what) {
        try {
            ScimSql.update(dataSource, sql, Map.of("groupId", groupId, "memberId", memberId));
        } catch (SQLException ex) {
            if (ex.getSQLState() != null && ex.getSQLState().startsWith("23")) {
                return; // membership already present / referent absent — keep PATCH idempotent
            }
            throw new ScimException(500, null, "SCIM group " + what + " failed: " + ex.getMessage());
        }
    }

    private List<ScimGroup.Member> members(String groupId) {
        try {
            return ScimSql.queryAll(dataSource, contract.listMembersSql(), Map.of("groupId", groupId))
                    .stream().map(ScimGroupMapper::memberFromRow).toList();
        } catch (SQLException ex) {
            throw new ScimException(500, null, "SCIM group members failed: " + ex.getMessage());
        }
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
