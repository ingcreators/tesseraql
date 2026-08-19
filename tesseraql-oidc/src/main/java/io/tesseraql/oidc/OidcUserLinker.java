package io.tesseraql.oidc;

import io.tesseraql.identity.FederatedIdentities;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.security.Principal;

/**
 * Links a federated OIDC identity to a local identity-store user (design ch. 10.14 userLink,
 * docs/application-roles.md structural decision 3): the immutable key is the token's
 * {@code iss} + {@code sub} pair, kept in {@code tql_user_identities}, so authorization uses
 * locally-managed roles and permissions and a login-id change at the OP re-syncs the same
 * account. Mapped claims and the mutable profile re-sync at every login; an unknown subject is
 * JIT-provisioned when enabled. Mirrors the SAML user linker.
 */
final class OidcUserLinker {

    private final IdentityService identity;
    private final RealmConfig realm;
    private final boolean provision;

    OidcUserLinker(IdentityService identity, RealmConfig realm, boolean provision) {
        this.identity = identity;
        this.realm = realm;
        this.provision = provision;
    }

    /**
     * Resolves the local principal for a federated login, provisioning it when enabled and absent.
     *
     * @throws OidcException when no local account exists and provisioning is disabled
     */
    Principal resolve(FederatedIdentities.FederatedLogin login) {
        return FederatedIdentities.resolve(identity, realm, login, provision)
                .orElseThrow(() -> new OidcException(
                        "No local account is linked to '" + login.loginId() + "'"));
    }
}
