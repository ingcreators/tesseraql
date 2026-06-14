package io.tesseraql.security;

import io.tesseraql.security.apikey.ApiKeyConfig;
import io.tesseraql.security.policy.Policy;
import java.util.Map;
import java.util.Optional;

/**
 * Resolved security configuration: named policies and authentication settings (design ch. 10.9,
 * 11). Built by the runtime from {@code tesseraql.security.*} and bound into the Camel registry.
 *
 * @param policies authorization policies keyed by id
 * @param jwt      bearer JWT verification settings, or null when no bearer auth is configured
 * @param apiKeys  API-key settings, or null when no API-key auth is configured
 */
public record SecurityConfig(Map<String, Policy> policies, JwtConfig jwt, ApiKeyConfig apiKeys) {

    public SecurityConfig {
        policies = policies == null ? Map.of() : Map.copyOf(policies);
    }

    /** A configuration without API-key auth. */
    public SecurityConfig(Map<String, Policy> policies, JwtConfig jwt) {
        this(policies, jwt, null);
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
     * @param jwksUri          RS256 JWKS endpoint, an alternative to a static {@code publicKey}
     * @param jwks             JWKS cache settings (never null; defaults applied)
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
            String jwksUri,
            JwksConfig jwks,
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
            jwks = jwks == null ? new JwksConfig(null, null, null) : jwks;
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

    /**
     * JWKS fetch and cache settings (design ch. 11.1).
     *
     * @param cacheTtl      how long a fetched key set is trusted before a refresh (default 10m)
     * @param refreshFloor  minimum interval between unknown-{@code kid} refetches (default 1m)
     * @param requestTimeout JWKS HTTP connect/request timeout (default 5s)
     */
    public record JwksConfig(
            java.time.Duration cacheTtl,
            java.time.Duration refreshFloor,
            java.time.Duration requestTimeout) {

        public JwksConfig {
            cacheTtl = cacheTtl == null ? java.time.Duration.ofMinutes(10) : cacheTtl;
            refreshFloor = refreshFloor == null ? java.time.Duration.ofMinutes(1) : refreshFloor;
            requestTimeout = requestTimeout == null
                    ? java.time.Duration.ofSeconds(5)
                    : requestTimeout;
        }
    }
}
