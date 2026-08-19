package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.service.ServiceProviders;
import io.tesseraql.operations.app.InstalledApp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The portal's member list (docs/root-portal.md): catalogue order, derived addresses, the
 * relay's own tenant-entitlement semantics — a principal declaring no tenant is not checked,
 * exactly as the relay skips the check when no {@code X-Tenant-Id} arrives — and the
 * {@code tql.app.use} axis beside it (docs/stack-shells.md structural decision 1): tiles are
 * the caller's grants applied to the member list, deny by default.
 */
class PortalProvidersTest {

    private static final List<InstalledApp> MEMBERS = List.of(
            new InstalledApp("orders", "1.0.0", "orders", List.of()),
            new InstalledApp("billing", "1.0.0", "billing", List.of("tenant-b")));

    @Test
    void listsEveryMemberAtItsDerivedAddressForATenantlessPrincipal() {
        assertThat(list(null, List.of("tql.app.use.*"))).containsExactly(
                Map.of("name", "orders", "href", "/orders"),
                Map.of("name", "billing", "href", "/billing"));
    }

    @Test
    void filtersByTenantEntitlementWithTheRelaysSemantics() {
        assertThat(list("tenant-b", List.of("tql.app.use.*")))
                .extracting(row -> row.get("name"))
                .containsExactly("orders", "billing");
        assertThat(list("tenant-a", List.of("tql.app.use.*")))
                .extracting(row -> row.get("name"))
                .as("an entitlement list excludes the tenants not on it; an empty list is"
                        + " every tenant")
                .containsExactly("orders");
    }

    @Test
    void filtersByTheAppUseGrantDenyByDefault() {
        assertThat(list(null, List.of("tql.app.use.billing")))
                .extracting(row -> row.get("name"))
                .as("a named grant reaches exactly its application")
                .containsExactly("billing");
        assertThat(list(null, List.of()))
                .as("no tql.app.use grants, no tiles — not every entitled member")
                .isEmpty();
        assertThat(list(null, null))
                .as("an absent permissions binding denies, never widens")
                .isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(String tenantId, List<String> permissions) {
        ServiceProviders providers = new ServiceProviders();
        PortalProviders.register(providers, MEMBERS, null);
        Map<String, Object> params = new HashMap<>();
        if (tenantId != null) {
            params.put("tenantId", tenantId);
        }
        if (permissions != null) {
            params.put("permissions", permissions);
        }
        return (List<Map<String, Object>>) providers.require("portal.apps.list").invoke(params);
    }
}
