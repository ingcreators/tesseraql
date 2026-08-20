package io.tesseraql.identity;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static separation of duties (docs/access-governance.md structural decision 2): a constraint
 * names two or more role codes nobody may hold at once, and it is checked at the only two
 * paths that can create a role assignment — the administrator's write and the assignment
 * rules' converge.
 *
 * <p>The two checkpoints answer differently on purpose. The admin write <em>refuses</em>, because
 * a person is making a decision and can be told why. The rule converge <em>withholds</em> the
 * role it would have produced, because it runs inside sign-in: refusing there would lock
 * somebody out of the product over two attribute rules disagreeing, which is the wrong
 * failure. The withheld grant is not silent — nothing was written, so the violation report
 * shows the constraint and the person keeps the access they legitimately hold.
 *
 * <p>Dynamic separation of duties is not here because it already shipped. One acting role per
 * application per tab, with {@code acting_role} in the audit, is exactly the rule a dynamic
 * constraint would express.
 */
public final class SeparationOfDuties {

    /** A grant is refused: it would put a person on both sides of a blocking constraint. */
    public static final TqlErrorCode CONFLICT = new TqlErrorCode(TqlDomain.IAM, 4034);

    /** The constraint refuses the grant outright. */
    public static final String BLOCK = "block";
    /** The constraint records the conflict and lets the grant through. */
    public static final String WARN = "warn";

    private SeparationOfDuties() {
    }

    /**
     * One constraint: a name, a severity, and the role codes that are mutually exclusive.
     *
     * @param id       the constraint's own id
     * @param name     what it is called on the admin page and in the refusal
     * @param severity {@link #BLOCK} or {@link #WARN}
     * @param roles    the mutually exclusive codes; fewer than two can never be violated
     */
    public record Constraint(String id, String name, String severity, Set<String> roles) {

        public Constraint {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }

        /** The codes in {@code held} that this constraint covers. */
        public Set<String> conflictingWith(Set<String> held) {
            Set<String> hit = new LinkedHashSet<>();
            for (String role : roles) {
                if (held.contains(role)) {
                    hit.add(role);
                }
            }
            return hit;
        }
    }

    /** Groups {@code list-sod-constraints} rows (one role code per row) into constraints. */
    public static List<Constraint> constraintsOf(List<Map<String, Object>> rows) {
        Map<String, String[]> meta = new LinkedHashMap<>();
        Map<String, Set<String>> roles = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("constraint_id"));
            meta.putIfAbsent(id, new String[]{
                    row.get("constraint_name") == null
                            ? id
                            : String.valueOf(row.get("constraint_name")),
                    row.get("severity") == null ? BLOCK : String.valueOf(row.get("severity"))});
            Object role = row.get("role_code");
            if (role != null) {
                roles.computeIfAbsent(id, key -> new LinkedHashSet<>())
                        .add(String.valueOf(role));
            }
        }
        List<Constraint> constraints = new ArrayList<>();
        meta.forEach((id, names) -> constraints.add(
                new Constraint(id, names[0], names[1], roles.getOrDefault(id, Set.of()))));
        return constraints;
    }

    /**
     * The constraints this realm holds, or an empty list when it keeps none — a realm without
     * the contract enforces nothing and refuses nothing, the degradation every optional
     * contract in this surface takes.
     */
    public static List<Constraint> load(IdentityService identity, RealmConfig realm) {
        if (identity == null || realm == null) {
            return List.of();
        }
        try {
            return constraintsOf(identity.execute(realm,
                    IdentityContracts.LIST_SOD_CONSTRAINTS, Map.of()));
        } catch (TqlException ex) {
            if (!IdentityService.featureUnavailable(ex)) {
                throw ex;
            }
            return List.of();
        }
    }

    /**
     * The constraints {@code candidate} would violate against {@code alreadyHeld}, at the given
     * severity. A code the person already holds is not a conflict with itself, so the candidate
     * is excluded from the held set before comparing.
     */
    public static List<Constraint> violations(List<Constraint> constraints, Set<String> alreadyHeld,
            String candidate, String severity) {
        Set<String> others = new LinkedHashSet<>(alreadyHeld);
        others.remove(candidate);
        List<Constraint> hit = new ArrayList<>();
        for (Constraint constraint : constraints) {
            if (!severity.equals(constraint.severity())
                    || !constraint.roles().contains(candidate)) {
                continue;
            }
            if (!constraint.conflictingWith(others).isEmpty()) {
                hit.add(constraint);
            }
        }
        return hit;
    }

    /**
     * Refuses the grant when {@code candidate} would put the person on both sides of a blocking
     * constraint. The constraint's name and the conflicting codes ride {@code details}, the
     * envelope's one declared-safe channel, because the generic message alone would tell an
     * administrator "Conflict" and leave them nothing to act on.
     */
    public static void requireNoBlockingConflict(List<Constraint> constraints,
            Set<String> alreadyHeld, String candidate) {
        for (Constraint constraint : violations(constraints, alreadyHeld, candidate, BLOCK)) {
            Set<String> others = new LinkedHashSet<>(alreadyHeld);
            others.remove(candidate);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("constraint", constraint.name());
            details.put("requested", candidate);
            details.put("conflictsWith", List.copyOf(constraint.conflictingWith(others)));
            throw TqlException.builder(CONFLICT)
                    .message("Separation of duties '" + constraint.name() + "' refuses '"
                            + candidate + "': the user already holds "
                            + constraint.conflictingWith(others)
                            + ". Revoke one side before granting the other.")
                    .details(details)
                    .build();
        }
    }

    /** The role codes a user holds now, by any path, for a checkpoint's comparison. */
    public static Set<String> heldRoles(IdentityService identity, RealmConfig realm,
            String userId) {
        Set<String> held = new LinkedHashSet<>();
        for (Map<String, Object> row : identity.execute(realm,
                IdentityContracts.FIND_ROLES_BY_USER_ID, Map.of("userId", userId))) {
            Object code = row.get("role_code");
            if (code != null) {
                held.add(String.valueOf(code));
            }
        }
        return held;
    }

    /**
     * The constraints page's model: every constraint with its codes, and everybody already on
     * both sides of one. The violation report exists because a constraint added to a store
     * where people already hold both sides has violations the moment it is created.
     */
    public static Map<String, Object> constraintsModel(IdentityService identity,
            RealmConfig realm) {
        Map<String, Object> model = new LinkedHashMap<>();
        if (identity == null || realm == null) {
            return unavailable(model, "No identity realm is configured");
        }
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Constraint constraint : constraintsOf(identity.execute(realm,
                    IdentityContracts.LIST_SOD_CONSTRAINTS, Map.of()))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("constraint_id", constraint.id());
                row.put("constraint_name", constraint.name());
                row.put("severity", constraint.severity());
                row.put("roles", List.copyOf(constraint.roles()));
                rows.add(row);
            }
            model.put("rows", rows);
            model.put("violations", identity.execute(realm,
                    IdentityContracts.FIND_SOD_VIOLATIONS, Map.of()));
            model.put("roles", identity.execute(realm, IdentityContracts.LIST_ROLES, Map.of()));
            model.put("available", 1);
        } catch (TqlException ex) {
            if (!IdentityService.featureUnavailable(ex)) {
                throw ex;
            }
            return unavailable(model, ex.getMessage());
        }
        model.put("writable", realm.capabilities().roleWriteAllowed() ? 1 : 0);
        return model;
    }

    private static Map<String, Object> unavailable(Map<String, Object> model, String reason) {
        model.put("rows", List.of());
        model.put("violations", List.of());
        model.put("roles", List.of());
        model.put("available", 0);
        model.put("writable", 0);
        model.put("reason", reason);
        return model;
    }
}
