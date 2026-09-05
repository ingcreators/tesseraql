package io.tesseraql.identity;

import io.tesseraql.core.error.TqlException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The append-only grant trail (docs/access-governance.md structural decision 1): one row per
 * change to what a person holds, written at both paths that can make one — the administrator's
 * edit and the sign-in rule converge.
 *
 * <p>The route audit cannot stand in for this. It answers "which HTTP call happened", and the
 * rule converge is not an HTTP call at all: it runs inside principal resolution. A trail that
 * silently omitted the automatic path would read as complete while being wrong, so the record
 * is written where the grant is written.
 *
 * <p>The actor is a person's login id on the admin path and the mechanism's name on the
 * automatic one. Naming the signing-in user for a rule-produced grant would assert a decision
 * nobody made.
 */
public final class GrantHistory {

    /** A role was assigned, by any path. */
    public static final String ROLE_GRANTED = "role-granted";
    /** A role assignment was removed. */
    public static final String ROLE_REVOKED = "role-revoked";
    /** A permission code was granted directly to a person. */
    public static final String PERMISSION_GRANTED = "permission-granted";
    /** A direct permission grant was removed. */
    public static final String PERMISSION_REVOKED = "permission-revoked";
    /** Somebody was put in a group; the subject code is the group's. */
    public static final String GROUP_JOINED = "group-joined";
    /** Somebody left a group, or the group was deleted out from under them. */
    public static final String GROUP_LEFT = "group-left";

    /** An administrator's own edit. */
    public static final String SOURCE_ADMIN = "admin";
    /** The assignment rules' converge; also the actor, because no person decided. */
    public static final String SOURCE_RULE = "rule";

    private GrantHistory() {
    }

    /**
     * One change to what a person holds. {@code application} is what the writer knew when the
     * change was made and may be null; the read joins the role's current application beside
     * it, so a role that moved shows both truths.
     *
     * @param actor         the login id that decided, or the mechanism's name
     * @param subjectUserId whose access changed
     * @param changeKind    one of the {@code *_GRANTED}/{@code *_REVOKED} constants
     * @param subjectCode   the role or permission code the change is about
     * @param application   the application axis, when the writer knew it
     * @param source        the mechanism (admin, rule, elevation, request, review)
     * @param startsAt      the granted window's start, when the change granted one
     * @param endsAt        the granted window's end, when the change granted one
     * @param reason        the reason given, when the path asks for one
     * @param correlation   the elevation, request or review that caused it; null for an edit
     */
    public record Change(String actor, String subjectUserId, String changeKind,
            String subjectCode, String application, String source, Timestamp startsAt,
            Timestamp endsAt, String reason, String correlation) {

        /** The plain admin edit: a person, a code, no window, no cause to correlate. */
        public static Change admin(String actor, String userId, String changeKind,
                String code) {
            return new Change(actor, userId, changeKind, code, null, SOURCE_ADMIN, null, null,
                    null, null);
        }

        /** The admin edit that granted a validity window. */
        public static Change granted(String actor, String userId, String changeKind,
                String code, Timestamp startsAt, Timestamp endsAt) {
            return new Change(actor, userId, changeKind, code, null, SOURCE_ADMIN, startsAt,
                    endsAt, null, null);
        }

        /** The rule converge: the mechanism is both the actor and the source. */
        public static Change rule(String userId, String changeKind, String code) {
            return new Change(SOURCE_RULE, userId, changeKind, code, null, SOURCE_RULE, null,
                    null, null, null);
        }
    }

    /**
     * Appends one change. A store with no trail installed keeps its grant writes and records
     * nothing — the same degradation every optional contract takes, and the history page says
     * plainly that the store keeps none.
     *
     * <p>The design first said a history write failure should propagate. Measurement corrected
     * it: the standard schema is applied with {@code create table if not exists}, so an
     * existing store gains {@code tql_grant_history} only when the operator re-runs it, and
     * propagating would mean every grant write in that deployment fails until they do. Refusing
     * all administration over an uninstalled table is the wrong failure — the same lesson the
     * declared-role reconciler learned about never failing boot on an uninstalled store. Any
     * failure that is <em>not</em> an uninstalled feature still propagates.
     */
    public static void record(IdentityService identity, RealmConfig realm, Change change) {
        if (identity == null || realm == null || change == null) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("eventId", "gh-" + UUID.randomUUID());
        params.put("occurredAt", Timestamp.from(Instant.now()));
        params.put("actor", change.actor());
        params.put("subjectUserId", change.subjectUserId());
        params.put("changeKind", change.changeKind());
        params.put("subjectCode", change.subjectCode());
        params.put("application", change.application());
        params.put("source", change.source());
        params.put("startsAt", change.startsAt());
        params.put("endsAt", change.endsAt());
        params.put("reason", change.reason());
        params.put("correlation", change.correlation());
        try {
            identity.executeUpdate(realm, IdentityContracts.RECORD_GRANT_CHANGE, params);
        } catch (TqlException ex) {
            if (!IdentityService.featureUnavailable(ex)) {
                throw ex;
            }
        }
    }

    /** There is no trail to show: this realm's pack has no history contract. */
    private static final String NO_TRAIL = "This realm keeps no grant history.";

    /** There is too much trail to show, which is the opposite problem and must not read alike. */
    private static final String TRAIL_TOO_LARGE = "This trail is too large to show at once.";

    /**
     * The history page's model: the trail newest first, optionally narrowed to one person or
     * one application. A realm without the contract answers unavailable with its reason, the
     * shape every degraded identity model in this surface uses.
     */
    public static Map<String, Object> historyModel(IdentityService identity, RealmConfig realm,
            String userId, String application) {
        Map<String, Object> model = new LinkedHashMap<>();
        if (identity == null || realm == null) {
            return unavailable(model, NO_TRAIL, "No identity realm is configured");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", blankToNull(userId));
        params.put("application", blankToNull(application));
        params.put("since", null);
        try {
            model.put("rows", identity.execute(realm, IdentityContracts.LIST_GRANT_HISTORY,
                    params));
            model.put("available", 1);
        } catch (TqlException ex) {
            // The trail is read whole by design - the contract's own header says no parameter
            // means the whole store, and the store is append-only - so it is the one identity
            // read that meets the bound in normal operation. Answer it with a page that says so,
            // not a 500 an operator cannot clear from the page.
            //
            // NOT by widening featureUnavailable to admit this code: that method is consulted by
            // every degrading caller in the module, and an over-large read reported as an absent
            // feature is how a truncated constraint set finds no conflict.
            if (IdentityService.readTooLarge(ex)) {
                return unavailable(model, TRAIL_TOO_LARGE, "Narrow the trail with a user or"
                        + " application filter, or raise tesseraql.identity.maxRows.");
            }
            if (!IdentityService.featureUnavailable(ex)) {
                throw ex;
            }
            return unavailable(model, NO_TRAIL, ex.getMessage());
        }
        model.put("userId", blankToNull(userId));
        model.put("application", blankToNull(application));
        return model;
    }

    /** The panel cannot show a trail, and says which of the two reasons it is. */
    private static Map<String, Object> unavailable(Map<String, Object> model, String title,
            String reason) {
        model.put("rows", List.of());
        model.put("available", 0);
        model.put("title", title);
        model.put("reason", reason);
        return model;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || "null".equals(value) ? null : value.trim();
    }
}
