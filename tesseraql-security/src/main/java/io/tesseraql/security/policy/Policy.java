package io.tesseraql.security.policy;

import io.tesseraql.security.Principal;
import java.util.List;

/**
 * An authorization policy: the principal is permitted if any of its rules match (design ch.
 * 10.9.1). With no rules, or when a referenced policy is absent, access is denied by default
 * (design ch. 20.14).
 *
 * @param id    the policy id, e.g. {@code users.read}
 * @param anyOf the alternative rules; satisfying any one grants access
 */
public record Policy(String id, List<Rule> anyOf) {

    public Policy {
        anyOf = anyOf == null ? List.of() : List.copyOf(anyOf);
    }

    public boolean permits(Principal principal) {
        return anyOf.stream().anyMatch(rule -> rule.matches(principal));
    }

    /**
     * A single alternative within a policy. Exactly one of role/permission/claim is set.
     *
     * @param role       required role
     * @param permission required permission
     * @param claimName  required claim name (paired with {@code claimValue})
     * @param claimValue required claim value
     */
    public record Rule(String role, String permission, String claimName, String claimValue) {

        public static Rule ofRole(String role) {
            return new Rule(role, null, null, null);
        }

        public static Rule ofPermission(String permission) {
            return new Rule(null, permission, null, null);
        }

        public static Rule ofClaim(String name, String value) {
            return new Rule(null, null, name, value);
        }

        public boolean matches(Principal principal) {
            if (role != null) {
                return principal.hasRole(role);
            }
            if (permission != null) {
                return principal.hasPermission(permission);
            }
            if (claimName != null) {
                return String.valueOf(principal.claims().get(claimName)).equals(claimValue);
            }
            return false;
        }
    }
}
