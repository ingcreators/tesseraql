package io.tesseraql.runtime;

import io.tesseraql.core.credential.CredentialTokenStore;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.identity.IdentityContracts;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.security.session.SessionStore;
import java.util.Map;

/**
 * The IAM admin's disable action, per user and in bulk (docs/session-administration.md,
 * docs/credential-lifecycle.md): the status flips, every session of the subject ends now, and
 * every live credential token of the login dies with it — a mailed invite or reset link cannot
 * undo what the operator did. Withdrawing an invitation is this same action on an INVITED
 * account. One method for both routes so they cannot drift.
 */
final class IdentityDisables {

    /** The caller's own account: an administrator cannot disable themselves. */
    static final TqlErrorCode SELF = new TqlErrorCode(TqlDomain.IAM, 4037);

    private IdentityDisables() {
    }

    /**
     * Disables {@code userId} unless it is the caller's own account ({@code subject} is the
     * session principal's), and returns how many user rows changed — zero for an unknown id,
     * which the bulk endpoint reports rather than counts (docs/silent-tolerance.md O10).
     *
     * <p>Self-disable is refused rather than allowed: the same request would end the caller's
     * own session, land its redirect on the login page, and — for the last holder of
     * {@code tql.iam.admin.write} — leave the deployment with no administrator the console can
     * bring back (docs/audit-low-leads.md slice 4, G43).
     */
    static int disable(IdentityService identity, RealmConfig realm, SessionStore sessions,
            CredentialTokenStore tokens, String userId, String subject) {
        if (userId.equals(subject)) {
            throw new TqlException(SELF, "You cannot disable your own account");
        }
        int disabled = identity.executeUpdate(realm, IdentityContracts.DISABLE_USER,
                Map.of("userId", userId));
        sessions.invalidateOthersFor(userId, "");
        if (tokens != null) {
            identity.execute(realm, IdentityContracts.FIND_USER_BY_ID, Map.of("userId", userId))
                    .stream().findFirst()
                    .ifPresent(user -> tokens.revoke(String.valueOf(user.get("login_id"))));
        }
        return disabled;
    }
}
