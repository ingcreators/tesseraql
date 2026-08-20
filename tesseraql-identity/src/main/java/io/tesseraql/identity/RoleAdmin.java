package io.tesseraql.identity;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The IAM Admin role and grant writes (docs/application-roles.md slice 2): create a role on
 * the application axis, assign and unassign users with validity windows, and grant or revoke
 * direct permissions. Every write rides {@link IdentityService#executeUpdate}, so the realm's
 * role capability gates it; validation happens here, before any SQL.
 */
public final class RoleAdmin {

    /** A role-management input is refused: a code outside its application, or a bad window. */
    public static final TqlErrorCode INPUT_REFUSED = new TqlErrorCode(TqlDomain.IAM, 4033);

    private RoleAdmin() {
    }

    /** The roles page's model: every role, the member list, and whether writes are allowed. */
    public static Map<String, Object> rolesModel(IdentityService identity, RealmConfig realm,
            java.util.List<String> members) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("members", members);
        if (identity == null || realm == null) {
            return unavailable(model, "No identity realm is configured");
        }
        try {
            model.put("rows", identity.execute(realm, IdentityContracts.LIST_ROLES, Map.of()));
            model.put("available", 1);
        } catch (TqlException ex) {
            if (!ContractResolver.MISSING_CONTRACT.equals(ex.code())) {
                throw ex;
            }
            return unavailable(model, ex.getMessage());
        }
        model.put("writable", realm.capabilities().roleWriteAllowed() ? 1 : 0);
        return model;
    }

    /** One user's editable grants: role assignments and direct permissions, windows shown. */
    public static Map<String, Object> grantEditorModel(IdentityService identity,
            RealmConfig realm, String userId) {
        Map<String, Object> model = new LinkedHashMap<>();
        if (identity == null || realm == null) {
            return unavailable(model, "No identity realm is configured");
        }
        Map<String, Object> byUser = Map.of("userId", userId);
        try {
            model.put("attributes", identity.execute(realm,
                    IdentityContracts.LIST_USER_ATTRIBUTES, byUser));
            model.put("assignments", identity.execute(realm,
                    IdentityContracts.LIST_ROLE_ASSIGNMENTS_BY_USER_ID, byUser));
            model.put("grants", identity.execute(realm,
                    IdentityContracts.LIST_PERMISSION_GRANTS_BY_USER_ID, byUser));
            model.put("roles", identity.execute(realm, IdentityContracts.LIST_ROLES, Map.of()));
            model.put("available", 1);
        } catch (TqlException ex) {
            if (!ContractResolver.MISSING_CONTRACT.equals(ex.code())) {
                throw ex;
            }
            return unavailable(model, ex.getMessage());
        }
        model.put("writable", realm.capabilities().roleWriteAllowed() ? 1 : 0);
        return model;
    }

    private static Map<String, Object> unavailable(Map<String, Object> model, String reason) {
        model.put("rows", java.util.List.of());
        model.put("attributes", java.util.List.of());
        model.put("assignments", java.util.List.of());
        model.put("grants", java.util.List.of());
        model.put("roles", java.util.List.of());
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

    /**
     * Creates a role. A blank application means stack-wide; a named application requires the
     * role code to carry that application's name as its first segment — the store-side twin
     * of the declared-role rule, enforced where the row is made.
     */
    public static Map<String, Object> createRole(IdentityService identity, RealmConfig realm,
            String code, String name, String application) {
        requireRealm(identity, realm);
        String roleCode = require(code, "role code");
        String app = blankToNull(application);
        if (app != null && !roleCode.startsWith(app + ".")) {
            throw new TqlException(INPUT_REFUSED, "An application role's code carries its "
                    + "application's name as its first segment: expected '" + app
                    + ".…', got '" + roleCode + "'");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("roleId", roleCode);
        params.put("roleCode", roleCode);
        params.put("roleName", blankToNull(name) == null ? roleCode : name.trim());
        params.put("application", app);
        identity.executeUpdate(realm, IdentityContracts.CREATE_ROLE, params);
        return Map.of("created", roleCode);
    }

    /**
     * Assigns a role (replacing any admin assignment's window) as an admin grant, and records
     * the change in the grant trail (docs/access-governance.md structural decision 1). The
     * re-assign is a revoke and a grant, but it is one decision, so it leaves one row.
     */
    public static Map<String, Object> assignRole(IdentityService identity, RealmConfig realm,
            String actor, String userId, String roleCode, String startsAt, String endsAt) {
        requireRealm(identity, realm);
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("userId", require(userId, "user"));
        key.put("roleCode", require(roleCode, "role code"));
        // The first separation-of-duties checkpoint (docs/access-governance.md structural
        // decision 2). A person is deciding here, so a blocking conflict refuses and says
        // which side to revoke; nothing is written before the check.
        SeparationOfDuties.requireNoBlockingConflict(
                SeparationOfDuties.load(identity, realm),
                SeparationOfDuties.heldRoles(identity, realm, String.valueOf(key.get("userId"))),
                String.valueOf(key.get("roleCode")));
        identity.executeUpdate(realm, IdentityContracts.REVOKE_USER_ROLE, key);
        Map<String, Object> params = new LinkedHashMap<>(key);
        Timestamp from = window(startsAt);
        Timestamp until = window(endsAt);
        params.put("startsAt", from);
        params.put("endsAt", until);
        identity.executeUpdate(realm, IdentityContracts.GRANT_USER_ROLE, params);
        GrantHistory.record(identity, realm, GrantHistory.Change.granted(actor(actor),
                String.valueOf(key.get("userId")), GrantHistory.ROLE_GRANTED,
                String.valueOf(key.get("roleCode")), from, until));
        return Map.of("assigned", roleCode);
    }

    public static Map<String, Object> unassignRole(IdentityService identity, RealmConfig realm,
            String actor, String userId, String roleCode) {
        requireRealm(identity, realm);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", require(userId, "user"));
        params.put("roleCode", require(roleCode, "role code"));
        identity.executeUpdate(realm, IdentityContracts.REVOKE_USER_ROLE, params);
        GrantHistory.record(identity, realm, GrantHistory.Change.admin(actor(actor),
                String.valueOf(params.get("userId")), GrantHistory.ROLE_REVOKED,
                String.valueOf(params.get("roleCode"))));
        return Map.of("unassigned", roleCode);
    }

    /** Grants a permission code directly (creating the code row if it is new). */
    public static Map<String, Object> grantPermission(IdentityService identity,
            RealmConfig realm, String actor, String userId, String code, String startsAt,
            String endsAt) {
        requireRealm(identity, realm);
        String permission = require(code, "permission code");
        Map<String, Object> ensure = new LinkedHashMap<>();
        ensure.put("permissionId", permission);
        ensure.put("permissionCode", permission);
        ensure.put("permissionName", permission);
        identity.executeUpdate(realm, IdentityContracts.ENSURE_PERMISSION, ensure);
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("userId", require(userId, "user"));
        key.put("code", permission);
        identity.executeUpdate(realm, IdentityContracts.REVOKE_USER_PERMISSION, key);
        Map<String, Object> params = new LinkedHashMap<>(key);
        Timestamp from = window(startsAt);
        Timestamp until = window(endsAt);
        params.put("startsAt", from);
        params.put("endsAt", until);
        identity.executeUpdate(realm, IdentityContracts.GRANT_USER_PERMISSION, params);
        GrantHistory.record(identity, realm, GrantHistory.Change.granted(actor(actor),
                String.valueOf(key.get("userId")), GrantHistory.PERMISSION_GRANTED, permission,
                from, until));
        return Map.of("granted", permission);
    }

    public static Map<String, Object> revokePermission(IdentityService identity,
            RealmConfig realm, String actor, String userId, String code) {
        requireRealm(identity, realm);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", require(userId, "user"));
        params.put("code", require(code, "permission code"));
        identity.executeUpdate(realm, IdentityContracts.REVOKE_USER_PERMISSION, params);
        GrantHistory.record(identity, realm, GrantHistory.Change.admin(actor(actor),
                String.valueOf(params.get("userId")), GrantHistory.PERMISSION_REVOKED,
                String.valueOf(params.get("code"))));
        return Map.of("revoked", code);
    }

    /** Sets one user attribute (delete-then-insert; the user-management gate applies). */
    public static Map<String, Object> setAttribute(IdentityService identity, RealmConfig realm,
            String userId, String name, String value) {
        requireRealm(identity, realm);
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("userId", require(userId, "user"));
        key.put("name", require(name, "attribute name"));
        identity.executeUpdate(realm, IdentityContracts.DELETE_USER_ATTRIBUTE, key);
        Map<String, Object> params = new LinkedHashMap<>(key);
        params.put("value", value == null ? "" : value.trim());
        identity.executeUpdate(realm, IdentityContracts.INSERT_USER_ATTRIBUTE, params);
        return Map.of("set", name);
    }

    public static Map<String, Object> deleteAttribute(IdentityService identity,
            RealmConfig realm, String userId, String name) {
        requireRealm(identity, realm);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", require(userId, "user"));
        params.put("name", require(name, "attribute name"));
        identity.executeUpdate(realm, IdentityContracts.DELETE_USER_ATTRIBUTE, params);
        return Map.of("deleted", name);
    }

    /** Creates a rule granting one role, with its first condition in the same submit. */
    public static Map<String, Object> createRule(IdentityService identity, RealmConfig realm,
            String roleCode, String attribute, String kind, String value, boolean orgManaged) {
        requireRealm(identity, realm);
        RoleRules.validateCondition(attribute, require(kind, "condition kind"),
                require(value, "condition value"), orgManaged);
        String ruleId = "rule-" + java.util.UUID.randomUUID();
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("ruleId", ruleId);
        create.put("roleCode", require(roleCode, "role code"));
        identity.executeUpdate(realm, IdentityContracts.CREATE_ROLE_RULE, create);
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("ruleId", ruleId);
        condition.put("attributeName", attribute == null || attribute.isBlank()
                || "null".equals(attribute) ? null : attribute.trim());
        condition.put("matchKind", kind.trim());
        condition.put("value", value.trim());
        identity.executeUpdate(realm, IdentityContracts.INSERT_RULE_CONDITION, condition);
        return Map.of("created", ruleId);
    }

    /** Adds one condition to an existing rule (conditions AND across attributes). */
    public static Map<String, Object> addRuleCondition(IdentityService identity,
            RealmConfig realm, String ruleId, String attribute, String kind, String value,
            boolean orgManaged) {
        requireRealm(identity, realm);
        RoleRules.validateCondition(attribute, require(kind, "condition kind"),
                require(value, "condition value"), orgManaged);
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("ruleId", require(ruleId, "rule"));
        condition.put("attributeName", attribute == null || attribute.isBlank()
                || "null".equals(attribute) ? null : attribute.trim());
        condition.put("matchKind", kind.trim());
        condition.put("value", value.trim());
        identity.executeUpdate(realm, IdentityContracts.INSERT_RULE_CONDITION, condition);
        return Map.of("added", ruleId);
    }

    public static Map<String, Object> deleteRule(IdentityService identity, RealmConfig realm,
            String ruleId) {
        requireRealm(identity, realm);
        Map<String, Object> params = Map.of("ruleId", require(ruleId, "rule"));
        identity.executeUpdate(realm, IdentityContracts.DELETE_RULE_CONDITIONS, params);
        identity.executeUpdate(realm, IdentityContracts.DELETE_ROLE_RULE, params);
        return Map.of("deleted", ruleId);
    }

    /** The rules page's model: every rule with its conditions, and the role dropdown. */
    public static Map<String, Object> rulesModel(IdentityService identity, RealmConfig realm) {
        Map<String, Object> model = new LinkedHashMap<>();
        if (identity == null || realm == null) {
            return unavailable(model, "No identity realm is configured");
        }
        try {
            Map<String, List<Map<String, Object>>> conditions = new LinkedHashMap<>();
            for (Map<String, Object> row : identity.execute(realm,
                    IdentityContracts.LIST_RULE_CONDITIONS, Map.of())) {
                conditions.computeIfAbsent(String.valueOf(row.get("rule_id")),
                        key -> new java.util.ArrayList<>()).add(row);
            }
            List<Map<String, Object>> rules = new java.util.ArrayList<>();
            for (Map<String, Object> row : identity.execute(realm,
                    IdentityContracts.LIST_ROLE_RULES, Map.of())) {
                Map<String, Object> rule = new LinkedHashMap<>(row);
                rule.put("conditions", conditions.getOrDefault(
                        String.valueOf(row.get("rule_id")), List.of()));
                rules.add(rule);
            }
            model.put("rows", rules);
            model.put("roles", identity.execute(realm, IdentityContracts.LIST_ROLES,
                    Map.of()));
            model.put("available", 1);
        } catch (TqlException ex) {
            if (!ContractResolver.MISSING_CONTRACT.equals(ex.code())) {
                throw ex;
            }
            return unavailable(model, ex.getMessage());
        }
        model.put("writable", realm.capabilities().roleWriteAllowed() ? 1 : 0);
        return model;
    }

    /** Recomputes every user's rule-produced assignments (the admin's bulk action). */
    public static Map<String, Object> recomputeAll(IdentityService identity,
            RealmConfig realm) {
        requireRealm(identity, realm);
        int users = 0;
        for (Map<String, Object> row : identity.execute(realm,
                IdentityContracts.LIST_USER_IDS, Map.of())) {
            RoleRules.recompute(identity, realm, String.valueOf(row.get("user_id")));
            users++;
        }
        return Map.of("recomputed", users);
    }

    /** A window field: blank means unbounded; a date or date-time means that instant. */
    static Timestamp window(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return null;
        }
        String text = value.trim();
        try {
            return text.length() == 10
                    ? Timestamp.valueOf(LocalDate.parse(text).atStartOfDay())
                    : Timestamp.valueOf(LocalDateTime.parse(text));
        } catch (DateTimeParseException ex) {
            throw new TqlException(INPUT_REFUSED,
                    "Not a date or date-time: '" + text + "'");
        }
    }

    private static String require(String value, String what) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            throw new TqlException(INPUT_REFUSED, "A " + what + " is required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || "null".equals(value) ? null : value.trim();
    }

    /**
     * Creates a separation-of-duties constraint with its first two role codes
     * (docs/access-governance.md structural decision 2). Two is the minimum that can be
     * violated, so a constraint is never created with fewer — one holding a single code
     * would sit on the page asserting a protection it cannot provide.
     */
    public static Map<String, Object> createConstraint(IdentityService identity,
            RealmConfig realm, String name, String severity, String firstRole,
            String secondRole) {
        requireRealm(identity, realm);
        String level = require(severity, "severity");
        if (!SeparationOfDuties.BLOCK.equals(level) && !SeparationOfDuties.WARN.equals(level)) {
            throw new TqlException(INPUT_REFUSED, "A severity is '"
                    + SeparationOfDuties.BLOCK + "' or '" + SeparationOfDuties.WARN
                    + "', not '" + level + "'");
        }
        String first = require(firstRole, "first role code");
        String second = require(secondRole, "second role code");
        if (first.equals(second)) {
            throw new TqlException(INPUT_REFUSED,
                    "A constraint separates two different roles; both are '" + first + "'");
        }
        String constraintId = "sod-" + java.util.UUID.randomUUID();
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("constraintId", constraintId);
        create.put("constraintName", require(name, "constraint name"));
        create.put("severity", level);
        create.put("description", null);
        identity.executeUpdate(realm, IdentityContracts.CREATE_SOD_CONSTRAINT, create);
        addConstraintRole(identity, realm, constraintId, first);
        addConstraintRole(identity, realm, constraintId, second);
        return Map.of("created", constraintId);
    }

    /** Adds one more mutually exclusive role code to an existing constraint. */
    public static Map<String, Object> addConstraintRole(IdentityService identity,
            RealmConfig realm, String constraintId, String roleCode) {
        requireRealm(identity, realm);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("constraintId", require(constraintId, "constraint"));
        params.put("roleCode", require(roleCode, "role code"));
        if (identity.executeUpdate(realm, IdentityContracts.ADD_SOD_CONSTRAINT_ROLE,
                params) == 0) {
            // Zero rows means the role code names nothing, because the insert is otherwise
            // idempotent. A constraint over a code that does not exist can never fire.
            throw new TqlException(INPUT_REFUSED,
                    "No role '" + params.get("roleCode") + "' to constrain");
        }
        return Map.of("added", roleCode);
    }

    public static Map<String, Object> deleteConstraint(IdentityService identity,
            RealmConfig realm, String constraintId) {
        requireRealm(identity, realm);
        Map<String, Object> params = Map.of("constraintId", require(constraintId, "constraint"));
        identity.executeUpdate(realm, IdentityContracts.DELETE_SOD_CONSTRAINT_ROLES, params);
        identity.executeUpdate(realm, IdentityContracts.DELETE_SOD_CONSTRAINT, params);
        return Map.of("deleted", constraintId);
    }

    /**
     * The actor recorded in the grant trail. An unnamed caller records as {@code unknown}
     * rather than as nobody: the row saying a change happened without saying who is still
     * worth having, and a blank column reads as a bug in the trail.
     */
    private static String actor(String value) {
        String named = blankToNull(value);
        return named == null ? "unknown" : named;
    }
}
