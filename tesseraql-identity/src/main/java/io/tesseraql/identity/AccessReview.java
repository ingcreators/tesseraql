package io.tesseraql.identity;

import io.tesseraql.core.error.TqlException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Periodic access review (docs/access-governance.md structural decision 5): a campaign over a
 * <em>snapshot</em> of who holds what, decided item by item, and executed on close.
 *
 * <p>The snapshot is the point. A campaign that read live grants would ask reviewers about a
 * moving target and could never answer "what did we certify in Q3". Snapshotting answers both,
 * and it makes the gap between opening and closing visible instead of invisible: an item whose
 * grant is already gone at close is recorded {@code stale} rather than re-revoked, because
 * claiming to have removed something that was not there is a false entry in the record.
 *
 * <p>Closing executes every {@code revoke} decision through {@link RoleAdmin}, so each
 * revocation passes the same validation, writes the same history row, and appears in the trail
 * like any other change — the campaign is a decision surface, not a second write path.
 */
public final class AccessReview {

    /** A decision has not been made yet. */
    public static final String PENDING = "pending";
    /** The reviewer certified the grant. */
    public static final String KEEP = "keep";
    /** The reviewer asked for the grant to go; close executes it. */
    public static final String REVOKE = "revoke";
    /** The grant was gone before the campaign closed, so nothing was executed. */
    public static final String STALE = "stale";

    /** A campaign taking decisions. */
    public static final String OPEN = "open";

    /** The provenance a close's revocations carry into the grant trail. */
    public static final String SOURCE = "review";

    private AccessReview() {
    }

    /** The reviews page: every campaign with its item and pending counts. */
    public static Map<String, Object> reviewsModel(IdentityService identity, RealmConfig realm,
            List<String> members) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("members", members == null ? List.of() : members);
        if (identity == null || realm == null) {
            return unavailable(model, "No identity realm is configured");
        }
        try {
            model.put("rows", identity.execute(realm, IdentityContracts.LIST_ACCESS_REVIEWS,
                    Map.of()));
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

    /** One campaign's items, and whether it still takes decisions. */
    public static Map<String, Object> reviewModel(IdentityService identity, RealmConfig realm,
            String reviewId) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("reviewId", reviewId);
        if (identity == null || realm == null) {
            return unavailable(model, "No identity realm is configured");
        }
        try {
            model.put("items", identity.execute(realm, IdentityContracts.LIST_REVIEW_ITEMS,
                    Map.of("reviewId", reviewId)));
            Map<String, Object> review = findReview(identity, realm, reviewId);
            model.put("review", review);
            model.put("open", review != null && OPEN.equals(review.get("status")) ? 1 : 0);
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

    /**
     * Opens a campaign and snapshots the grants in scope. A blank application reviews the
     * whole store; a named one reviews that application's roles and the direct permission
     * codes carrying its name.
     */
    public static Map<String, Object> open(IdentityService identity, RealmConfig realm,
            String openedBy, String name, String application) {
        requireRealm(identity, realm);
        String reviewId = "rv-" + UUID.randomUUID();
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("reviewId", reviewId);
        create.put("reviewName", require(name, "review name"));
        create.put("application", blankToNull(application));
        create.put("openedAt", Timestamp.from(Instant.now()));
        create.put("openedBy", blankToNull(openedBy));
        identity.executeUpdate(realm, IdentityContracts.CREATE_ACCESS_REVIEW, create);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("reviewId", reviewId);
        snapshot.put("application", blankToNull(application));
        int items = identity.executeUpdate(realm, IdentityContracts.SNAPSHOT_REVIEW_ITEMS,
                snapshot);
        return Map.of("opened", reviewId, "items", items);
    }

    /** Records one decision. A closed campaign takes none — its items are the record. */
    public static Map<String, Object> decide(IdentityService identity, RealmConfig realm,
            String decidedBy, String reviewId, String userId, String itemKind,
            String subjectCode, String decision, String note) {
        requireRealm(identity, realm);
        String verdict = require(decision, "decision");
        if (!KEEP.equals(verdict) && !REVOKE.equals(verdict)) {
            throw new TqlException(RoleAdmin.INPUT_REFUSED,
                    "A decision is '" + KEEP + "' or '" + REVOKE + "', not '" + verdict + "'");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("decision", verdict);
        params.put("decidedBy", blankToNull(decidedBy));
        params.put("decidedAt", Timestamp.from(Instant.now()));
        params.put("note", blankToNull(note));
        params.put("reviewId", require(reviewId, "review"));
        params.put("userId", require(userId, "user"));
        params.put("itemKind", require(itemKind, "item kind"));
        params.put("subjectCode", require(subjectCode, "subject code"));
        if (identity.executeUpdate(realm, IdentityContracts.DECIDE_REVIEW_ITEM, params) == 0) {
            throw new TqlException(RoleAdmin.INPUT_REFUSED,
                    "No open review item to decide: the campaign is closed, or the item is"
                            + " not part of it");
        }
        return Map.of("decided", subjectCode);
    }

    /**
     * Closes a campaign, executing every {@code revoke} through {@link RoleAdmin}. An item
     * whose grant has already gone is marked stale instead. The close is not transactional
     * across items on purpose: each revocation is an independent decision with its own trail
     * row, and one that fails should not silently undo the ones that worked.
     */
    public static Map<String, Object> close(IdentityService identity, RealmConfig realm,
            String closedBy, String reviewId) {
        requireRealm(identity, realm);
        String review = require(reviewId, "review");
        int revoked = 0;
        int stale = 0;
        for (Map<String, Object> item : identity.execute(realm,
                IdentityContracts.LIST_REVIEW_ITEMS, Map.of("reviewId", review))) {
            if (!REVOKE.equals(String.valueOf(item.get("decision")))) {
                continue;
            }
            String userId = String.valueOf(item.get("user_id"));
            String code = String.valueOf(item.get("subject_code"));
            boolean role = "role".equals(String.valueOf(item.get("item_kind")));
            if (!stillHeld(identity, realm, userId, code, role)) {
                identity.executeUpdate(realm, IdentityContracts.MARK_REVIEW_ITEM_STALE,
                        itemKey(review, item));
                stale++;
                continue;
            }
            // Through RoleAdmin, so the revocation passes the same validation and writes the
            // same trail row — attributed to the campaign that decided it, not to a plain
            // administrative edit.
            if (role) {
                RoleAdmin.unassignRole(identity, realm, actor(closedBy), userId, code,
                        SOURCE, review);
            } else {
                RoleAdmin.revokePermission(identity, realm, actor(closedBy), userId, code,
                        SOURCE, review);
            }
            revoked++;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("closedAt", Timestamp.from(Instant.now()));
        params.put("closedBy", blankToNull(closedBy));
        params.put("reviewId", review);
        if (identity.executeUpdate(realm, IdentityContracts.CLOSE_ACCESS_REVIEW, params) == 0) {
            throw new TqlException(RoleAdmin.INPUT_REFUSED,
                    "No open review '" + review + "' to close");
        }
        return Map.of("closed", review, "revoked", revoked, "stale", stale);
    }

    private static Map<String, Object> itemKey(String reviewId, Map<String, Object> item) {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("reviewId", reviewId);
        key.put("userId", item.get("user_id"));
        key.put("itemKind", item.get("item_kind"));
        key.put("subjectCode", item.get("subject_code"));
        return key;
    }

    /** Whether the snapshot's grant is still there to revoke at close. */
    private static boolean stillHeld(IdentityService identity, RealmConfig realm, String userId,
            String code, boolean role) {
        if (role) {
            return SeparationOfDuties.heldRoles(identity, realm, userId).contains(code);
        }
        Set<String> held = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : identity.execute(realm,
                IdentityContracts.FIND_DIRECT_PERMISSIONS_BY_USER_ID,
                Map.of("userId", userId))) {
            Object value = row.get("permission_code");
            if (value != null) {
                held.add(String.valueOf(value));
            }
        }
        return held.contains(code);
    }

    private static Map<String, Object> findReview(IdentityService identity, RealmConfig realm,
            String reviewId) {
        for (Map<String, Object> row : identity.execute(realm,
                IdentityContracts.LIST_ACCESS_REVIEWS, Map.of())) {
            if (reviewId.equals(String.valueOf(row.get("review_id")))) {
                return row;
            }
        }
        return null;
    }

    private static Map<String, Object> unavailable(Map<String, Object> model, String reason) {
        model.put("rows", List.of());
        model.put("items", List.of());
        model.put("review", null);
        model.put("open", 0);
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

    private static String actor(String value) {
        String named = blankToNull(value);
        return named == null ? "review" : named;
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
