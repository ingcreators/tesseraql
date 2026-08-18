package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.service.ServiceProviders;
import io.tesseraql.operations.app.InstalledApp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The portal's member list (docs/root-portal.md): catalogue order, derived addresses, and the
 * relay's own tenant-entitlement semantics — a principal declaring no tenant is not checked,
 * exactly as the relay skips the check when no {@code X-Tenant-Id} arrives.
 */
class PortalProvidersTest {

    private static final List<InstalledApp> MEMBERS = List.of(
            new InstalledApp("orders", "1.0.0", "orders", List.of()),
            new InstalledApp("billing", "1.0.0", "billing", List.of("tenant-b")));

    @Test
    void listsEveryMemberAtItsDerivedAddressForATenantlessPrincipal() {
        assertThat(list(null)).containsExactly(
                Map.of("name", "orders", "href", "/orders"),
                Map.of("name", "billing", "href", "/billing"));
    }

    @Test
    void filtersByTenantEntitlementWithTheRelaysSemantics() {
        assertThat(list("tenant-b")).extracting(row -> row.get("name"))
                .containsExactly("orders", "billing");
        assertThat(list("tenant-a")).extracting(row -> row.get("name"))
                .as("an entitlement list excludes the tenants not on it; an empty list is"
                        + " every tenant")
                .containsExactly("orders");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(String tenantId) {
        ServiceProviders providers = new ServiceProviders();
        PortalProviders.register(providers, MEMBERS);
        Map<String, Object> params = new HashMap<>();
        if (tenantId != null) {
            params.put("tenantId", tenantId);
        }
        return (List<Map<String, Object>>) providers.require("portal.apps.list").invoke(params);
    }
}
