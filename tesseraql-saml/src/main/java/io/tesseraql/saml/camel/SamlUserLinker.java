package io.tesseraql.saml.camel;

import io.tesseraql.identity.FederatedIdentities;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.saml.SamlException;
import io.tesseraql.security.Principal;

/**
 * Links a federated SAML identity to a local identity-store user (design ch. 10.14 userLink,
 * docs/application-roles.md structural decision 3): the immutable key is the persistent NameID
 * (or a configured assertion attribute), kept in {@code tql_user_identities} under the fixed
 * {@code saml} provider alias, so authorization uses locally-managed roles and permissions and a
 * login-id change at the IdP re-syncs the same account. Mapped attributes and the mutable profile
 * re-sync at every login; an unknown subject is JIT-provisioned when enabled.
 */
final class SamlUserLinker {

    /** The provider alias for the link table; a runtime configures at most one SAML IdP. */
    static final String PROVIDER = "saml";

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
    Principal resolve(FederatedIdentities.FederatedLogin login) {
        return FederatedIdentities.resolve(identity, realm, login, provision)
                .orElseThrow(() -> new SamlException(
                        "No local account is linked to '" + login.loginId() + "'"));
    }
}
