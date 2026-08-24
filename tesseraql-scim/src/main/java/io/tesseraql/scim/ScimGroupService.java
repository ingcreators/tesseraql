package io.tesseraql.scim;

import io.tesseraql.core.dialect.SqlErrors;
import io.tesseraql.core.sql.SqlStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Executes SCIM inbound provisioning for groups against the {@link ScimGroupContract} SQL
 * (design ch. 10.15): create, look up, list, delete, replace, and PATCH. A group's own row and its
 * membership are rendered through separate statements so members can change without rewriting the
 * group.
 */
public final class ScimGroupService {

    private final ScimGroupContract contract;
    private SqlStatement statements;
    /** Mints an id before the insert when set (the bundled managed set); null otherwise. */
    private java.util.function.Supplier<String> idSupplier;

    public ScimGroupService(DataSource dataSource, ScimGroupContract contract) {
        this.statements = SqlStatement.on(dataSource);
        this.contract = contract;
    }

    /**
     * The bound every SCIM contract statement runs under, in seconds; {@code 0} leaves it unset
     * (docs/contract-sql-execution.md structural decision 3).
     *
     * <p>There was none: a provisioning call's statement ran for as long as the driver allowed,
     * holding a pooled connection, where the same statement on a route has been bounded by
     * {@code tesseraql.sql.timeoutSeconds} all along.
     */
    public ScimGroupService sqlTimeoutSeconds(int seconds) {
        this.statements = statements.timeoutSeconds(seconds);
        return this;
    }

    /**
     * The dialect whose driver capabilities steer the generated-key JDBC call
     * (docs/contract-sql-execution.md structural decision 2). Labels stay raw regardless — SCIM's
     * aliases are quoted camelCase, and a quoted alias is not what label folding is for.
     */
    public ScimGroupService dialect(String dialect) {
        this.statements = statements.dialect(dialect).rawLabels();
        return this;
    }

    /**
     * The tracer every contract statement spans through (docs/contract-sql-execution.md
     * structural decision 5): a slow provisioning call stops being an unexplained gap.
     */
    public ScimGroupService tracer(io.tesseraql.core.telemetry.Tracer tracer) {
        this.statements = statements.tracer(tracer);
        return this;
    }

    /**
     * Mints the group id before the insert (docs/contract-sql-execution.md structural
     * decision 6): the bundled managed set targets a supplied {@code group_id}, so the id is
     * known before the statement runs — the other half of the declared-key decision.
     */
    public ScimGroupService idSupplier(java.util.function.Supplier<String> idSupplier) {
        this.idSupplier = idSupplier;
        return this;
    }

    /**
     * Creates a group (and any members supplied), returning the persisted resource. The create is
     * a plain write (docs/contract-sql-execution.md structural decision 2): the assigned id comes
     * from the contract's declared key when it declares one, and from the id the caller supplied
     * when it does not.
     *
     * <p>The row and its membership land in one transaction (structural decision 4): a failure
     * part-way used to leave a group holding some of the members the client sent while the client
     * was told the create failed — the one outcome nothing downstream expects.
     */
    public ScimGroup create(ScimGroup group) {
        try {
            return statements.transact("scim.groups.create", connection -> {
                Map<String, Object> params = ScimGroupMapper.toParams(group);
                String minted = null;
                if (idSupplier != null && (group.id() == null || group.id().isBlank())) {
                    minted = idSupplier.get();
                    params.put("id", minted);
                }
                SqlStatement.WriteResult written = statements.update(connection,
                        "scim.groups.create", contract.createSql(), params, contract.keys());
                String id = !written.keys().isEmpty()
                        ? string(written.keys().values().iterator().next())
                        : minted != null ? minted : group.id();
                // Deduplicated in Java: inside a transaction a unique violation aborts the
                // whole transaction on PostgreSQL, so the tolerance the old per-connection adds
                // relied on cannot apply — and a fresh group cannot collide with itself.
                Set<String> values = new LinkedHashSet<>();
                group.members().forEach(member -> values.add(member.value()));
                for (String value : values) {
                    addMember(connection, id, value);
                }
                Map<String, Object> row = statements.queryOne(connection, "scim.groups.findById",
                        contract.findByIdSql(), Map.of("id", id));
                if (row == null) {
                    throw new ScimException(500, null, "Group vanished after create: " + id);
                }
                return ScimGroupMapper.fromRow(row, members(connection, id));
            });
        } catch (SQLException ex) {
            if (SqlErrors.isUniqueViolation(ex)) {
                throw new ScimException(409, "uniqueness",
                        "Group already exists: " + group.displayName());
            }
            throw new ScimException(500, null, "SCIM group create failed: " + ex.getMessage());
        }
    }

    /** Looks up a group (with its members) by service-provider id. */
    public Optional<ScimGroup> findById(String id) {
        try {
            Map<String, Object> row = queryOne("findById", contract.findByIdSql(),
                    Map.of("id", id));
            return row == null
                    ? Optional.empty()
                    : Optional.of(ScimGroupMapper.fromRow(row, members(id)));
        } catch (SQLException ex) {
            throw new ScimException(500, null, "SCIM group lookup failed: " + ex.getMessage());
        }
    }

    /**
     * Lists a page of groups (with members); {@code startIndex} is 1-based per SCIM.
     *
     * <p>Members load per group — one statement per row of the page. Known and bounded: the
     * page size caps it, and this is an IdP-sync surface, not a request path. Batching it
     * means a tenth contract statement (an IN-list {@code list-members-of}), which widens the
     * all-or-nothing contract surface every custom deployment declares; do that when a real
     * deployment's sync is measurably slow here, not before.
     */
    public ScimListResponse<ScimGroup> list(int startIndex, int count) {
        try {
            // The offset arrives precomputed beside the SCIM-native 1-based startIndex,
            // because MySQL refuses an expression in its OFFSET clause; a contract binds
            // whichever it references.
            List<Map<String, Object>> rows = queryAll("list", contract.listSql(),
                    Map.of("startIndex", startIndex, "count", count,
                            "offset", Math.max(0, startIndex - 1)));
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
        return ScimCount.toInt(queryOne("count", contract.countSql(), Map.of()), fallback);
    }

    /**
     * Replaces a group by id (RFC 7644 §3.5.1): updates its own attributes and reconciles its
     * membership to exactly the supplied members — adding those that are missing and removing those
     * no longer present (bidirectional). Returns the updated group; 404 when it does not exist.
     */
    public ScimGroup replace(String id, ScimGroup group) {
        try {
            return statements.transact("scim.groups.replace", connection -> {
                Map<String, Object> params = ScimGroupMapper.toParams(group);
                params.put("id", id);
                // Zero affected rows is the 404 — what the returned row was standing in for
                // when the contract had to be `update … returning`.
                if (statements.update(connection, "scim.groups.replace", contract.replaceSql(),
                        params) == 0) {
                    throw new ScimException(404, null, "Group not found: " + id);
                }
                reconcileMembers(connection, id, group.members());
                Map<String, Object> row = statements.queryOne(connection, "scim.groups.findById",
                        contract.findByIdSql(), Map.of("id", id));
                if (row == null) {
                    throw new ScimException(404, null, "Group not found: " + id);
                }
                return ScimGroupMapper.fromRow(row, members(connection, id));
            });
        } catch (SQLException ex) {
            if (SqlErrors.isUniqueViolation(ex)) {
                throw new ScimException(409, "uniqueness",
                        "Group already exists: " + group.displayName());
            }
            throw new ScimException(500, null, "SCIM group replace failed: " + ex.getMessage());
        }
    }

    /** Drives the membership to exactly {@code desired}: adds the missing, removes the surplus. */
    private void reconcileMembers(Connection connection, String id, List<ScimGroup.Member> desired)
            throws SQLException {
        Set<String> target = new LinkedHashSet<>();
        desired.forEach(member -> target.add(member.value()));
        Set<String> current = new LinkedHashSet<>();
        members(connection, id).forEach(member -> current.add(member.value()));
        for (String value : target) {
            if (!current.contains(value)) {
                addMember(connection, id, value);
            }
        }
        for (String value : current) {
            if (!target.contains(value)) {
                removeMember(connection, id, value);
            }
        }
    }

    /** Deletes a group by id; throws 404 when it does not exist. */
    public void delete(String id) {
        try {
            if (statements.update("scim.groups.delete", contract.deleteSql(),
                    Map.of("id", id)) == 0) {
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

    private void addMember(Connection connection, String groupId, String memberId)
            throws SQLException {
        statements.update(connection, "scim.groups.addMember", contract.addMemberSql(),
                Map.of("groupId", groupId, "memberId", memberId));
    }

    private void removeMember(Connection connection, String groupId, String memberId)
            throws SQLException {
        statements.update(connection, "scim.groups.removeMember", contract.removeMemberSql(),
                Map.of("groupId", groupId, "memberId", memberId));
    }

    private List<ScimGroup.Member> members(Connection connection, String groupId)
            throws SQLException {
        return statements.query(connection, "scim.groups.listMembers", contract.listMembersSql(),
                Map.of("groupId", groupId)).stream().map(ScimGroupMapper::memberFromRow).toList();
    }

    private List<ScimGroup.Member> members(String groupId) {
        try {
            return queryAll("listMembers", contract.listMembersSql(), Map.of("groupId", groupId))
                    .stream().map(ScimGroupMapper::memberFromRow).toList();
        } catch (SQLException ex) {
            throw new ScimException(500, null, "SCIM group members failed: " + ex.getMessage());
        }
    }

    /**
     * One SCIM contract statement, named as its configuration key names it.
     *
     * <p>Labels come back exactly as the driver reports them: SCIM's attribute names are camelCase,
     * so a contract has to quote its aliases on every dialect for the mapper to find them, and a
     * quoted alias is not what Oracle's label folding is for.
     */
    private Map<String, Object> queryOne(String name, String sql, Map<String, Object> params)
            throws SQLException {
        return statements.queryOne("scim.groups." + name, sql, params);
    }

    private List<Map<String, Object>> queryAll(String name, String sql, Map<String, Object> params)
            throws SQLException {
        return statements.query("scim.groups." + name, sql, params);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
