package io.tesseraql.runtime;

import com.zaxxer.hikari.HikariConfig;
import io.tesseraql.core.dialect.Dialect;
import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The duckdb datasource kind (docs/duckdb.md): an in-process analytics engine over CSV/Parquet
 * files, never a system of record. This class owns detection, the declared file scopes, and the
 * connection fence — every setting DuckDB must hold before app SQL runs on a pooled connection.
 *
 * <p>The fence, proven against DuckDB 1.3: external access is disabled at database creation with
 * the scope roots (only) carved out via {@code allowed_directories}; extension autoinstall and
 * autoload are off; and the first statement on every connection locks the configuration, so app
 * SQL can never widen any of it. {@code enable_external_access} cannot be re-enabled on a running
 * database even before the lock. Each {@code jdbc:duckdb:} connection is its own in-memory
 * database, so the fence properties apply per pooled connection and local tables are per-connection
 * scratch — exactly the nothing-durable, nothing-shared stance the design commits to.
 */
final class DuckDbDatasources {

    /** A declared file scope: a root directory and an optional per-tenant partition. */
    record FileScope(String root, boolean partitionByTenant) {
    }

    private DuckDbDatasources() {
    }

    /** Whether the named datasource resolves to the duckdb dialect (explicit or URL-inferred). */
    static boolean isDuckDb(AppConfig config, String name) {
        String prefix = "tesseraql.datasources." + name + ".";
        String dialect = config.getString(prefix + "dialect").orElse(null);
        if (dialect != null) {
            return "duckdb".equalsIgnoreCase(dialect);
        }
        return config.getString(prefix + "jdbcUrl")
                .flatMap(Dialect::fromJdbcUrl)
                .filter(d -> d == Dialect.DUCKDB)
                .isPresent();
    }

    /** The declared file scopes of a duckdb datasource, in declaration order (may be empty). */
    static Map<String, FileScope> fileScopes(AppConfig config, String name) {
        Map<String, FileScope> scopes = new LinkedHashMap<>();
        Object declared = config.navigate("tesseraql.datasources." + name + ".duckdb.fileScopes");
        if (declared instanceof Map<?, ?> map) {
            for (Object scopeName : map.keySet()) {
                String prefix = "tesseraql.datasources." + name + ".duckdb.fileScopes." + scopeName
                        + ".";
                String root = config.requireString(prefix + "root");
                String partitionBy = config.getString(prefix + "partitionBy").orElse(null);
                if (partitionBy != null && !"tenant".equals(partitionBy)) {
                    throw new IllegalStateException("tesseraql.datasources." + name
                            + ".duckdb.fileScopes." + scopeName + ".partitionBy must be 'tenant',"
                            + " not '" + partitionBy + "'");
                }
                scopes.put(String.valueOf(scopeName), new FileScope(root, partitionBy != null));
            }
        }
        return scopes;
    }

    /** Resolves a declared scope root against the app home; roots must stay traversal-free. */
    static Path resolveRoot(Path appHome, String root) {
        Path resolved = appHome.resolve(root).normalize().toAbsolutePath();
        if (root.contains("..") || resolved.toString().indexOf('\'') >= 0) {
            throw new IllegalStateException(
                    "A duckdb file-scope root must be a plain directory path without '..' or"
                            + " quotes: " + root);
        }
        return resolved;
    }

    /**
     * Applies the duckdb connection fence to a pool. Called for every {@code jdbc:duckdb:}
     * datasource; {@code main} is refused outright — framework bookkeeping lives on {@code main}
     * by design, and a duckdb datasource holds nothing durable.
     */
    static void configure(HikariConfig hikari, AppConfig config, String name, String prefix,
            Path appHome) {
        if ("main".equals(name)) {
            throw new IllegalStateException("tesseraql.datasources.main cannot be a duckdb"
                    + " datasource: the engine holds nothing durable and framework tables live on"
                    + " main (docs/duckdb.md)");
        }
        hikari.addDataSourceProperty("autoinstall_known_extensions", "false");
        hikari.addDataSourceProperty("autoload_known_extensions", "false");
        hikari.addDataSourceProperty("enable_external_access", "false");
        List<String> roots = fileScopes(config, name).values().stream()
                .map(scope -> resolveRoot(appHome == null ? Path.of(".") : appHome, scope.root())
                        + "/")
                .distinct()
                .toList();
        if (!roots.isEmpty()) {
            hikari.addDataSourceProperty("allowed_directories",
                    "[" + String.join(",", roots.stream().map(r -> "'" + r + "'").toList()) + "]");
        }
        config.getString(prefix + "duckdb.memoryLimit")
                .ifPresent(limit -> hikari.addDataSourceProperty("memory_limit", limit));
        config.getString(prefix + "duckdb.threads")
                .ifPresent(threads -> hikari.addDataSourceProperty("threads", threads));
        // Each connection is its own in-memory database; keep the default pool small so a
        // deployment cannot silently multiply engine memory by Hikari's default of ten.
        if (config.getString(prefix + "maximumPoolSize").isEmpty()) {
            hikari.setMaximumPoolSize(4);
        }
        // The first statement on every pooled connection freezes the configuration; nothing app
        // SQL runs afterwards can change a setting on this database instance.
        hikari.setConnectionInitSql("SET GLOBAL lock_configuration=true");
    }
}
