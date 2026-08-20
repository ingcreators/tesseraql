package io.tesseraql.identity;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Eligibility and just-in-time elevation (docs/access-governance.md structural decision 3):
 * an eligibility says a person <em>may take</em> a role, and grants nothing.
 *
 * <p>Nothing reads {@code tql_role_eligibility} during principal resolution, deliberately. An
 * eligible role is absent from the union, absent from {@code roleGrants}, and invisible to every
 * policy — which is the whole difference between "may take" and "holds". Elevating creates an
 * ordinary {@code tql_user_roles} row with a validity window, the shape the store already had,
 * so nothing downstream needs to learn a new concept and expiry needs no sweeper: the row stops
 * resolving when its window closes.
 *
 * <p>The elevation is stamped {@code source = 'elevation'} so it is distinguishable from a
 * standing grant, and so ending one early can key on that provenance without touching an
 * administrator's own assignment of the same role.
 */
public final class Elevation {

    /** An elevation is refused: no eligibility, a role already held, or a bad duration. */
    public static final TqlErrorCode REFUSED = new TqlErrorCode(TqlDomain.IAM, 4035);

    /** The provenance stamped on an elevated assignment. */
    public static final String SOURCE = "elevation";

    private Elevation() {
    }

    /**
     * What this person may elevate into, with any standing elevation's end. Degrades to an
     * empty, unavailable model on a realm whose pack has no eligibility contract.
     */
    public static Map<String, Object> eligibilityModel(IdentityService identity,
            RealmConfig realm, String userId) {
        Map<String, Object> model = new LinkedHashMap<>();
        if (identity == null || realm == null) {
            model.put("rows", List.of());
            model.put("available", 0);
            return model;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", userId);
        try {
            model.put("rows", identity.execute(realm,
                    IdentityContracts.LIST_ROLE_ELIGIBILITY, params));
            model.put("available", 1);
        } catch (TqlException ex) {
            if (!ContractResolver.MISSING_CONTRACT.equals(ex.code())) {
                throw ex;
            }
            model.put("rows", List.of());
            model.put("available", 0);
            model.put("reason", ex.getMessage());
        }
        return model;
    }

    /**
     * Takes an eligible role for a bounded window, capped by the eligibility's own limit.
     * Refuses a role the person already holds: the assignment would collide with the standing
     * row, and "extend what I have" is a different feature with different window semantics.
     * Separation of duties is checked here like any other grant, because an elevation is a
     * grant — a temporary one is exactly the kind a constraint exists to catch.
     *
     * @return the granted role and the instant the window closes
     */
    public static Map<String, Object> elevate(IdentityService identity, RealmConfig realm,
            String userId, String roleCode, String minutes, String reason) {
        if (identity == null || realm == null) {
            throw new TqlException(ContractResolver.MISSING_CONTRACT,
                    "No identity realm is configured");
        }
        String user = required(userId, "user");
        String role = required(roleCode, "role code");
        Map<String, Object> eligibility = eligibilityFor(identity, realm, user, role);
        int cap = number(eligibility.get("max_minutes"), 60);
        int requested = minutes == null || minutes.isBlank() ? cap : parseMinutes(minutes);
        if (requested < 1 || requested > cap) {
            throw refuse("An elevation into '" + role + "' lasts between 1"
                    + " and " + cap + " minutes, not " + requested);
        }
        String why = reason == null ? "" : reason.trim();
        if (number(eligibility.get("requires_reason"), 0) == 1 && why.isEmpty()) {
            throw refuse(
                    "Elevating into '" + role + "' requires a reason");
        }
        if (SeparationOfDuties.heldRoles(identity, realm, user).contains(role)) {
            throw refuse("'" + user + "' already holds '" + role
                    + "', so there is nothing to elevate into");
        }
        SeparationOfDuties.requireNoBlockingConflict(SeparationOfDuties.load(identity, realm),
                SeparationOfDuties.heldRoles(identity, realm, user), role);

        Instant now = Instant.now();
        Timestamp until = Timestamp.from(now.plus(requested, ChronoUnit.MINUTES));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", user);
        params.put("roleCode", role);
        params.put("startsAt", Timestamp.from(now.minusSeconds(1)));
        params.put("endsAt", until);
        identity.executeUpdate(realm, IdentityContracts.GRANT_USER_ROLE_ELEVATION, params);
        GrantHistory.record(identity, realm, new GrantHistory.Change(user, user,
                GrantHistory.ROLE_GRANTED, role, application(eligibility), SOURCE,
                (Timestamp) params.get("startsAt"), until, why.isEmpty() ? null : why, null));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("elevated", role);
        result.put("until", until.toString());
        return result;
    }

    /** Ends a standing elevation early; a standing grant of the same role is untouched. */
    public static Map<String, Object> endElevation(IdentityService identity, RealmConfig realm,
            String actor, String userId, String roleCode) {
        if (identity == null || realm == null) {
            throw new TqlException(ContractResolver.MISSING_CONTRACT,
                    "No identity realm is configured");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", required(userId, "user"));
        params.put("roleCode", required(roleCode, "role code"));
        identity.executeUpdate(realm, IdentityContracts.REVOKE_USER_ROLE_ELEVATION, params);
        GrantHistory.record(identity, realm, new GrantHistory.Change(
                actor == null || actor.isBlank() ? String.valueOf(params.get("userId")) : actor,
                String.valueOf(params.get("userId")), GrantHistory.ROLE_REVOKED,
                String.valueOf(params.get("roleCode")), null, SOURCE, null, null, null, null));
        return Map.of("ended", roleCode);
    }

    /** Records that a person may take a role; the administrator's side of the pair. */
    public static Map<String, Object> grantEligibility(IdentityService identity,
            RealmConfig realm, String userId, String roleCode, String maxMinutes,
            boolean requiresReason) {
        if (identity == null || realm == null) {
            throw new TqlException(ContractResolver.MISSING_CONTRACT,
                    "No identity realm is configured");
        }
        int cap = maxMinutes == null || maxMinutes.isBlank() ? 60 : parseMinutes(maxMinutes);
        if (cap < 1) {
            throw refuse(
                    "An eligibility's limit is at least one minute, not " + cap);
        }
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("userId", required(userId, "user"));
        key.put("roleCode", required(roleCode, "role code"));
        // Re-granting with different limits is a revoke and a grant, so the caller sees one
        // shape rather than an upsert that behaves differently per vendor.
        identity.executeUpdate(realm, IdentityContracts.REVOKE_ROLE_ELIGIBILITY, key);
        Map<String, Object> params = new LinkedHashMap<>(key);
        params.put("maxMinutes", cap);
        params.put("requiresReason", requiresReason ? 1 : 0);
        params.put("requiresApproval", 0);
        params.put("expiresAt", null);
        if (identity.executeUpdate(realm, IdentityContracts.GRANT_ROLE_ELIGIBILITY,
                params) == 0) {
            throw refuse(
                    "No role '" + key.get("roleCode") + "' to be eligible for");
        }
        return Map.of("eligible", roleCode);
    }

    public static Map<String, Object> revokeEligibility(IdentityService identity,
            RealmConfig realm, String userId, String roleCode) {
        if (identity == null || realm == null) {
            throw new TqlException(ContractResolver.MISSING_CONTRACT,
                    "No identity realm is configured");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", required(userId, "user"));
        params.put("roleCode", required(roleCode, "role code"));
        identity.executeUpdate(realm, IdentityContracts.REVOKE_ROLE_ELIGIBILITY, params);
        return Map.of("revoked", roleCode);
    }

    private static Map<String, Object> eligibilityFor(IdentityService identity,
            RealmConfig realm, String userId, String roleCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", userId);
        for (Map<String, Object> row : identity.execute(realm,
                IdentityContracts.LIST_ROLE_ELIGIBILITY, params)) {
            if (roleCode.equals(String.valueOf(row.get("role_code")))) {
                return row;
            }
        }
        // Absence denies, and the message says so without listing what else exists: an
        // elevation refusal is not a place to enumerate somebody's eligibilities.
        throw refuse(
                "'" + userId + "' is not eligible for '" + roleCode + "'");
    }

    private static String application(Map<String, Object> eligibility) {
        Object application = eligibility.get("application");
        return application == null ? null : String.valueOf(application);
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static int parseMinutes(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            throw refuse("Not a number of minutes: '" + value + "'");
        }
    }

    /**
     * A refusal that says why. The envelope's generic message would tell somebody who asked
     * for too long a window only "Bad Request", so the explanation rides {@code details} —
     * the one channel a thrower may declare safe to expose.
     */
    private static TqlException refuse(String message) {
        return TqlException.builder(REFUSED)
                .message(message)
                .details(Map.of("elevation", message))
                .build();
    }

    private static String required(String value, String what) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            throw refuse("A " + what + " is required");
        }
        return value.trim();
    }
}
