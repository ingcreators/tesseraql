package io.tesseraql.identity;

import io.tesseraql.yaml.app.DeclaredRoles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converges an application's declared roles into the identity store at boot
 * (docs/application-roles.md slice 3). Declared roles are upserted on the application axis
 * with their bundles replaced by the declaration's codes plus the application's implicit
 * {@code tql.app.use.<name>} atom — holding a duty in an application implies being allowed
 * through its fence, as a visible store row. A previously declared role the declaration no
 * longer contains is stamped {@code orphaned} (assignments kept, reported, never deleted);
 * re-declaring it revives it. Manually created roles ({@code source = 'admin'}) are never
 * touched. A realm that is not managed, or does not allow role writes, reconciles nothing.
 */
public final class DeclaredRoleReconciler {

    private static final Logger LOG = LoggerFactory.getLogger(DeclaredRoleReconciler.class);

    private DeclaredRoleReconciler() {
    }

    /** Returns the orphaned role codes (for the caller's log line and for tests). */
    public static List<String> reconcile(IdentityService identity, RealmConfig realm,
            String appName, List<DeclaredRoles.DeclaredRole> declared) {
        if (identity == null || realm == null || appName == null
                || realm.type() != RealmConfig.RealmType.MANAGED
                || !realm.capabilities().roleWriteAllowed()) {
            return List.of();
        }
        // The probe doubles as the schema check: a managed realm whose store was never
        // installed (identity-schema is a deliberate operator step — "a fresh database has
        // no users until this goal runs") must not fail the boot over a declaration it
        // cannot reconcile yet. Only this first read is tolerated; a failure while WRITING
        // still propagates, so a half-applied declaration never passes silently.
        List<Map<String, Object>> existing;
        try {
            existing = identity.execute(realm, IdentityContracts.LIST_ROLES_BY_APPLICATION,
                    Map.of("application", appName));
        } catch (io.tesseraql.core.error.TqlException ex) {
            if (!declared.isEmpty()) {
                LOG.warn("Declared roles of '{}' were not reconciled — the identity store is"
                        + " not reachable or not installed ({}); run identity-schema and"
                        + " restart to converge", appName, ex.getMessage());
            }
            return List.of();
        }
        if (declared.isEmpty() && existing.isEmpty()) {
            return List.of();
        }
        String useAtom = io.tesseraql.security.policy.Atoms.APP_USE_PREFIX + appName;
        Set<String> declaredCodes = new LinkedHashSet<>();
        for (DeclaredRoles.DeclaredRole role : declared) {
            declaredCodes.add(role.code());
            Map<String, Object> upsert = new LinkedHashMap<>();
            upsert.put("roleId", role.code());
            upsert.put("roleCode", role.code());
            upsert.put("roleName", role.name() == null || role.name().isEmpty()
                    ? role.code()
                    : role.name());
            upsert.put("application", appName);
            identity.executeUpdate(realm, IdentityContracts.UPSERT_DECLARED_ROLE, upsert);
            identity.executeUpdate(realm, IdentityContracts.CLEAR_ROLE_PERMISSIONS,
                    Map.of("roleCode", role.code()));
            List<String> bundle = new ArrayList<>(role.permissions());
            bundle.add(useAtom);
            for (String code : bundle) {
                Map<String, Object> ensure = new LinkedHashMap<>();
                ensure.put("permissionId", code);
                ensure.put("permissionCode", code);
                ensure.put("permissionName", code);
                identity.executeUpdate(realm, IdentityContracts.ENSURE_PERMISSION, ensure);
                Map<String, Object> assign = new LinkedHashMap<>();
                assign.put("roleCode", role.code());
                assign.put("permissionCode", code);
                identity.executeUpdate(realm, IdentityContracts.ASSIGN_ROLE_PERMISSION, assign);
            }
        }
        List<String> orphaned = new ArrayList<>();
        for (Map<String, Object> row : existing) {
            String code = String.valueOf(row.get("role_code"));
            if ("declared".equals(String.valueOf(row.get("source")))
                    && !declaredCodes.contains(code)) {
                Map<String, Object> stamp = new LinkedHashMap<>();
                stamp.put("roleCode", code);
                stamp.put("source", "orphaned");
                identity.executeUpdate(realm, IdentityContracts.SET_ROLE_SOURCE, stamp);
                orphaned.add(code);
            }
        }
        if (!orphaned.isEmpty()) {
            LOG.warn("{} declared role(s) of '{}' are no longer declared and are marked"
                    + " orphaned (assignments kept): {}", orphaned.size(), appName, orphaned);
        }
        return orphaned;
    }
}
