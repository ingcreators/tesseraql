package io.tesseraql.identity;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a federated (SAML/OIDC) login against the identity store through the immutable
 * identity link (docs/application-roles.md structural decision 3): {@code tql_user_identities}
 * maps a provider's stable subject to the local user, so a login-id change at the IdP re-syncs
 * the same account instead of provisioning a duplicate. On every sign-in the mutable profile
 * (login id, display name, email) and the mapped attributes re-sync before the principal is
 * resolved, so this sign-in's assignment rules already see the fresh values.
 *
 * <p>A realm without the link contracts (a {@code sql} realm) or without user-write capability
 * degrades to the pre-link behavior: resolve by login id, provision when enabled — exactly what
 * the linkers did before the link table existed.
 */
public final class FederatedIdentities {

    private FederatedIdentities() {
    }

    /**
     * One federated sign-in: the immutable key and the mutable, re-synced profile.
     *
     * @param provider    the identity provider's stable identifier (the OIDC issuer; a fixed
     *                    alias for SAML, whose subject is already IdP-scoped)
     * @param subject     the provider's immutable subject (OIDC {@code sub}, the persistent
     *                    SAML NameID or a configured assertion attribute)
     * @param loginId     the mutable login id asserted for this sign-in
     * @param displayName the asserted display name, or null when the IdP did not map one
     * @param email       the asserted email, or null when the IdP did not map one
     * @param tenantId    the asserted tenant, or null
     * @param attributes  mapped attribute values to re-sync; a null value deletes the attribute
     */
    public record FederatedLogin(String provider, String subject, String loginId,
            String displayName, String email, String tenantId, Map<String, String> attributes) {

        public FederatedLogin {
            attributes = attributes == null ? Map.of() : attributes;
        }
    }

    /**
     * Resolves the local principal for a federated sign-in, linking and optionally provisioning
     * on first contact. Empty means no local account exists and provisioning is disabled — the
     * caller turns that into its protocol-specific refusal.
     */
    public static Optional<Principal> resolve(IdentityService identity, RealmConfig realm,
            FederatedLogin login, boolean provision) {
        if (login.subject() == null || login.subject().isBlank()
                || login.provider() == null || login.provider().isBlank()
                || !realm.capabilities().userWriteAllowed()) {
            return legacyResolve(identity, realm, login, provision);
        }
        try {
            Map<String, Object> key = new LinkedHashMap<>();
            key.put("provider", login.provider());
            key.put("subject", login.subject());
            List<Map<String, Object>> linked = identity.execute(realm,
                    IdentityContracts.FIND_USER_BY_IDENTITY, key);
            if (!linked.isEmpty()) {
                Map<String, Object> user = linked.get(0);
                String userId = asString(user.get("user_id"));
                syncProfile(identity, realm, userId, user, login);
                syncAttributes(identity, realm, userId, login.attributes());
                return identity.resolvePrincipal(realm, login.loginId(), login.tenantId());
            }

            // No link yet: an account that pre-dates the link table (or was created by hand)
            // is adopted by login id once, then owns its link forever.
            Optional<Map<String, Object>> existing = findByLogin(identity, realm, login);
            if (existing.isPresent()) {
                String userId = asString(existing.get().get("user_id"));
                identity.executeUpdate(realm, IdentityContracts.LINK_USER_IDENTITY, Map.of(
                        "userId", userId, "provider", login.provider(),
                        "subject", login.subject()));
                syncProfile(identity, realm, userId, existing.get(), login);
                syncAttributes(identity, realm, userId, login.attributes());
                return identity.resolvePrincipal(realm, login.loginId(), login.tenantId());
            }

            if (!provision) {
                return Optional.empty();
            }
            String userId = UUID.randomUUID().toString();
            createUser(identity, realm, userId, login);
            identity.executeUpdate(realm, IdentityContracts.LINK_USER_IDENTITY, Map.of(
                    "userId", userId, "provider", login.provider(),
                    "subject", login.subject()));
            syncAttributes(identity, realm, userId, login.attributes());
            return identity.resolvePrincipal(realm, login.loginId(), login.tenantId());
        } catch (TqlException ex) {
            if (!ContractResolver.MISSING_CONTRACT.equals(ex.code())) {
                throw ex;
            }
            return legacyResolve(identity, realm, login, provision);
        }
    }

    /** The pre-link behavior: resolve by login id, provision when enabled and absent. */
    private static Optional<Principal> legacyResolve(IdentityService identity, RealmConfig realm,
            FederatedLogin login, boolean provision) {
        Optional<Principal> existing = identity.resolvePrincipal(realm, login.loginId(),
                login.tenantId());
        if (existing.isPresent()) {
            return existing;
        }
        if (!provision) {
            return Optional.empty();
        }
        createUser(identity, realm, UUID.randomUUID().toString(), login);
        return identity.resolvePrincipal(realm, login.loginId(), login.tenantId());
    }

    private static void createUser(IdentityService identity, RealmConfig realm, String userId,
            FederatedLogin login) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", userId);
        params.put("loginId", login.loginId());
        params.put("displayName",
                login.displayName() == null || login.displayName().isBlank()
                        ? login.loginId()
                        : login.displayName());
        params.put("email", login.email());
        params.put("status", "ACTIVE");
        params.put("tenantId", login.tenantId());
        identity.executeUpdate(realm, IdentityContracts.CREATE_USER, params);
    }

    /** Re-syncs login id, display name and email when the assertion moved any of them. */
    private static void syncProfile(IdentityService identity, RealmConfig realm, String userId,
            Map<String, Object> current, FederatedLogin login) {
        boolean loginMoved = changed(current.get("login_id"), login.loginId());
        boolean nameMoved = login.displayName() != null && !login.displayName().isBlank()
                && changed(current.get("display_name"), login.displayName());
        boolean emailMoved = login.email() != null
                && changed(current.get("email"), login.email());
        if (!loginMoved && !nameMoved && !emailMoved) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", userId);
        params.put("loginId", login.loginId());
        params.put("displayName", nameMoved ? login.displayName() : null);
        params.put("email", emailMoved ? login.email() : null);
        identity.executeUpdate(realm, IdentityContracts.UPDATE_FEDERATED_USER, params);
    }

    /**
     * Converges declared attribute values for one user: set what the source sent, delete what it
     * withheld (a null or blank value). Shared by the SSO linkers and the SCIM attribute capture —
     * one write discipline for every path that lands attributes with the user.
     */
    public static void syncAttributes(IdentityService identity, RealmConfig realm,
            String userId, Map<String, String> attributes) {
        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
            if (attribute.getKey() == null || attribute.getKey().isBlank()) {
                continue;
            }
            Map<String, Object> key = new LinkedHashMap<>();
            key.put("userId", userId);
            key.put("name", attribute.getKey().trim());
            identity.executeUpdate(realm, IdentityContracts.DELETE_USER_ATTRIBUTE, key);
            if (attribute.getValue() != null && !attribute.getValue().isBlank()) {
                Map<String, Object> params = new LinkedHashMap<>(key);
                params.put("value", attribute.getValue().trim());
                identity.executeUpdate(realm, IdentityContracts.INSERT_USER_ATTRIBUTE, params);
            }
        }
    }

    private static Optional<Map<String, Object>> findByLogin(IdentityService identity,
            RealmConfig realm, FederatedLogin login) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("loginId", login.loginId());
        params.put("tenantId", login.tenantId());
        List<Map<String, Object>> users = identity.execute(realm,
                IdentityContracts.FIND_USER_BY_LOGIN, params);
        return users.isEmpty() ? Optional.empty() : Optional.of(users.get(0));
    }

    private static boolean changed(Object current, String asserted) {
        return !java.util.Objects.equals(current == null ? null : String.valueOf(current),
                asserted);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
