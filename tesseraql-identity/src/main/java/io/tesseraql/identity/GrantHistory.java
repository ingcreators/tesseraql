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
     * Appends one change. A realm whose pack has no history contract keeps its grant writes
     * and records nothing — the same degradation every optional contract takes. Any other
     * failure propagates: losing the record of a change that did happen is not tolerable, so
     * the write that caused it fails with the history write.
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
            if (!ContractResolver.MISSING_CONTRACT.equals(ex.code())) {
                throw ex;
            }
        }
    }

    /**
     * The history page's model: the trail newest first, optionally narrowed to one person or
     * one application. A realm without the contract answers unavailable with its reason, the
     * shape every degraded identity model in this surface uses.
     */
    public static Map<String, Object> historyModel(IdentityService identity, RealmConfig realm,
            String userId, String application) {
        Map<String, Object> model = new LinkedHashMap<>();
        if (identity == null || realm == null) {
            return unavailable(model, "No identity realm is configured");
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
            if (!ContractResolver.MISSING_CONTRACT.equals(ex.code())) {
                throw ex;
            }
            return unavailable(model, ex.getMessage());
        }
        model.put("userId", blankToNull(userId));
        model.put("application", blankToNull(application));
        return model;
    }

    private static Map<String, Object> unavailable(Map<String, Object> model, String reason) {
        model.put("rows", List.of());
        model.put("available", 0);
        model.put("reason", reason);
        return model;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || "null".equals(value) ? null : value.trim();
    }
}
