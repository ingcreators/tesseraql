package io.tesseraql.identity;

import io.tesseraql.core.error.TqlException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Self-service access requests (docs/access-governance.md structural decision 6): somebody
 * asks for a role, its owner approves, and the grant lands — time-boxed when a duration was
 * asked for.
 *
 * <p><b>Why this is not a workflow document.</b> The deferral that led here proposed composing
 * the approval-workflow engine with the identity write contracts. Measurement made the cost
 * plain: a transition's command is app-authored 2-way SQL over an app-owned table, and the
 * engine has no seam for calling a framework service. Landing a grant from a transition
 * command would mean writing {@code tql_user_roles} directly — bypassing the
 * separation-of-duties check, the grant trail, the window validation and the delegation
 * containment this very campaign adds. The composition would have defeated the governance it
 * was meant to carry, so requests are framework store rows with a framework write path, and
 * the engine stays what it is: how an <em>application</em> moves <em>its</em> documents.
 *
 * <p>A role with no owner is not requestable. That is the deny-by-default answer to "who
 * approves this", rather than falling back to whoever administers the store.
 */
public final class AccessRequests {

    /** Waiting for an owner's decision. */
    public static final String PENDING = "pending";
    /** Approved; the grant landed. */
    public static final String APPROVED = "approved";
    /** Refused. */
    public static final String REJECTED = "rejected";
    /** Withdrawn by the person who asked. */
    public static final String CANCELLED = "cancelled";

    /** The provenance a request's grant carries into the trail. */
    public static final String SOURCE = "request";

    /** An owner named by subject. */
    public static final String OWNER_USER = "user";
    /** An owner named by group code; anybody in the group may decide. */
    public static final String OWNER_GROUP = "group";

    private AccessRequests() {
    }

    /** The requester's card: what they may ask for, and what they have asked for. */
    public static Map<String, Object> myRequestsModel(IdentityService identity,
            RealmConfig realm, String userId) {
        Map<String, Object> model = new LinkedHashMap<>();
        if (identity == null || realm == null) {
            return unavailable(model, "No identity realm is configured");
        }
        try {
            model.put("requestable", identity.execute(realm,
                    IdentityContracts.LIST_REQUESTABLE_ROLES, Map.of("userId", userId)));
            Map<String, Object> mine = new LinkedHashMap<>();
            mine.put("requesterId", userId);
            mine.put("status", null);
            model.put("rows", identity.execute(realm,
                    IdentityContracts.LIST_ACCESS_REQUESTS, mine));
            model.put("available", 1);
        } catch (TqlException ex) {
            if (!IdentityService.featureUnavailable(ex)) {
                throw ex;
            }
            return unavailable(model, ex.getMessage());
        }
        return model;
    }

    /**
     * The approver's queue: pending requests for roles this caller owns. Ownership is decided
     * here rather than in SQL, because the caller's groups are already on the principal and a
     * per-row store read to re-derive them would be both slower and a second source of truth.
     */
    public static Map<String, Object> queueModel(IdentityService identity, RealmConfig realm,
            String subject, List<String> groups) {
        Map<String, Object> model = new LinkedHashMap<>();
        if (identity == null || realm == null) {
            return unavailable(model, "No identity realm is configured");
        }
        try {
            Map<String, List<Map<String, Object>>> ownersByRole = new LinkedHashMap<>();
            Map<String, Object> allOwners = new LinkedHashMap<>();
            allOwners.put("roleCode", null);
            for (Map<String, Object> owner : identity.execute(realm,
                    IdentityContracts.LIST_ROLE_OWNERS, allOwners)) {
                ownersByRole.computeIfAbsent(String.valueOf(owner.get("role_code")),
                        key -> new ArrayList<>()).add(owner);
            }
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("requesterId", null);
            pending.put("status", PENDING);
            List<Map<String, Object>> mine = new ArrayList<>();
            for (Map<String, Object> row : identity.execute(realm,
                    IdentityContracts.LIST_ACCESS_REQUESTS, pending)) {
                if (owns(ownersByRole.get(String.valueOf(row.get("role_code"))), subject,
                        groups)) {
                    mine.add(row);
                }
            }
            model.put("rows", mine);
            model.put("available", 1);
        } catch (TqlException ex) {
            if (!IdentityService.featureUnavailable(ex)) {
                throw ex;
            }
            return unavailable(model, ex.getMessage());
        }
        return model;
    }

    /** Whether this caller is one of the role's owners, by subject or by group. */
    static boolean owns(List<Map<String, Object>> owners, String subject, List<String> groups) {
        if (owners == null) {
            return false;
        }
        for (Map<String, Object> owner : owners) {
            String kind = String.valueOf(owner.get("owner_kind"));
            String ref = String.valueOf(owner.get("owner_ref"));
            if (OWNER_USER.equals(kind) && ref.equals(subject)) {
                return true;
            }
            if (OWNER_GROUP.equals(kind) && groups != null && groups.contains(ref)) {
                return true;
            }
        }
        return false;
    }

    /** Asks for a role. An unowned role is refused, not silently dropped. */
    public static Map<String, Object> request(IdentityService identity, RealmConfig realm,
            String userId, String roleCode, String reason, String minutes) {
        requireRealm(identity, realm);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("requestId", "rq-" + UUID.randomUUID());
        params.put("requestedAt", Timestamp.from(Instant.now()));
        params.put("requesterId", require(userId, "user"));
        params.put("roleCode", require(roleCode, "role code"));
        params.put("reason", blankToNull(reason));
        params.put("requestedMinutes", minutes == null || minutes.isBlank()
                ? null
                : parseMinutes(minutes));
        if (identity.executeUpdate(realm, IdentityContracts.CREATE_ACCESS_REQUEST,
                params) == 0) {
            throw new TqlException(RoleAdmin.INPUT_REFUSED, "'" + params.get("roleCode")
                    + "' cannot be requested: it has no owner to approve it");
        }
        return Map.of("requested", roleCode);
    }

    /**
     * Approves or rejects. Approval lands the grant through {@link RoleAdmin}, so it passes
     * the separation-of-duties check and writes the trail row like any other — attributed to
     * the request, not to a plain administrative edit.
     *
     * <p>The decision is recorded <em>before</em> the grant, keyed on {@code pending}: two
     * approvers racing produce one decision, because the second write affects no rows and
     * stops there rather than granting twice.
     */
    public static Map<String, Object> decide(IdentityService identity, RealmConfig realm,
            String decidedBy, String requestId, String decision, String note) {
        requireRealm(identity, realm);
        String verdict = require(decision, "decision");
        if (!APPROVED.equals(verdict) && !REJECTED.equals(verdict)
                && !CANCELLED.equals(verdict)) {
            throw new TqlException(RoleAdmin.INPUT_REFUSED,
                    "A decision is '" + APPROVED + "', '" + REJECTED + "' or '" + CANCELLED
                            + "', not '" + verdict + "'");
        }
        Map<String, Object> request = find(identity, realm, require(requestId, "request"));
        if (request == null) {
            throw new TqlException(RoleAdmin.INPUT_REFUSED,
                    "No request '" + requestId + "'");
        }
        Integer minutes = request.get("requested_minutes") instanceof Number n
                ? n.intValue()
                : null;
        Timestamp until = APPROVED.equals(verdict) && minutes != null && minutes > 0
                ? Timestamp.from(Instant.now().plus(minutes, ChronoUnit.MINUTES))
                : null;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", verdict);
        params.put("decidedBy", blankToNull(decidedBy));
        params.put("decidedAt", Timestamp.from(Instant.now()));
        params.put("decisionNote", blankToNull(note));
        params.put("grantedUntil", until);
        params.put("requestId", requestId.trim());
        if (identity.executeUpdate(realm, IdentityContracts.DECIDE_ACCESS_REQUEST,
                params) == 0) {
            throw new TqlException(RoleAdmin.INPUT_REFUSED,
                    "Request '" + requestId + "' has already been decided");
        }
        if (!APPROVED.equals(verdict)) {
            // A rejection or a cancellation changes nothing about what is held, so there is
            // no grant row to record; the request itself is the record.
            return Map.of("decided", verdict);
        }
        RoleAdmin.assignRole(identity, realm, blankToNull(decidedBy),
                String.valueOf(request.get("requester_id")),
                String.valueOf(request.get("role_code")), null,
                until == null ? null : until.toLocalDateTime().toString(),
                SOURCE, String.valueOf(request.get("request_id")));
        return Map.of("decided", verdict);
    }

    /** Records who approves requests for a role. */
    public static Map<String, Object> addOwner(IdentityService identity, RealmConfig realm,
            String roleCode, String ownerKind, String ownerRef) {
        requireRealm(identity, realm);
        String kind = require(ownerKind, "owner kind");
        if (!OWNER_USER.equals(kind) && !OWNER_GROUP.equals(kind)) {
            throw new TqlException(RoleAdmin.INPUT_REFUSED, "An owner is a '" + OWNER_USER
                    + "' or a '" + OWNER_GROUP + "', not a '" + kind + "'");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("roleCode", require(roleCode, "role code"));
        params.put("ownerKind", kind);
        params.put("ownerRef", require(ownerRef, "owner"));
        if (identity.executeUpdate(realm, IdentityContracts.ADD_ROLE_OWNER, params) == 0) {
            throw new TqlException(RoleAdmin.INPUT_REFUSED, "No role '"
                    + params.get("roleCode") + "' to own, or that owner is already recorded");
        }
        return Map.of("added", ownerRef);
    }

    public static Map<String, Object> removeOwner(IdentityService identity, RealmConfig realm,
            String roleCode, String ownerKind, String ownerRef) {
        requireRealm(identity, realm);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("roleCode", require(roleCode, "role code"));
        params.put("ownerKind", require(ownerKind, "owner kind"));
        params.put("ownerRef", require(ownerRef, "owner"));
        identity.executeUpdate(realm, IdentityContracts.REMOVE_ROLE_OWNER, params);
        return Map.of("removed", ownerRef);
    }

    /** The owners page's model: every role's owners, with the role list for the editor. */
    public static Map<String, Object> ownersModel(IdentityService identity, RealmConfig realm) {
        Map<String, Object> model = new LinkedHashMap<>();
        if (identity == null || realm == null) {
            return unavailable(model, "No identity realm is configured");
        }
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("roleCode", null);
        try {
            model.put("rows", identity.execute(realm, IdentityContracts.LIST_ROLE_OWNERS, all));
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

    private static Map<String, Object> find(IdentityService identity, RealmConfig realm,
            String requestId) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("requesterId", null);
        all.put("status", null);
        for (Map<String, Object> row : identity.execute(realm,
                IdentityContracts.LIST_ACCESS_REQUESTS, all)) {
            if (requestId.equals(String.valueOf(row.get("request_id")))) {
                return row;
            }
        }
        return null;
    }

    private static Map<String, Object> unavailable(Map<String, Object> model, String reason) {
        model.put("rows", List.of());
        model.put("requestable", List.of());
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

    private static int parseMinutes(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            throw new TqlException(RoleAdmin.INPUT_REFUSED,
                    "Not a number of minutes: '" + value + "'");
        }
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
