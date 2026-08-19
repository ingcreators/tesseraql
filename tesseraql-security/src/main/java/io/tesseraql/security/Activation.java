package io.tesseraql.security;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives the active-view principal for one acting role (docs/application-roles.md structural
 * decision 4): reachability reads the union, conduct reads the active view. The active view
 * inside member {@code M} is all stack-wide roles, plus the one activated {@code M}-role (if
 * any), minus every other application-scoped role — with permissions recomputed to match: the
 * active roles' bundles plus the principal's direct grants. A principal with empty
 * {@code roleGrants} (a pre-upgrade session, a claim-asserted bearer, a {@code sql} realm
 * without the attribution contracts) has no attribution to narrow by, so its union <em>is</em>
 * its active view and it passes through untouched.
 */
public final class Activation {

    /**
     * TQL-SEC-4148: the caller asked to act as a role they do not hold for this application.
     *
     * <p>The shipped SEC family had no "authenticated, allowed in, wrong capacity" answer: a
     * browser gets the role picker (the human fix is choosing again), everyone else a 403.
     */
    public static final TqlErrorCode WRONG_CAPACITY = new TqlErrorCode(TqlDomain.SEC, 4148);

    /**
     * The claim carrying the acting role on a swapped principal — how the audit trail and the
     * token mint read "acted as which capacity" without an eleventh record component.
     */
    public static final String ACTING_ROLE_CLAIM = "actingRole";

    private Activation() {
    }

    /** The grants scoped to one application — what a caller may activate for that member. */
    public static List<Principal.RoleGrant> grantsFor(Principal principal, String application) {
        List<Principal.RoleGrant> scoped = new ArrayList<>();
        for (Principal.RoleGrant grant : principal.roleGrants()) {
            if (application.equals(grant.application())) {
                scoped.add(grant);
            }
        }
        return scoped;
    }

    /**
     * The active-view principal for {@code member} with {@code actingRole} activated —
     * {@code null} activates nothing, leaving only the stack-wide roles active (absence
     * denies). The caller has already validated that the role is held for this member;
     * activation can only ever narrow, because the view is built from the principal's own
     * grants and nothing else.
     */
    public static Principal activate(Principal principal, String member, String actingRole) {
        if (principal.roleGrants().isEmpty()) {
            return principal;
        }
        List<String> roles = new ArrayList<>();
        Set<String> permissions = new LinkedHashSet<>();
        for (Principal.RoleGrant grant : principal.roleGrants()) {
            boolean active = grant.application() == null
                    || (actingRole != null && member.equals(grant.application())
                            && grant.role().equals(actingRole));
            if (active) {
                roles.add(grant.role());
                permissions.addAll(grant.permissions());
            }
        }
        permissions.addAll(principal.directPermissions());
        Map<String, Object> claims = new LinkedHashMap<>(principal.claims());
        if (actingRole != null) {
            claims.put(ACTING_ROLE_CLAIM, actingRole);
        }
        return new Principal(principal.subject(), principal.loginId(),
                principal.displayName(), principal.tenantId(), principal.groups(), roles,
                List.copyOf(permissions), claims, principal.roleGrants(),
                principal.directPermissions());
    }

    /** The acting role a (possibly swapped) principal carries, or null. */
    public static String actingRole(Principal principal) {
        if (principal == null) {
            return null;
        }
        Object acting = principal.claims().get(ACTING_ROLE_CLAIM);
        return acting == null ? null : String.valueOf(acting);
    }
}
