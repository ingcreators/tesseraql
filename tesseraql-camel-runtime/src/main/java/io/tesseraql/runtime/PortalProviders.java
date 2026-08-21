package io.tesseraql.runtime;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.service.ServiceProviders;
import io.tesseraql.operations.app.InstalledApp;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.LoginRedirects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The portal's providers, registered only when this runtime is the stack surface — the one
 * runtime whose {@link HostContext} carries the member list (docs/root-portal.md). A member
 * runtime never receives the list, so no member can see its siblings, and a member route naming
 * {@code portal.apps.list} meets the ordinary unknown-provider refusal.
 */
final class PortalProviders {

    private PortalProviders() {
    }

    static void register(ServiceProviders providers, List<InstalledApp> members,
            RuntimeContext context) {
        providers.register("portal.apps.list", params -> appsFor(members, params));
        providers.register("portal.roles.list", params -> rolesFor(members, context, params));
    }

    /**
     * The members this principal may reach, as {@code {name, href}} rows in catalogue order.
     *
     * <p>Two filters, two questions (docs/stack-shells.md structural decision 1): the catalogue
     * says which <em>tenants</em> an application serves — applied with the relay's own
     * semantics, a principal declaring no tenant is not checked, exactly as the relay skips the
     * check when no {@code X-Tenant-Id} arrives — and the {@code tql.app.use.<name>} grant says
     * which <em>people</em> use it, deny by default: no grants, no tiles. The member's own
     * fence refuses on the same atom, so what a user sees and what they can reach are one
     * answer.
     */
    private static List<Map<String, Object>> appsFor(List<InstalledApp> members,
            Map<String, Object> params) {
        Object declared = params.get("tenantId");
        String tenantId = declared == null ? null : String.valueOf(declared);
        List<String> permissions = params.get("permissions") instanceof List<?> codes
                ? codes.stream().map(String::valueOf).toList()
                : List.of();
        return members.stream()
                .filter(member -> tenantId == null || member.isEntitled(tenantId))
                .filter(member -> io.tesseraql.security.policy.Atoms.appUse(permissions,
                        member.name()))
                .map(member -> Map.<String, Object>of(
                        "name", member.name(),
                        "href", member.basePath()))
                .toList();
    }

    /**
     * The role picker's model (docs/application-roles.md structural decision 4): the caller's
     * roles for one member, by display name where the store can answer it, each as a direct
     * link into that role's {@code /_as} address carrying the sanitized return target. The
     * grants come from the caller's own session principal — resolved from the authenticated
     * exchange, never caller-writable — so the picker can only ever offer what is held.
     */
    private static Map<String, Object> rolesFor(List<InstalledApp> members, RuntimeContext context,
            Map<String, Object> params) {
        String app = params.get("app") == null ? "" : String.valueOf(params.get("app"));
        boolean known = members.stream().anyMatch(member -> member.name().equals(app));
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("app", app);
        model.put("known", known);
        List<Map<String, Object>> roles = new ArrayList<>();
        model.put("roles", roles);
        if (!known) {
            return model;
        }
        Map<String, String> names = roleNames(context, app);
        String within = returnTarget(params.get("redirect"), app);
        for (Principal.RoleGrant grant : grants(params.get("roleGrants"))) {
            if (!app.equals(grant.application())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", grant.role());
            row.put("name", names.getOrDefault(grant.role(), grant.role()));
            row.put("href", "/" + app + "/_as/"
                    + io.tesseraql.camel.BasePath.encodeSegment(grant.role()) + within);
            roles.add(row);
        }
        return model;
    }

    /** {@code role_code → role_name} for one application; a realm without the contract: codes. */
    private static Map<String, String> roleNames(RuntimeContext context, String app) {
        Map<String, String> names = new LinkedHashMap<>();
        if (context == null) {
            return names;
        }
        io.tesseraql.identity.IdentityService identity = context.lookup(
                TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                io.tesseraql.identity.IdentityService.class);
        io.tesseraql.identity.RealmConfig realm = context.lookup(
                TesseraqlProperties.IDENTITY_REALM_BEAN,
                io.tesseraql.identity.RealmConfig.class);
        if (identity == null || realm == null) {
            return names;
        }
        try {
            for (Map<String, Object> row : identity.execute(realm,
                    io.tesseraql.identity.IdentityContracts.LIST_ROLES_BY_APPLICATION,
                    Map.of("application", app))) {
                if (row.get("role_code") != null && row.get("role_name") != null) {
                    names.put(String.valueOf(row.get("role_code")),
                            String.valueOf(row.get("role_name")));
                }
            }
        } catch (io.tesseraql.core.error.TqlException ex) {
            if (!io.tesseraql.identity.ContractResolver.MISSING_CONTRACT.equals(ex.code())) {
                throw ex;
            }
        }
        return names;
    }

    /**
     * The member-relative return target of the picker's links: the {@code redirect} wire path,
     * sanitized, held to this member's own prefix, any stale {@code _as} segment stripped —
     * everything else falls back to the member's root.
     */
    private static String returnTarget(Object redirect, String app) {
        String prefix = "/" + app;
        String wire = LoginRedirects.sanitize(redirect == null ? null : String.valueOf(redirect),
                prefix);
        if (!wire.equals(prefix) && !wire.startsWith(prefix + "/")) {
            return "";
        }
        String within = wire.substring(prefix.length());
        if (within.startsWith("/_as/")) {
            int next = within.indexOf('/', "/_as/".length());
            within = next < 0 ? "" : within.substring(next);
        }
        return within;
    }

    private static List<Principal.RoleGrant> grants(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Principal.RoleGrant> grants = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof Principal.RoleGrant grant) {
                grants.add(grant);
            }
        }
        return grants;
    }
}
