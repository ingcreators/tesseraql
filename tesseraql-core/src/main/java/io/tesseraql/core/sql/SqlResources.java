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
}
