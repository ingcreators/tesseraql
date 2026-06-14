package io.tesseraql.security;

import io.tesseraql.security.policy.Policy;
import java.util.Map;
import java.util.Optional;

/**
 * Resolved security configuration: named policies and JWT verification settings (design ch. 10.9,
 * 11). Built by the runtime from {@code tesseraql.security.*} and bound into the Camel registry.
 *
 * @param policies authorization policies keyed by id
 * @param jwt      bearer JWT verification settings
 */
public record SecurityConfig(Map<String, Policy> policies, JwtConfig jwt) {

    public SecurityConfig {
        policies = policies == null ? Map.of() : Map.copyOf(policies);
    }

    public Optional<Policy> policy(String id) {
        return Optional.ofNullable(policies.get(id));
    }

    /**
     * Bearer JWT verification settings and claim mappings (design ch. 11.1).
     *
     * @param algorithm        signature algorithm, {@code HS256} (default) or {@code RS256}
     * @param secret           shared HMAC secret for HS256 verification
     * @param publicKey        RS256 static verification key (PEM, X.509 certificate, or JWK JSON)
     * @param issuer           expected {@code iss}, or null to skip the check
     * @param clockSkew        leeway applied to {@code exp}/{@code nbf}; defaults to zero
     * @param rolesClaim       claim holding the roles array
     * @param permissionsClaim claim holding the permissions array
     * @param groupsClaim      claim holding the groups array
     * @param tenantClaim      claim holding the tenant id
     * @param loginClaim       claim holding the login id
     * @param nameClaim        claim holding the display name
     */
    public record JwtConfig(
            String algorithm,
            String secret,
            String publicKey,
            String issuer,
            java.time.Duration clockSkew,
            String rolesClaim,
            String permissionsClaim,
            String groupsClaim,
            String tenantClaim,
            String loginClaim,
            String nameClaim) {

        public JwtConfig {
            algorithm = algorithm == null || algorithm.isBlank()
                    ? "HS256"
                    : algorithm.toUpperCase(java.util.Locale.ROOT);
            clockSkew = clockSkew == null ? java.time.Duration.ZERO : clockSkew;
            rolesClaim = orDefault(rolesClaim, "roles");
            permissionsClaim = orDefault(permissionsClaim, "permissions");
            groupsClaim = orDefault(groupsClaim, "groups");
            tenantClaim = orDefault(tenantClaim, "tenant_id");
            loginClaim = orDefault(loginClaim, "preferred_username");
            nameClaim = orDefault(nameClaim, "name");
        }

        private static String orDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
