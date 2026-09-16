package io.tesseraql.core.expr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Variable scope for evaluating 2-way SQL directive expressions (design ch. 8.1).
 *
 * <p>The root scope is a name to value map (the SQL parameters). Dotted paths such as
 * {@code principal.claim.tenant_id} are resolved segment by segment: a {@link Map} answers
 * its key, a record its own accessors ({@code principal.claim}, {@code tenant.id}), any other
 * value its JavaBean {@code getX}/{@code isX} getter or a public instance field. A small set
 * of virtual properties ({@code size}, {@code length}, {@code empty}) is supported on
 * collections, maps, arrays, and strings, and on a map a present key of that name wins over
 * the virtual answer — an input or a column may be called {@code size}
 * (docs/audit-low-leads.md G12).
 *
 * <p>That is the whole reach of a path (docs/audit-low-leads.md G15). Nothing else is
 * invoked: not a method by its bare name on a non-record ({@code s.strip}), not a method
 * every object has ({@code toString}, {@code hashCode}, {@code getClass} and the {@code Class}
 * behind it), not a static one — each of those answers {@code null} like any other absent
 * property. The grammar has no call syntax, so no expression can pass an argument.
 */
public final class EvaluationContext {

    /** The zero-argument methods every object has, which a path never reads. */
    private static final Set<String> OBJECT_METHODS = Set.of("getClass", "hashCode",
            "toString", "notify", "notifyAll", "wait");

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
        if (target instanceof Map<?, ?> map) {
            // A present key is the author's own value; the virtual answer is for the map that
            // has no key by that name. The other order made an input named size unreachable
            // from every nested scope, and read the count of bound inputs in its place.
            if (map.containsKey(name)) {
                return map.get(name);
            }
            Object virtual = virtualProperty(target, name);
            return virtual != null ? virtual : map.get(name);
        }
        Object virtual = virtualProperty(target, name);
        if (virtual != null) {
            return virtual;
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
        Class<?> type = target.getClass();
        String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        // A record's own accessor by bare name — a component's, or one the record declares
        // itself, which is how principal.claim.<name> resolves — then JavaBean getX/isX on
        // anything. The bare name used to be tried on every class, which invoked String.strip,
        // Object.toString and, through getClass, every getter of Class; a bare name matching a
        // public static method escaped as an uncaught IllegalArgumentException.
        List<String> accessors = type.isRecord()
                ? List.of(name, "get" + capitalized, "is" + capitalized)
                : List.of("get" + capitalized, "is" + capitalized);
        for (String accessor : accessors) {
            try {
                Method method = type.getMethod(accessor);
                boolean bare = accessor.equals(name);
                if (method.getParameterCount() != 0
                        || Modifier.isStatic(method.getModifiers())
                        || OBJECT_METHODS.contains(method.getName())
                        || (bare && method.getDeclaringClass() != type)
                        || !method.canAccess(target)) {
                    continue;
                }
                return method.invoke(target);
            } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                // try next accessor strategy
            }
        }
        return null;
    }

    private static Object fieldValue(Object target, String name) {
        try {
            Field field = target.getClass().getField(name);
            return Modifier.isStatic(field.getModifiers()) ? null : field.get(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
