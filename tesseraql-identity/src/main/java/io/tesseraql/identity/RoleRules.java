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
import java.util.function.BiPredicate;

/**
 * Attribute-to-role assignment rules (docs/application-roles.md structural decision 3). A
 * rule grants one role when its conditions all match the user; conditions are comparisons,
 * never expressions — the decision-table discipline — so rules stay enumerable and their
 * effect stays auditable. Evaluation happens at sign-in and materializes into
 * {@code tql_user_roles} with {@code source = 'rule'} provenance: a manual assignment always
 * survives recompute, and a rule assignment survives only while a rule produces it.
 */
public final class RoleRules {

    /** A rule's shape is refused: an unknown condition kind, a missing value, or an org-subtree condition without the managed org foundation. */
    public static final TqlErrorCode RULE_REFUSED = new TqlErrorCode(TqlDomain.IAM, 4032);

    /** The condition kinds — {@code eq}, {@code in}, {@code neq}, {@code not-in}, {@code group}, {@code subtree}. */
    public static final Set<String> KINDS = Set.of("eq", "in", "neq", "not-in", "group",
            "subtree");

    private RoleRules() {
    }

    /** Refuses a malformed condition before it reaches the store. */
    public static void validateCondition(String attribute, String kind, String value,
            boolean orgManaged) {
        if (kind == null || !KINDS.contains(kind)) {
            throw new TqlException(RULE_REFUSED, "Unknown condition kind '" + kind
                    + "' — one of " + KINDS);
        }
        if (value == null || value.isBlank()) {
            throw new TqlException(RULE_REFUSED, "A condition needs a value");
        }
        if ("subtree".equals(kind) && !orgManaged) {
            throw new TqlException(RULE_REFUSED, "An org-subtree condition needs the managed"
                    + " org-unit foundation (tesseraql.orgunit.mode: managed)");
        }
        if (!"group".equals(kind) && (attribute == null || attribute.isBlank())) {
            throw new TqlException(RULE_REFUSED,
                    "A '" + kind + "' condition names the attribute it matches");
        }
    }

    /**
     * Evaluates the enabled rules against one user. Rows are
     * {@code find-enabled-rule-conditions} rows (rule_id, role_code, attribute_name,
     * match_kind, value; a condition-less rule grants unconditionally). Within one rule: rows sharing an attribute and a positive kind
     * ({@code eq}/{@code in}) OR together; every negative row must hold; distinct
     * attributes AND. Returns the produced role codes.
     */
    public static Set<String> evaluate(List<Map<String, Object>> rows,
            Map<String, String> attributes, List<String> groups,
            BiPredicate<String, String> orgDescendant) {
        Map<String, List<Map<String, Object>>> byRule = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byRule.computeIfAbsent(String.valueOf(row.get("rule_id")), k -> new ArrayList<>())
                    .add(row);
        }
        Set<String> produced = new LinkedHashSet<>();
        for (List<Map<String, Object>> rule : byRule.values()) {
            String role = String.valueOf(rule.get(0).get("role_code"));
            if (matches(rule, attributes, groups, orgDescendant)) {
                produced.add(role);
            }
        }
        return produced;
    }

    private static boolean matches(List<Map<String, Object>> rule,
            Map<String, String> attributes, List<String> groups,
            BiPredicate<String, String> orgDescendant) {
        // Positive rows OR within (attribute, family); negatives and subtree/group each hold.
        Map<String, Boolean> positiveByAttribute = new LinkedHashMap<>();
        for (Map<String, Object> row : rule) {
            String kind = String.valueOf(row.get("match_kind"));
            String value = String.valueOf(row.get("value"));
            if ("null".equals(String.valueOf(row.get("match_kind")))) {
                continue; // a condition-less rule's left-join row
            }
            String attribute = row.get("attribute_name") == null
                    ? null
                    : String.valueOf(row.get("attribute_name"));
            switch (kind) {
                case "eq", "in" -> {
                    String actual = attributes.get(attribute);
                    positiveByAttribute.merge(attribute, value.equals(actual), Boolean::logicalOr);
                }
                case "neq", "not-in" -> {
                    if (value.equals(attributes.get(attribute))) {
                        return false;
                    }
                }
                case "group" -> {
                    if (groups == null || !groups.contains(value)) {
                        return false;
                    }
                }
                case "subtree" -> {
                    String actual = attributes.get(attribute);
                    if (actual == null || !orgDescendant.test(value, actual)) {
                        return false;
                    }
                }
                default -> {
                    return false;
                }
            }
        }
        return positiveByAttribute.values().stream().allMatch(Boolean::booleanValue);
    }

    /** The closure predicate over the managed org foundation, for evaluation call sites. */
    public static BiPredicate<String, String> orgPredicate(IdentityService identity,
            RealmConfig realm) {
        return (ancestor, descendant) -> {
            List<Map<String, Object>> matched = identity.execute(realm,
                    IdentityContracts.IS_ORG_DESCENDANT,
                    Map.of("ancestorId", ancestor, "descendantId", descendant));
            return !matched.isEmpty()
                    && ((Number) matched.get(0).get("matched")).longValue() > 0;
        };
    }

    /** The standalone recompute (the admin's "recompute now"): read, evaluate, converge. */
    public static Set<String> recompute(IdentityService identity, RealmConfig realm,
            String userId) {
        if (realm.type() != RealmConfig.RealmType.MANAGED
                || !realm.capabilities().roleWriteAllowed()) {
            return Set.of();
        }
        Map<String, Object> byUser = Map.of("userId", userId);
        Map<String, String> attributes = new LinkedHashMap<>();
        for (Map<String, Object> row : identity.execute(realm,
                IdentityContracts.LIST_USER_ATTRIBUTES, byUser)) {
            attributes.put(String.valueOf(row.get("name")),
                    row.get("value") == null ? null : String.valueOf(row.get("value")));
        }
        List<String> groups = new ArrayList<>();
        for (Map<String, Object> row : identity.execute(realm,
                IdentityContracts.FIND_GROUPS_BY_USER_ID, byUser)) {
            groups.add(String.valueOf(row.get("group_code")));
        }
        return materialize(identity, realm, userId, evaluate(
                identity.execute(realm, IdentityContracts.FIND_ENABLED_RULE_CONDITIONS,
                        Map.of()),
                attributes, groups, orgPredicate(identity, realm)));
    }

    /**
     * Converges one user's rule-produced assignments: grants the missing, revokes the
     * no-longer-produced, and never touches an {@code admin} row (the revoke keys on
     * {@code source = 'rule'}; the grant skips a code the user already holds by any path).
     */
    public static Set<String> materialize(IdentityService identity, RealmConfig realm,
            String userId, Set<String> produced) {
        List<String> existing = new ArrayList<>();
        for (Map<String, Object> row : identity.execute(realm,
                IdentityContracts.LIST_RULE_ASSIGNMENTS_BY_USER_ID, Map.of("userId", userId))) {
            existing.add(String.valueOf(row.get("role_code")));
        }
        for (String role : produced) {
            if (!existing.contains(role)) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("userId", userId);
                params.put("roleCode", role);
                identity.executeUpdate(realm, IdentityContracts.GRANT_USER_ROLE_RULE, params);
                // The converge is the second grant write path, so it records like the first
                // (docs/access-governance.md structural decision 1). It is not an HTTP call,
                // which is exactly why the route audit could never have covered it.
                GrantHistory.record(identity, realm,
                        GrantHistory.Change.rule(userId, GrantHistory.ROLE_GRANTED, role));
            }
        }
        for (String role : existing) {
            if (!produced.contains(role)) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("userId", userId);
                params.put("roleCode", role);
                identity.executeUpdate(realm, IdentityContracts.REVOKE_USER_ROLE_RULE, params);
                GrantHistory.record(identity, realm,
                        GrantHistory.Change.rule(userId, GrantHistory.ROLE_REVOKED, role));
            }
        }
        return produced;
    }
}
