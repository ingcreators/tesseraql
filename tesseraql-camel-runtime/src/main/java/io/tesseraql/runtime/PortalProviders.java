package io.tesseraql.runtime;

import io.tesseraql.core.service.ServiceProviders;
import io.tesseraql.operations.app.InstalledApp;
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

    static void register(ServiceProviders providers, List<InstalledApp> members) {
        providers.register("portal.apps.list", params -> appsFor(members, params));
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
}
