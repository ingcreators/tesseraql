package io.tesseraql.scim;

import io.tesseraql.core.dialect.SqlErrors;
import io.tesseraql.core.sql.ContractStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Executes SCIM inbound provisioning against the {@link ScimContract} SQL (design ch. 10.15): create,
 * look up by id, and list users. Each statement is rendered with the SCIM attribute bind values and
 * its rows are mapped back to {@link ScimUser} via {@link ScimUserMapper}.
 */
public final class ScimUserService {

    private final ScimContract contract;
    private ContractStatement statements;

    public ScimUserService(DataSource dataSource, ScimContract contract) {
        this.statements = ContractStatement.on(dataSource);
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
    public ScimUserService sqlTimeoutSeconds(int seconds) {
        this.statements = statements.timeoutSeconds(seconds);
        return this;
    }

    /**
     * The dialect whose driver capabilities steer the generated-key JDBC call
     * (docs/contract-sql-execution.md structural decision 2). Labels stay raw regardless — SCIM's
     * aliases are quoted camelCase, and a quoted alias is not what label folding is for.
     */
    public ScimUserService dialect(String dialect) {
        this.statements = statements.dialect(dialect).rawLabels();
        return this;
    }

    /**
     * Creates a user, returning the persisted resource (with its assigned id). The create is a
     * plain write (docs/contract-sql-execution.md structural decision 2): the assigned id comes
     * from the contract's declared key when it declares one, and from the id the caller supplied
     * when it does not — {@code insert … returning} is no longer required, which is what made
     * the contract PostgreSQL-and-Oracle-only.
     */
    public ScimUser create(ScimUser user) {
        try {
            ContractStatement.WriteResult written = statements.update("scim.users.create",
                    contract.createSql(), ScimUserMapper.toParams(user), contract.keys());
            String id = written.keys().isEmpty()
                    ? user.id()
                    : string(written.keys().values().iterator().next());
            if (id == null) {
                return user;
            }
            Map<String, Object> row = queryOne("findById", contract.findByIdSql(),
                    Map.of("id", id));
            return row == null ? user : ScimUserMapper.fromRow(row);
        } catch (SQLException ex) {
            if (SqlErrors.isUniqueViolation(ex)) {
                throw new ScimException(409, "uniqueness",
                        "User already exists: " + user.userName());
            }
            throw new ScimException(500, null, "SCIM create failed: " + ex.getMessage());
        }
    }

    /** Looks up a user by service-provider id. */
    public Optional<ScimUser> findById(String id) {
        try {
            Map<String, Object> row = queryOne("findById", contract.findByIdSql(),
                    Map.of("id", id));
            return row == null ? Optional.empty() : Optional.of(ScimUserMapper.fromRow(row));
        } catch (SQLException ex) {
            throw new ScimException(500, null, "SCIM lookup failed: " + ex.getMessage());
        }
    }

    /** Replaces a user by id, returning the updated resource; 404 when it does not exist. */
    public ScimUser replace(String id, ScimUser user) {
        try {
            Map<String, Object> params = ScimUserMapper.toParams(user);
            params.put("id", id);
            // Zero affected rows is the 404 — what the returned row was standing in for when
            // the contract had to be `update … returning`.
            if (statements.update("scim.users.replace", contract.replaceSql(), params) == 0) {
                throw new ScimException(404, null, "User not found: " + id);
            }
            Map<String, Object> row = queryOne("findById", contract.findByIdSql(),
                    Map.of("id", id));
            if (row == null) {
                throw new ScimException(404, null, "User not found: " + id);
            }
            return ScimUserMapper.fromRow(row);
        } catch (SQLException ex) {
            if (SqlErrors.isUniqueViolation(ex)) {
                throw new ScimException(409, "uniqueness",
                        "User already exists: " + user.userName());
            }
            throw new ScimException(500, null, "SCIM replace failed: " + ex.getMessage());
        }
    }

    /** Applies a SCIM PATCH by normalizing it against the current user and replacing (RFC 7644 §3.5.2). */
    public ScimUser patch(String id, ScimPatchRequest patch) {
        ScimUser current = findById(id)
                .orElseThrow(() -> new ScimException(404, null, "User not found: " + id));
        return replace(id, ScimPatch.apply(current, patch));
    }

    /** Deletes a user by id; throws 404 when it does not exist. */
    public void delete(String id) {
        try {
            if (statements.update("scim.users.delete", contract.deleteSql(),
                    Map.of("id", id)) == 0) {
                throw new ScimException(404, null, "User not found: " + id);
            }
        } catch (SQLException ex) {
            throw new ScimException(500, null, "SCIM delete failed: " + ex.getMessage());
        }
    }

    /** Lists a page of users; {@code startIndex} is 1-based per SCIM. */
    public ScimListResponse<ScimUser> list(int startIndex, int count) {
        try {
            List<Map<String, Object>> rows = queryAll("list", contract.listSql(),
                    Map.of("startIndex", startIndex, "count", count));
            List<ScimUser> users = rows.stream().map(ScimUserMapper::fromRow).toList();
            return ScimListResponse.of(users, total(users.size()), startIndex);
        } catch (SQLException ex) {
            throw new ScimException(500, null, "SCIM list failed: " + ex.getMessage());
        }
    }

    /** Total matching users from the count contract SQL, or {@code fallback} when none is configured. */
    private int total(int fallback) throws SQLException {
        if (contract.countSql() == null || contract.countSql().isBlank()) {
            return fallback;
        }
        Map<String, Object> row = queryOne("count", contract.countSql(), Map.of());
        return ScimCount.toInt(row, fallback);
    }

    /**
     * Lists users matching a SCIM filter; only {@code userName eq "..."} is supported, returning the
     * matching user (or an empty list) so provisioning clients can dedupe before create.
     */
    public ScimListResponse<ScimUser> list(int startIndex, int count, String filter) {
        if (filter == null || filter.isBlank()) {
            return list(startIndex, count);
        }
        ScimFilter parsed = ScimFilter.parse(filter);
        if (!"userName".equals(parsed.attribute())) {
            throw new ScimException(400, "invalidFilter",
                    "Unsupported filter attribute: " + parsed.attribute());
        }
        try {
            Map<String, Object> row = queryOne("findByUserName", contract.findByUserNameSql(),
                    Map.of("userName", parsed.value()));
            List<ScimUser> users = row == null ? List.of() : List.of(ScimUserMapper.fromRow(row));
            return ScimListResponse.of(users, users.size(), startIndex);
        } catch (SQLException ex) {
            throw new ScimException(500, null, "SCIM filter failed: " + ex.getMessage());
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
        return statements.queryOne("scim.users." + name, sql, params);
    }

    private List<Map<String, Object>> queryAll(String name, String sql, Map<String, Object> params)
            throws SQLException {
        return statements.query("scim.users." + name, sql, params);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
