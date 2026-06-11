package io.tesseraql.identity;

import io.tesseraql.security.Principal;
import io.tesseraql.security.password.PasswordVerifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates a login id and password against an identity realm (design ch. 10.8): it looks up
 * the credential via the {@code find-credential-by-login} contract, verifies the password, checks
 * the account is active, then resolves the full principal.
 */
public final class PasswordAuthenticator {

    private final IdentityService identity;
    private final PasswordVerifier verifier = new PasswordVerifier();

    public PasswordAuthenticator(IdentityService identity) {
        this.identity = identity;
    }

    /** Returns the authenticated principal, or empty if the login or password is invalid. */
    public Optional<Principal> authenticate(RealmConfig realm, String loginId, String password,
            String tenantId) {
        Map<String, Object> lookup = new LinkedHashMap<>();
        lookup.put("loginId", loginId);
        lookup.put("tenantId", tenantId);
        List<Map<String, Object>> credentials =
                identity.execute(realm, IdentityContracts.FIND_CREDENTIAL_BY_LOGIN, lookup);
        if (credentials.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> credential = credentials.get(0);
        if (!"ACTIVE".equals(asString(credential.get("status")))) {
            return Optional.empty();
        }
        boolean valid = verifier.verify(password,
                asString(credential.get("password_hash")),
                asString(credential.get("password_algo")),
                asString(credential.get("password_params")));
        if (!valid) {
            return Optional.empty();
        }
        return identity.resolvePrincipal(realm, loginId, tenantId);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
