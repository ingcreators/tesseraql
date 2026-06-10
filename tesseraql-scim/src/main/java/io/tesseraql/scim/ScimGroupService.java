package io.tesseraql.scim;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Executes SCIM inbound provisioning for groups against the {@link ScimGroupContract} SQL
 * (design ch. 10.15): create, look up, list, delete, and member PATCH. A group's own row and its
 * membership are rendered through separate statements so members can change without rewriting the
 * group.
 */
public final class ScimGroupService {

    /** A SCIM member-removal path filter, e.g. {@code members[value eq "42"]} (RFC 7644 §3.5.2). */
    private static final Pattern MEMBER_FILTER =
            Pattern.compile("^members\\[\\s*value\\s+eq\\s+\"(.*)\"\\s*]$");

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
            return ScimListResponse.of(groups, groups.size(), startIndex);
        } catch (SQLException ex) {
            throw new ScimException(500, null, "SCIM group list failed: " + ex.getMessage());
        }
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
     * Applies a SCIM PATCH targeting the {@code members} attribute (RFC 7644 §3.5.2): {@code add}
     * inserts the supplied members and {@code remove} deletes them (by {@code value} array or by a
     * {@code members[value eq "..."]} path filter). Returns the updated group.
     */
    public ScimGroup patch(String id, ScimPatchRequest patch) {
        findById(id).orElseThrow(() -> new ScimException(404, null, "Group not found: " + id));
        for (ScimPatchRequest.Operation op : patch.operations()) {
            applyOperation(id, op);
        }
        return findById(id)
                .orElseThrow(() -> new ScimException(404, null, "Group not found: " + id));
    }

    private void applyOperation(String id, ScimPatchRequest.Operation op) {
        String name = op.op() == null ? "" : op.op().toLowerCase(java.util.Locale.ROOT);
        String path = op.path();
        switch (name) {
            case "add" -> {
                requireMembersPath(path);
                memberValues(op.value()).forEach(value -> addMember(id, value));
            }
            case "remove" -> {
                Matcher filter = path == null ? null : MEMBER_FILTER.matcher(path);
                if (filter != null && filter.matches()) {
                    removeMember(id, filter.group(1));
                } else {
                    requireMembersPath(path);
                    memberValues(op.value()).forEach(value -> removeMember(id, value));
                }
            }
            case "replace" -> {
                requireMembersPath(path);
                memberValues(op.value()).forEach(value -> addMember(id, value));
            }
            default -> throw new ScimException(400, "invalidSyntax",
                    "Unsupported PATCH op: " + op.op());
        }
    }

    private static void requireMembersPath(String path) {
        if (path != null && !"members".equals(path)) {
            throw new ScimException(400, "invalidPath",
                    "Only the members attribute is patchable on groups: " + path);
        }
    }

    /** Extracts member ids from a PATCH value node (an array of member objects, or a scalar id). */
    private static List<String> memberValues(JsonNode value) {
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (value.isArray()) {
            java.util.List<String> ids = new java.util.ArrayList<>();
            value.forEach(node -> {
                JsonNode member = node.get("value");
                ids.add(member != null ? member.asText() : node.asText());
            });
            return ids;
        }
        JsonNode member = value.get("value");
        return List.of(member != null ? member.asText() : value.asText());
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
