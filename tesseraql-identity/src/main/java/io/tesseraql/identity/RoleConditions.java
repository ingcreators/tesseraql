package io.tesseraql.identity;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.net.CidrBlock;
import io.tesseraql.security.GrantConditions;
import io.tesseraql.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The administrative side of grant context conditions (docs/access-governance.md structural
 * decision 8): which roles carry a network or hours condition, and the writes that put one on
 * or take one off.
 *
 * <p>Evaluating a condition is {@link GrantConditions}, in the security module, because the
 * evaluation happens per request against a frozen principal and has no store to reach. What
 * lives here is the store: the read that rides sign-in, the admin read, and two writes that
 * refuse a value the evaluator could never satisfy.
 *
 * <p><b>A malformed value is refused at the write.</b> The evaluator fails closed on one, so a
 * mistyped block would not open a hole — it would quietly close the role to everybody, which is
 * a support call that looks like nothing in the store. Refusing at the point of writing is what
 * makes the difference between "this condition is wrong" and "this role stopped working".
 */
public final class RoleConditions {

    private RoleConditions() {
    }

    /** The conditions page's model: every condition, the roles to attach one to, writability. */
    public static Map<String, Object> conditionsModel(IdentityService identity,
            RealmConfig realm) {
        Map<String, Object> model = new LinkedHashMap<>();
        if (identity == null || realm == null) {
            return unavailable(model, "No identity realm is configured");
        }
        try {
            model.put("rows", identity.execute(realm, IdentityContracts.LIST_ROLE_CONDITIONS,
                    conditionParams(null, null, null)));
            model.put("roles", identity.execute(realm, IdentityContracts.LIST_ROLES, Map.of()));
            model.put("available", 1);
        } catch (TqlException ex) {
            if (!IdentityService.featureUnavailable(ex)) {
                throw ex;
            }
            return unavailable(model, ex.getMessage());
        }
        model.put("writable", realm.capabilities().roleWriteAllowed() ? 1 : 0);
        return model;
    }

    /** Puts one condition on a role, refusing a value that could never admit anybody. */
    public static Map<String, Object> addCondition(IdentityService identity, RealmConfig realm,
            String roleCode, String kind, String value) {
        requireRealm(identity, realm);
        Map<String, Object> params = validated(roleCode, kind, value);
        if (identity.executeUpdate(realm, IdentityContracts.ADD_ROLE_CONDITION, params) == 0) {
            // Zero rows means the role code names nothing, because the insert is otherwise
            // idempotent. A condition on a role that does not exist narrows nothing while
            // looking on the page exactly like one that does.
            throw new TqlException(RoleAdmin.INPUT_REFUSED,
                    "No role '" + params.get("roleCode") + "' to condition");
        }
        return Map.of("added", String.valueOf(params.get("roleCode")));
    }

    /** Lifts one condition off a role; the grant itself is untouched. */
    public static Map<String, Object> removeCondition(IdentityService identity,
            RealmConfig realm, String roleCode, String kind, String value) {
        requireRealm(identity, realm);
        // The value is not re-validated on the way out: a condition already in the store may
        // predate a tightening of what this accepts, and refusing to remove it would leave the
        // administrator with a row they can neither satisfy nor delete.
        Map<String, Object> params = conditionParams(require(roleCode, "role code"),
                require(kind, "condition kind"), require(value, "condition value"));
        if (identity.executeUpdate(realm, IdentityContracts.REMOVE_ROLE_CONDITION, params) == 0) {
            throw new TqlException(RoleAdmin.INPUT_REFUSED,
                    "No such condition on role '" + params.get("roleCode") + "'");
        }
        return Map.of("removed", String.valueOf(params.get("roleCode")));
    }

    /**
     * The conditions on the roles this person holds, keyed by role code, for the grants the
     * principal is built from.
     *
     * <p>Degrades to no conditions on a realm whose pack has no such contract or whose schema
     * predates the table — an uninstalled feature, not a fault. That is the safe direction
     * here for once: absent conditions mean an unnarrowed grant, which is exactly what the
     * deployment had before this slice.
     */
    public static Map<String, List<Principal.RoleGrant.Condition>> byRole(
            IdentityService identity, RealmConfig realm, String userId) {
        List<Map<String, Object>> rows;
        try {
            rows = identity.execute(realm, IdentityContracts.FIND_ROLE_CONDITIONS_BY_USER_ID,
                    Map.of("userId", userId));
        } catch (TqlException ex) {
            if (!IdentityService.featureUnavailable(ex)) {
                throw ex;
            }
            return Map.of();
        }
        Map<String, List<Principal.RoleGrant.Condition>> byRole = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object role = row.get("role_code");
            Object kind = row.get("condition_kind");
            Object value = row.get("value");
            if (role == null || kind == null || value == null) {
                continue;
            }
            byRole.computeIfAbsent(String.valueOf(role), key -> new ArrayList<>())
                    .add(new Principal.RoleGrant.Condition(String.valueOf(kind),
                            String.valueOf(value)));
        }
        return byRole;
    }

    /** The parameters of a condition write, validated against what the evaluator can read. */
    private static Map<String, Object> validated(String roleCode, String kind, String value) {
        String conditionKind = require(kind, "condition kind");
        String conditionValue = require(value, "condition value");
        switch (conditionKind) {
            case GrantConditions.NETWORK -> {
                try {
                    CidrBlock.parse(conditionValue);
                } catch (IllegalArgumentException invalid) {
                    throw new TqlException(RoleAdmin.INPUT_REFUSED,
                            "Not a network condition: " + invalid.getMessage());
                }
            }
            case GrantConditions.HOURS -> {
                try {
                    GrantConditions.Hours.parse(conditionValue);
                } catch (IllegalArgumentException invalid) {
                    throw new TqlException(RoleAdmin.INPUT_REFUSED, invalid.getMessage());
                }
            }
            default -> throw new TqlException(RoleAdmin.INPUT_REFUSED, "A condition is '"
                    + GrantConditions.NETWORK + "' or '" + GrantConditions.HOURS + "', not '"
                    + conditionKind + "'");
        }
        return conditionParams(require(roleCode, "role code"), conditionKind, conditionValue);
    }

    private static Map<String, Object> conditionParams(String roleCode, String kind,
            String value) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("roleCode", roleCode);
        params.put("conditionKind", kind);
        params.put("value", value);
        return params;
    }

    private static Map<String, Object> unavailable(Map<String, Object> model, String reason) {
        model.put("rows", List.of());
        model.put("roles", List.of());
        model.put("available", 0);
        model.put("writable", 0);
        model.put("reason", reason);
        return model;
    }

    private static void requireRealm(IdentityService identity, RealmConfig realm) {
        if (identity == null || realm == null) {
            throw new TqlException(ContractResolver.MISSING_CONTRACT,
                    "No identity realm is configured");
        }
    }

    private static String require(String value, String what) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || "null".equals(trimmed)) {
            throw new TqlException(RoleAdmin.INPUT_REFUSED, "A " + what + " is required");
        }
        return trimmed;
    }
}
