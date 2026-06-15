package io.tesseraql.compiler.binding;

import io.tesseraql.core.mask.Masking;
import io.tesseraql.security.Principal;
import io.tesseraql.security.policy.PolicyEngine;
import io.tesseraql.yaml.model.ResponseSpec.FieldPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies output field authorization and masking to a resolved response body (design ch. 33.3,
 * 34.2). Fields are matched by name anywhere in the body tree (e.g. inside row maps).
 *
 * <p>Resolution order per field: an explicit {@code visible: false} hides it; otherwise a
 * {@code policy} the principal does not satisfy hides it; otherwise a {@code mask} or the masking
 * default for the {@code classification} masks the value.
 */
public final class FieldPolicyApplier {

    private enum Action {
        KEEP, HIDE, MASK
    }

    private final Map<String, FieldPolicy> fields;
    private final PolicyEngine policyEngine;
    private final Principal principal;
    /** Row flag columns referenced by an {@code unmaskWhen}; stripped from the response. */
    private final Set<String> flagColumns;

    public FieldPolicyApplier(Map<String, FieldPolicy> fields, PolicyEngine policyEngine,
            Principal principal) {
        this.fields = fields;
        this.policyEngine = policyEngine;
        this.principal = principal;
        this.flagColumns = new LinkedHashSet<>();
        for (FieldPolicy policy : fields.values()) {
            if (policy.unmaskWhen() != null && !policy.unmaskWhen().isBlank()) {
                flagColumns.add(policy.unmaskWhen());
            }
        }
    }

    /** Returns a transformed copy of the body with field policies applied. */
    public Object apply(Object body) {
        if (body instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                String name = String.valueOf(key);
                if (flagColumns.contains(name)) {
                    return; // internal scope-flag column, never emitted
                }
                FieldPolicy policy = fields.get(name);
                Action action = policy == null ? Action.KEEP : decide(policy, map);
                switch (action) {
                    case HIDE -> {
                        /* drop the field */ }
                    case MASK -> result.put(name, Masking.apply(maskStrategy(policy), value));
                    case KEEP -> result.put(name, apply(value));
                }
            });
            return result;
        }
        if (body instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(element -> result.add(apply(element)));
            return result;
        }
        return body;
    }

    private Action decide(FieldPolicy policy, Map<?, ?> row) {
        if (Boolean.FALSE.equals(policy.visible())) {
            return Action.HIDE;
        }
        if (policy.policy() != null && !permits(policy.policy())) {
            return Action.HIDE;
        }
        // Row-level masking: masked unless this row's scope flag is truthy (roadmap Phase 29).
        if (policy.unmaskWhen() != null && !policy.unmaskWhen().isBlank()) {
            return truthy(row.get(policy.unmaskWhen())) ? Action.KEEP : Action.MASK;
        }
        if (policy.mask() != null) {
            return Action.MASK;
        }
        String defaultAction = Masking.defaultActionFor(policy.classification());
        if ("hide".equals(defaultAction)) {
            return Action.HIDE;
        }
        if ("mask".equals(defaultAction)) {
            return Action.MASK;
        }
        return Action.KEEP;
    }

    private boolean permits(String policyId) {
        // Without a policy engine, fail safe by denying policy-gated fields.
        return policyEngine != null && policyEngine.permits(policyId, principal);
    }

    private static String maskStrategy(FieldPolicy policy) {
        return policy.mask() != null ? policy.mask() : "fixed";
    }

    /** A scope flag is truthy as boolean true, a non-zero number, or {@code "1"}/{@code "true"}. */
    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.longValue() != 0;
        }
        String text = String.valueOf(value);
        return "1".equals(text) || "true".equalsIgnoreCase(text);
    }
}
