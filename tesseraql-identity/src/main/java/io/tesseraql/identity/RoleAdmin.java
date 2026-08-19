package io.tesseraql.identity;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
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

    /** Assigns a role (replacing any admin assignment's window) as an admin grant. */
    public static Map<String, Object> assignRole(IdentityService identity, RealmConfig realm,
            String userId, String roleCode, String startsAt, String endsAt) {
        requireRealm(identity, realm);
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("userId", require(userId, "user"));
        key.put("roleCode", require(roleCode, "role code"));
        identity.executeUpdate(realm, IdentityContracts.REVOKE_USER_ROLE, key);
        Map<String, Object> params = new LinkedHashMap<>(key);
        params.put("startsAt", window(startsAt));
        params.put("endsAt", window(endsAt));
        identity.executeUpdate(realm, IdentityContracts.GRANT_USER_ROLE, params);
        return Map.of("assigned", roleCode);
    }

    public static Map<String, Object> unassignRole(IdentityService identity, RealmConfig realm,
            String userId, String roleCode) {
        requireRealm(identity, realm);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", require(userId, "user"));
        params.put("roleCode", require(roleCode, "role code"));
        identity.executeUpdate(realm, IdentityContracts.REVOKE_USER_ROLE, params);
        return Map.of("unassigned", roleCode);
    }

    /** Grants a permission code directly (creating the code row if it is new). */
    public static Map<String, Object> grantPermission(IdentityService identity,
            RealmConfig realm, String userId, String code, String startsAt, String endsAt) {
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
        params.put("startsAt", window(startsAt));
        params.put("endsAt", window(endsAt));
        identity.executeUpdate(realm, IdentityContracts.GRANT_USER_PERMISSION, params);
        return Map.of("granted", permission);
    }

    public static Map<String, Object> revokePermission(IdentityService identity,
            RealmConfig realm, String userId, String code) {
        requireRealm(identity, realm);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", require(userId, "user"));
        params.put("code", require(code, "permission code"));
        identity.executeUpdate(realm, IdentityContracts.REVOKE_USER_PERMISSION, params);
        return Map.of("revoked", code);
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
}
