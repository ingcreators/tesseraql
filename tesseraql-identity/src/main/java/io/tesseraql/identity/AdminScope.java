package io.tesseraql.identity;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.policy.Atoms;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an administrator may touch (docs/access-governance.md structural decision 7).
 *
 * <p>Containment is the whole design of delegated administration, so it lives here — beside
 * the writes — rather than in the pages. A page that hides a button is a convenience; a write
 * that refuses is the control.
 *
 * <p>A holder of the store-wide {@code tql.iam.admin.write} is unscoped. A holder of
 * {@code tql.iam.admin.app.<name>} may touch only that application's own access, and three
 * boundaries define "own":
 *
 * <ul>
 *   <li>a <b>role</b> must be classified to an application they hold the atom for — a
 *       stack-wide role belongs to the deployment, never to one application;
 *   <li>a <b>permission code</b> must carry that application's name as its first segment, the
 *       same classifier the rest of the model uses;
 *   <li>nothing under the {@code tql.} mark is ever theirs, whatever its shape. Delegating
 *       {@code orders} must not become a path to granting {@code tql.app.deploy.*}.
 * </ul>
 *
 * <p>Store-wide instruments — assignment rules, separation-of-duties constraints,
 * eligibilities — stay store-wide. Scoping them raises its own containment questions and is
 * recorded as out of scope rather than half-answered here.
 */
public final class AdminScope {

    /** A delegated administrator reached outside the application they administer. */
    public static final TqlErrorCode OUT_OF_SCOPE = new TqlErrorCode(TqlDomain.IAM, 4036);

    private final boolean storeWide;
    private final Set<String> applications;

    private AdminScope(boolean storeWide, Set<String> applications) {
        this.storeWide = storeWide;
        this.applications = Set.copyOf(applications);
    }

    /** The unscoped administrator: everything in the store is theirs. */
    public static AdminScope storeWide() {
        return new AdminScope(true, Set.of());
    }

    /** Administration confined to the named applications. */
    public static AdminScope forApplications(Set<String> applications) {
        return new AdminScope(false, applications);
    }

    /**
     * The scope a caller's granted permission codes buy. The store-wide write atom wins
     * outright; otherwise every {@code tql.iam.admin.app.<name>} names one application.
     *
     * <p>The terminal wildcard {@code tql.iam.admin.app.*} is deliberately <b>not</b> read as
     * store-wide: it delegates every application, which is still not the stack-wide roles or
     * the framework's own atoms. A caller who should have those is granted the store-wide atom.
     */
    public static AdminScope of(List<String> permissions, List<String> members) {
        if (permissions == null) {
            return forApplications(Set.of());
        }
        if (permissions.contains("tql.iam.admin.write")) {
            return storeWide();
        }
        Set<String> scoped = new LinkedHashSet<>();
        if (permissions.contains(Atoms.IAM_ADMIN_APP_PREFIX + "*") && members != null) {
            scoped.addAll(members);
        }
        for (String permission : permissions) {
            if (permission.startsWith(Atoms.IAM_ADMIN_APP_PREFIX)
                    && !permission.endsWith("*")) {
                scoped.add(permission.substring(Atoms.IAM_ADMIN_APP_PREFIX.length()));
            }
        }
        return forApplications(scoped);
    }

    /** Whether this scope reaches everything, the store-wide administrator's answer. */
    public boolean isStoreWide() {
        return storeWide;
    }

    /** The applications this scope administers; empty for a store-wide one. */
    public Set<String> applications() {
        return applications;
    }

    /** Whether anything at all may be written — a caller with neither atom writes nothing. */
    public boolean canWrite() {
        return storeWide || !applications.isEmpty();
    }

    /**
     * Refuses a role outside the scope. {@code application} is the role's own classification,
     * which the caller has read from the store — a role's application is a store fact, never
     * something a request may assert.
     */
    public void requireRole(String roleCode, String application) {
        if (storeWide) {
            return;
        }
        if (application == null || application.isBlank()) {
            throw refuse("'" + roleCode + "' is a stack-wide role, which belongs to the"
                    + " deployment rather than to any one application");
        }
        if (!applications.contains(application)) {
            throw refuse("'" + roleCode + "' belongs to '" + application + "', and you"
                    + " administer " + applications);
        }
    }

    /** Refuses a permission code outside the scope, and every framework atom always. */
    public void requirePermission(String code) {
        if (code != null && code.startsWith(Atoms.MARK)) {
            // The framework's own vocabulary is never one application's to hand out, even
            // to an administrator of the application the atom happens to name.
            if (!storeWide) {
                throw refuse("'" + code + "' is a framework grant, which no delegated"
                        + " administrator may hand out");
            }
            return;
        }
        if (storeWide) {
            return;
        }
        for (String application : applications) {
            if (code != null && code.startsWith(application + ".")) {
                return;
            }
        }
        throw refuse("'" + code + "' does not belong to " + applications);
    }

    /** Refuses a store-wide instrument: rules, constraints and eligibilities are not scoped. */
    public void requireStoreWide(String what) {
        if (!storeWide) {
            throw refuse(what + " is a store-wide instrument, not one application's");
        }
    }

    /** The role's application as the store classifies it, for {@link #requireRole}. */
    public static String applicationOf(IdentityService identity, RealmConfig realm,
            String roleCode) {
        for (Map<String, Object> row : identity.execute(realm, IdentityContracts.LIST_ROLES,
                Map.of())) {
            if (roleCode.equals(String.valueOf(row.get("role_code")))) {
                Object application = row.get("application");
                return application == null ? null : String.valueOf(application);
            }
        }
        return null;
    }

    private TqlException refuse(String message) {
        return TqlException.builder(OUT_OF_SCOPE)
                .message(message)
                .details(Map.of("scope", message))
                .build();
    }
}
