package io.tesseraql.runtime;

import io.tesseraql.identity.IdentityContracts;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.saml.SamlException;
import io.tesseraql.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Links a federated SAML identity to a local identity-store user (design ch. 10.14 userLink): it
 * resolves the local {@link Principal} by login id so authorization uses locally-managed roles and
 * permissions rather than IdP-asserted ones, optionally provisioning a local user the first time an
 * unknown federated subject signs in (just-in-time provisioning).
 */
final class SamlUserLinker {

    private final IdentityService identity;
    private final RealmConfig realm;
    private final boolean provision;

    SamlUserLinker(IdentityService identity, RealmConfig realm, boolean provision) {
        this.identity = identity;
        this.realm = realm;
        this.provision = provision;
    }

    /**
     * Resolves the local principal for a federated login, provisioning it when enabled and absent.
     *
     * @throws SamlException when no local account exists and provisioning is disabled
     */
    Principal resolve(String loginId, String displayName, String email, String tenantId) {
        Optional<Principal> existing = identity.resolvePrincipal(realm, loginId, tenantId);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!provision) {
            throw new SamlException("No local account is linked to '" + loginId + "'");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", UUID.randomUUID().toString());
        params.put("loginId", loginId);
        params.put("displayName", displayName == null || displayName.isBlank() ? loginId : displayName);
        params.put("email", email);
        params.put("status", "ACTIVE");
        params.put("tenantId", tenantId);
        identity.executeUpdate(realm, IdentityContracts.CREATE_USER, params);

        return identity.resolvePrincipal(realm, loginId, tenantId)
                .orElseThrow(() -> new SamlException("Provisioned user not found: " + loginId));
    }
}
