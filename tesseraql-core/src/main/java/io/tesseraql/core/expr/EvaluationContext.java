package io.tesseraql.core.expr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Variable scope for evaluating 2-way SQL directive expressions (design ch. 8.1).
 *
 * <p>The root scope is a name to value map (the SQL parameters). Dotted paths such as
 * {@code principal.claim.tenant_id} are resolved segment by segment against {@link Map} keys,
 * JavaBean getters, or accessible fields. A small set of virtual properties ({@code size},
 * {@code length}, {@code empty}) is supported on collections, maps, arrays, and strings.
 *
 * <p>Resolution is read-only and side-effect free; no arbitrary method invocation is permitted,
 * keeping expression evaluation within the whitelist guardrail (design ch. 20.6).
 */
public final class EvaluationContext {

    private final Map<String, Object> root;

    public EvaluationContext(Map<String, Object> root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /**
     * Resolves a dotted path to a value, or {@code null} if any segment is missing or null.
     */
    public Object resolve(java.util.List<String> path) {
        if (path.isEmpty()) {
            return null;
        }
        Object current = root.get(path.get(0));
        for (int i = 1; i < path.size(); i++) {
            if (current == null) {
                // Everything after a missing segment is missing too — except a virtual property,
                // which has an honest answer about absence. `!ids.empty` is the guard the
                // framework tells authors to write around a list bind, and an unselected optional
                // multi-select is never bound at all: answering null there made the guard true
                // and let it fail open on the one case it exists for.
                return i == path.size() - 1 ? absentProperty(path.get(i)) : null;
            }
            current = property(current, path.get(i));
        }
        return current;
    }

    /** What a virtual property answers about a value that is not there. */
    private static Object absentProperty(String name) {
        return switch (name) {
            case "empty" -> Boolean.TRUE;
            case "size", "length" -> 0;
            default -> null;
        };
    }

    private static Object property(Object target, String name) {
        Object virtual = virtualProperty(target, name);
        if (virtual != null) {
            return virtual;
        }
        if (target instanceof Map<?, ?> map) {
            return map.get(name);
        }
        return Optional.ofNullable(getterValue(target, name))
                .orElseGet(() -> fieldValue(target, name));
    }

    private static Object virtualProperty(Object target, String name) {
        switch (name) {
            case "size", "length" -> {
                if (target instanceof Collection<?> c) {
                    return c.size();
                }
                if (target instanceof Map<?, ?> m) {
                    return m.size();
                }
                if (target instanceof CharSequence s) {
                    return s.length();
                }
                if (target.getClass().isArray()) {
                    return java.lang.reflect.Array.getLength(target);
                }
            }
            case "empty" -> {
                if (target instanceof Collection<?> c) {
                    return c.isEmpty();
                }
                if (target instanceof Map<?, ?> m) {
                    return m.isEmpty();
                }
                if (target instanceof CharSequence s) {
                    return s.isEmpty();
                }
                if (target.getClass().isArray()) {
                    // `size` had this arm and `empty` did not, so the two disagreed about the
                    // same value and the shorter guard was the one that failed open.
                    return java.lang.reflect.Array.getLength(target) == 0;
                }
            }
            default -> {
                return null;
            }
        }
        return null;
    }

    private static Object getterValue(Object target, String name) {
        String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        // Record-style accessor (name), then JavaBean getX/isX.
        for (String accessor : new String[]{name, "get" + capitalized, "is" + capitalized}) {
            try {
                Method method = target.getClass().getMethod(accessor);
                if (method.getParameterCount() == 0 && method.canAccess(target)) {
                    return method.invoke(target);
                }
            } catch (ReflectiveOperationException ignored) {
                // try next accessor strategy
            }
        }
        return null;
    }

    private static Object fieldValue(Object target, String name) {
        try {
            Field field = target.getClass().getField(name);
            return field.get(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
