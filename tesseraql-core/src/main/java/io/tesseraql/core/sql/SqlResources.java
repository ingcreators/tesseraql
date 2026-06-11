package io.tesseraql.core.sql;

import io.tesseraql.core.util.SqlScripts;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Renders 2-way SQL templates bundled as classpath resources (design ch. 8). Framework-internal
 * queries with dynamic composition (IN expansion, optional conditions) or dialect-sensitive
 * syntax live in {@code .sql} files - readable and runnable in plain SQL tools thanks to their
 * dummy values - instead of being assembled in Java. Parsed templates are cached per resource.
 */
public final class SqlResources {

    private static final ConcurrentMap<String, List<SqlNode>> CACHE = new ConcurrentHashMap<>();

    private SqlResources() {
    }

    /** Renders the resource (resolved against the anchor class) with the given parameters. */
    public static BoundSql render(Class<?> anchor, String resourcePath, Map<String, Object> params) {
        List<SqlNode> nodes = CACHE.computeIfAbsent(anchor.getName() + ":" + resourcePath,
                key -> Sql2WayParser.parse(SqlScripts.read(anchor, resourcePath)));
        return SqlRenderer.render(nodes, params);
    }

    /**
     * As {@link #render(Class, String, Map)}, preferring a vendor variant when one is bundled:
     * {@code <base>.<vendor>.sql} replaces {@code <base>.sql} (design ch. 42), the convention the
     * app-side dialect file resolution already uses.
     */
    public static BoundSql render(Class<?> anchor, String resourcePath, String vendor,
            Map<String, Object> params) {
        if (vendor != null && !vendor.isBlank() && resourcePath.endsWith(".sql")) {
            String variant = resourcePath.substring(0, resourcePath.length() - 4)
                    + "." + vendor + ".sql";
            if (anchor.getResource(variant) != null) {
                return render(anchor, variant, params);
            }
        }
        return render(anchor, resourcePath, params);
    }
}
