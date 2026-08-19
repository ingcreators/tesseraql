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
        Map<String, Object> claims,
        List<RoleGrant> roleGrants,
        List<String> directPermissions) {

    /**
     * One held role with its application axis and the permission bundle it delivers — the
     * attribution the active-view recompute needs (docs/application-roles.md structural
     * decision 4). {@code application} is null for a stack-wide role. Populated only by
     * store-resolved principals; claim-asserted principals (bearer, API key, mTLS) carry
     * none and never activate.
     */
    public record RoleGrant(String role, String application, List<String> permissions) {
        public RoleGrant {
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }
    }

    public Principal {
        groups = groups == null ? List.of() : List.copyOf(groups);
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        // Claims may carry null values (e.g. optional columns), so use a null-tolerant copy.
        claims = claims == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(claims));
        // A pre-upgrade principal_json deserializes with neither list: the union stays the
        // active view for such sessions, exactly as before the application axis existed.
        roleGrants = roleGrants == null ? List.of() : List.copyOf(roleGrants);
        directPermissions = directPermissions == null
                ? List.of()
                : List.copyOf(directPermissions);
    }

    /** The pre-application-axis shape: every construction site without grant attribution. */
    public Principal(String subject, String loginId, String displayName, String tenantId,
            List<String> groups, List<String> roles, List<String> permissions,
            Map<String, Object> claims) {
        this(subject, loginId, displayName, tenantId, groups, roles, permissions, claims,
                List.of(), List.of());
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
