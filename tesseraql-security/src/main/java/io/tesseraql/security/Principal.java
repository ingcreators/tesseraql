package io.tesseraql.security;

import java.util.List;
import java.util.Map;

/**
 * The authenticated caller resolved from a request (design ch. 10.9.2).
 *
 * <p>This is the value referenced by {@code principal.*} source expressions, for example
 * {@code principal.sub} or {@code principal.claim.tenant_id}, when binding SQL parameters.
 *
 * @param subject     stable subject identifier (JWT {@code sub})
 * @param loginId     human login id, when available
 * @param displayName display name, when available
 * @param tenantId    tenant id, when available
 * @param groups      group memberships
 * @param roles       granted roles
 * @param permissions granted permissions
 * @param claims      raw claims, exposed as {@code principal.claim.<name>}
 */
public record Principal(
        String subject,
        String loginId,
        String displayName,
        String tenantId,
        List<String> groups,
        List<String> roles,
        List<String> permissions,
        Map<String, Object> claims) {

    public Principal {
        groups = groups == null ? List.of() : List.copyOf(groups);
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        // Claims may carry null values (e.g. optional columns), so use a null-tolerant copy.
        claims = claims == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(claims));
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    /** Exposes {@code claim} as a nested map so {@code principal.claim.<name>} resolves. */
    public Map<String, Object> claim() {
        return claims;
    }
}
