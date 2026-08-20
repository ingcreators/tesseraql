package io.tesseraql.identity;

import io.tesseraql.core.error.TqlException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Group management (docs/access-governance.md structural decision 4). The schema was complete
 * — {@code tql_groups}, {@code tql_user_groups}, {@code tql_group_roles} — and nothing wrote
 * it: three contracts read groups at sign-in, and a deployment could only populate them by
 * hand or through SCIM contract SQL of its own.
 *
 * <p>Membership carries the same {@code starts_at}/{@code ends_at} window every other
 * assignment carries, filtered at resolution by the same predicate. A second time model would
 * have been a second thing to explain.
 *
 * <p>The capability split is deliberate: creating a group and editing its role bundle are role
 * management, while putting somebody in one is user management. Delegating "who is in the
 * sales group" is not delegating "what the sales group may do".
 */
public final class GroupAdmin {

    private GroupAdmin() {
    }

    /** The groups page: every group with its live member count, and whether writes are on. */
    public static Map<String, Object> groupsModel(IdentityService identity, RealmConfig realm) {
        Map<String, Object> model = new LinkedHashMap<>();
        if (identity == null || realm == null) {
            return unavailable(model, "No identity realm is configured");
        }
        try {
            model.put("rows", identity.execute(realm, IdentityContracts.LIST_GROUPS, Map.of()));
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

    /** One group's detail: its members with their windows, and the roles it delivers. */
    public static Map<String, Object> groupModel(IdentityService identity, RealmConfig realm,
            String groupCode) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("groupCode", groupCode);
        if (identity == null || realm == null) {
            return unavailable(model, "No identity realm is configured");
        }
        Map<String, Object> byGroup = Map.of("groupCode", groupCode);
        try {
            model.put("members", identity.execute(realm,
                    IdentityContracts.LIST_GROUP_MEMBERS, byGroup));
            model.put("groupRoles", identity.execute(realm,
                    IdentityContracts.LIST_GROUP_ROLES, byGroup));
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

    public static Map<String, Object> createGroup(IdentityService identity, RealmConfig realm,
            String code, String name) {
        requireRealm(identity, realm);
        String groupCode = require(code, "group code");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("groupId", "grp-" + UUID.randomUUID());
        params.put("groupCode", groupCode);
        params.put("groupName", blankToNull(name) == null ? groupCode : name.trim());
        params.put("tenantId", null);
        identity.executeUpdate(realm, IdentityContracts.CREATE_GROUP, params);
        return Map.of("created", groupCode);
    }

    /**
     * Deletes a group, emptying its memberships and its bundle first. The order matters: a
     * join row pointing at a group that is gone is a membership nothing can explain, and the
     * standard schema carries no foreign keys to catch it.
     */
    public static Map<String, Object> deleteGroup(IdentityService identity, RealmConfig realm,
            String actor, String code) {
        requireRealm(identity, realm);
        Map<String, Object> params = Map.of("groupCode", require(code, "group code"));
        for (Map<String, Object> member : identity.execute(realm,
                IdentityContracts.LIST_GROUP_MEMBERS, params)) {
            GrantHistory.record(identity, realm, new GrantHistory.Change(actorOf(actor),
                    String.valueOf(member.get("user_id")), GrantHistory.GROUP_LEFT,
                    String.valueOf(params.get("groupCode")), null, GrantHistory.SOURCE_ADMIN,
                    null, null, null, null));
        }
        identity.executeUpdate(realm, IdentityContracts.CLEAR_GROUP_MEMBERS, params);
        identity.executeUpdate(realm, IdentityContracts.CLEAR_GROUP_ROLES, params);
        identity.executeUpdate(realm, IdentityContracts.DELETE_GROUP, params);
        return Map.of("deleted", code);
    }

    /** Puts one person in a group, optionally for a window. */
    public static Map<String, Object> addMember(IdentityService identity, RealmConfig realm,
            String actor, String groupCode, String userId, String startsAt, String endsAt) {
        requireRealm(identity, realm);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", require(userId, "user"));
        params.put("groupCode", require(groupCode, "group code"));
        params.put("source", GrantHistory.SOURCE_ADMIN);
        Timestamp from = RoleAdmin.window(startsAt);
        Timestamp until = RoleAdmin.window(endsAt);
        params.put("startsAt", from);
        params.put("endsAt", until);
        if (identity.executeUpdate(realm, IdentityContracts.ADD_GROUP_MEMBER, params) == 0) {
            // Zero rows is ambiguous between "already a member" and "no such group or
            // person", so it is reported as the second: a membership that silently did
            // not happen is the gap this campaign exists to close.
            throw new TqlException(RoleAdmin.INPUT_REFUSED, "No group '"
                    + params.get("groupCode") + "' and user '" + params.get("userId")
                    + "' to join, or the membership already exists");
        }
        GrantHistory.record(identity, realm, new GrantHistory.Change(actorOf(actor),
                String.valueOf(params.get("userId")), GrantHistory.GROUP_JOINED,
                String.valueOf(params.get("groupCode")), null, GrantHistory.SOURCE_ADMIN,
                from, until, null, null));
        return Map.of("added", userId);
    }

    public static Map<String, Object> removeMember(IdentityService identity, RealmConfig realm,
            String actor, String groupCode, String userId) {
        requireRealm(identity, realm);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", require(userId, "user"));
        params.put("groupCode", require(groupCode, "group code"));
        identity.executeUpdate(realm, IdentityContracts.REMOVE_GROUP_MEMBER, params);
        GrantHistory.record(identity, realm, new GrantHistory.Change(actorOf(actor),
                String.valueOf(params.get("userId")), GrantHistory.GROUP_LEFT,
                String.valueOf(params.get("groupCode")), null, GrantHistory.SOURCE_ADMIN,
                null, null, null, null));
        return Map.of("removed", userId);
    }

    /** Adds one role to a group's bundle; everybody in the group gains it at next sign-in. */
    public static Map<String, Object> grantRole(IdentityService identity, RealmConfig realm,
            String groupCode, String roleCode) {
        requireRealm(identity, realm);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("groupCode", require(groupCode, "group code"));
        params.put("roleCode", require(roleCode, "role code"));
        if (identity.executeUpdate(realm, IdentityContracts.GRANT_GROUP_ROLE, params) == 0) {
            throw new TqlException(RoleAdmin.INPUT_REFUSED, "No group '"
                    + params.get("groupCode") + "' and role '" + params.get("roleCode")
                    + "' to bundle, or the role is already in it");
        }
        return Map.of("granted", roleCode);
    }

    public static Map<String, Object> revokeRole(IdentityService identity, RealmConfig realm,
            String groupCode, String roleCode) {
        requireRealm(identity, realm);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("groupCode", require(groupCode, "group code"));
        params.put("roleCode", require(roleCode, "role code"));
        identity.executeUpdate(realm, IdentityContracts.REVOKE_GROUP_ROLE, params);
        return Map.of("revoked", roleCode);
    }

    private static Map<String, Object> unavailable(Map<String, Object> model, String reason) {
        model.put("rows", List.of());
        model.put("members", List.of());
        model.put("groupRoles", List.of());
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

    private static String actorOf(String value) {
        String named = blankToNull(value);
        return named == null ? "unknown" : named;
    }

    private static String require(String value, String what) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            throw new TqlException(RoleAdmin.INPUT_REFUSED, "A " + what + " is required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || "null".equals(value) ? null : value.trim();
    }
}
