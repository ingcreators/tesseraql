package io.tesseraql.yaml.app;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An application's declared duty roles — {@code tesseraql.security.roles}
 * (docs/application-roles.md structural decision 1). Each entry names a role the application
 * ships: its code (carrying the application's own name as its first segment, like a permission
 * code), a display name, and the bundle of the application's own permission codes it delivers.
 * The runtime reconciles the declaration into the identity store at boot; the deployment
 * assigns people.
 */
public final class DeclaredRoles {

    /**
     * TQL-YAML-1407: an application role declaration is refused — its code does not begin with
     * the application's own name, its bundle references a code outside the application's own
     * namespace, or the declaration repeats a code. Reported at lint and refused at boot, like
     * the policy-code fence.
     */
    public static final TqlErrorCode INVALID = new TqlErrorCode(TqlDomain.YAML, 1407);

    /** One declared role: code, display name (defaults to the code), and its bundle. */
    public record DeclaredRole(String code, String name, List<String> permissions) {
        public DeclaredRole {
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }
    }

    private DeclaredRoles() {
    }

    /** Parses {@code tesseraql.security.roles} (a list of maps); anything else is empty. */
    public static List<DeclaredRole> parse(Object raw) {
        List<DeclaredRole> roles = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return roles;
        }
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> map)) {
                continue;
            }
            List<String> permissions = new ArrayList<>();
            if (map.get("permissions") instanceof List<?> codes) {
                codes.forEach(code -> permissions.add(String.valueOf(code)));
            }
            roles.add(new DeclaredRole(
                    map.get("code") == null ? "" : String.valueOf(map.get("code")).trim(),
                    map.get("name") == null ? null : String.valueOf(map.get("name")).trim(),
                    permissions));
        }
        return roles;
    }

    /** Every violation in the declaration, shared by lint and the boot refusal. */
    public static List<String> violations(String appName, List<DeclaredRole> roles) {
        List<String> violations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DeclaredRole role : roles) {
            if (role.code().isEmpty()) {
                violations.add("a declared role has no code");
                continue;
            }
            if (!seen.add(role.code())) {
                violations.add("role '" + role.code() + "' is declared more than once");
            }
            if (role.code().equals("tql") || role.code().startsWith("tql.")) {
                violations.add("role '" + role.code() + "' sits under the framework's own"
                        + " mark — declare application roles outside tql.");
            } else if (!role.code().startsWith(appName + ".")
                    || role.code().length() == appName.length() + 1) {
                violations.add("role '" + role.code() + "' does not begin with this"
                        + " application's own name — application role codes are '" + appName
                        + ".<duty>' (e.g. '" + appName + ".approver'), so two applications"
                        + " cannot silently share one role");
            }
            for (String permission : role.permissions()) {
                String violation = PolicyCodes.violation(appName, permission);
                if (violation != null) {
                    violations.add("role '" + role.code() + "': " + violation);
                }
            }
        }
        return violations;
    }

    /**
     * The boot backstop: parses and validates the declaration, throwing the first violation —
     * for a configuration that never ran through the linter. An absent or unsafe application
     * name is not judged here; the name rule owns that refusal.
     */
    public static List<DeclaredRole> require(String appName, Object raw) {
        List<DeclaredRole> roles = parse(raw);
        if (appName == null || roles.isEmpty()) {
            return roles;
        }
        List<String> violations = violations(appName, roles);
        if (!violations.isEmpty()) {
            throw new TqlException(INVALID, violations.get(0));
        }
        return roles;
    }
}
