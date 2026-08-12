package io.tesseraql.camel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes one execution result into the exchange's context under its declared name.
 *
 * <p>A name is usually flat — a source publishes under its own name (docs/unified-sources.md
 * decision 10) — but a command step publishes under {@code steps.<id>}, the same address the
 * transactional processor uses, so a response reads a step's result the same way whether the
 * statement ran on the command's connection or through a service provider.
 */
public final class ContextResults {

    private ContextResults() {
    }

    /** Puts {@code result} at {@code key}, creating the intermediate map for a dotted name. */
    @SuppressWarnings("unchecked")
    public static void put(Map<String, Object> context, String key, Object result) {
        int dot = key.indexOf('.');
        if (dot < 0) {
            context.put(key, result);
            return;
        }
        String group = key.substring(0, dot);
        String name = key.substring(dot + 1);
        Object existing = context.get(group);
        Map<String, Object> nested = existing instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) existing)
                : new LinkedHashMap<>();
        nested.put(name, result);
        context.put(group, nested);
    }
}
