package io.tesseraql.runtime;

import com.zaxxer.hikari.HikariConfig;
import io.tesseraql.core.dialect.Dialect;
import io.tesseraql.yaml.config.AppConfig;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The duckdb datasource kind (docs/duckdb.md): an in-process analytics engine over CSV/Parquet
 * files, never a system of record. This class owns detection, the declared file scopes, declared
 * engine extensions, framework-managed attaches, and the connection fence — every setting DuckDB
 * must hold before app SQL runs on a pooled connection.
 *
 * <p>The fence, proven against DuckDB 1.3: extension autoinstall and autoload are off and the
 * extension directory is a local pre-provisioned cache (the runtime never downloads); declared
 * extensions are {@code LOAD}ed and declared attaches established at connection setup; then
 * external access is disabled — queries through an established attach keep working, while new
 * {@code ATTACH}, further {@code LOAD}/{@code INSTALL}, and out-of-root file access are refused —
 * with the scope roots (only) carved out via {@code allowed_directories}; and the configuration is
 * locked, so app SQL can never widen any of it. {@code enable_external_access} cannot be
 * re-enabled on a running database even before the lock. Each {@code jdbc:duckdb:} connection is
 * its own in-memory database, so the sequence applies per pooled connection and local tables are
 * per-connection scratch — exactly the nothing-durable, nothing-shared stance the design commits
 * to.
 */
final class DuckDbDatasources {

    /** A declared file scope: a root directory and an optional per-tenant partition. */
    record FileScope(String root, boolean partitionByTenant) {
    }

    /** A declared attach: a named server datasource surfaced under an alias, read-only default. */
    record Attach(String datasource, String alias, boolean readWrite) {
    }

    /** A declared DuckLake attach (docs/duckdb.md "Lake tables"): catalog, schema, data, alias. */
    record Lake(String catalog, String schema, String data, String alias, boolean readWrite) {
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

    /** The declared engine extensions of a duckdb datasource (validated names, may be empty). */
    static List<String> extensions(AppConfig config, String name) {
        List<String> extensions = new ArrayList<>();
        Object declared = config.navigate("tesseraql.datasources." + name + ".duckdb.extensions");
        if (declared instanceof List<?> list) {
            for (Object entry : list) {
                String extension = String.valueOf(entry);
                if (!extension.matches("[a-z0-9_]+")) {
                    throw new IllegalStateException("tesseraql.datasources." + name
                            + ".duckdb.extensions entry '" + extension
                            + "' is not a plain extension name");
                }
                extensions.add(extension);
            }
        }
        return extensions;
    }

    /** The declared attaches of a duckdb datasource (validated shape, may be empty). */
    static List<Attach> attaches(AppConfig config, String name) {
        List<Attach> attaches = new ArrayList<>();
        Object declared = config.navigate("tesseraql.datasources." + name + ".duckdb.attach");
        if (!(declared instanceof List<?> list)) {
            return attaches;
        }
        for (int i = 0; i < list.size(); i++) {
            String prefix = "tesseraql.datasources." + name + ".duckdb.attach[" + i + "] ";
            if (!(list.get(i) instanceof Map<?, ?> entry)) {
                throw new IllegalStateException(prefix + "must be a mapping with datasource:");
            }
            String target = entryValue(config, entry, "datasource");
            if (target == null || target.isBlank()) {
                throw new IllegalStateException(prefix + "declares no datasource:");
            }
            String alias = entryValue(config, entry, "as");
            alias = alias == null ? target : alias;
            String mode = entryValue(config, entry, "mode");
            mode = mode == null ? "readonly" : mode;
            if (!"readonly".equals(mode) && !"readwrite".equals(mode)) {
                throw new IllegalStateException(prefix + "mode must be readonly or readwrite,"
                        + " not '" + mode + "'");
            }
            if (!alias.matches("[A-Za-z_][A-Za-z0-9_]*") || "main".equals(alias)) {
                throw new IllegalStateException(prefix + "as must be a plain identifier other"
                        + " than 'main' (DuckDB's own default schema is named main); attaching"
                        + " the main datasource requires an explicit as:");
            }
            if (isDuckDb(config, target)) {
                throw new IllegalStateException(prefix + "datasource '" + target
                        + "' is a duckdb datasource; attach targets are server datasources");
            }
            attaches.add(new Attach(target, alias, "readwrite".equals(mode)));
        }
        return attaches;
    }

    /** The declared lake block of a duckdb datasource, or {@code null} (validated shape). */
    static Lake lake(AppConfig config, String name) {
        String prefix = "tesseraql.datasources." + name + ".duckdb.lake.";
        if (!(config.navigate("tesseraql.datasources." + name + ".duckdb.lake") instanceof Map)) {
            return null;
        }
        String catalog = config.getString(prefix + "catalog").orElse("main");
        String schema = config.getString(prefix + "schema").orElse("ducklake");
        String data = config.requireString(prefix + "data");
        String alias = config.getString(prefix + "as").orElse("lake");
        String mode = config.getString(prefix + "mode").orElse("readonly");
        if (!"readonly".equals(mode) && !"readwrite".equals(mode)) {
            throw new IllegalStateException(prefix + "mode must be readonly or readwrite, not '"
                    + mode + "'");
        }
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalStateException(prefix + "schema must be a plain identifier");
        }
        if (!alias.matches("[A-Za-z_][A-Za-z0-9_]*") || "main".equals(alias)) {
            throw new IllegalStateException(prefix + "as must be a plain identifier other than"
                    + " 'main' (DuckDB's own default schema is named main)");
        }
        if (data.contains("..") || data.indexOf('\'') >= 0) {
            throw new IllegalStateException(prefix + "data must be a plain directory path"
                    + " without '..' or quotes");
        }
        if (isDuckDb(config, catalog)) {
            throw new IllegalStateException(prefix + "catalog '" + catalog
                    + "' must be a PostgreSQL datasource holding the lake metadata");
        }
        List<String> extensions = extensions(config, name);
        if (!extensions.contains("ducklake") || !extensions.contains("postgres")) {
            throw new IllegalStateException("tesseraql.datasources." + name
                    + ".duckdb.lake needs extensions: [ducklake, postgres] declared, so the"
                    + " offline cache provisioning covers them (docs/duckdb.md)");
        }
        return new Lake(catalog, schema, data, alias, "readwrite".equals(mode));
    }

    /** A string value of an attach-entry key, with config placeholders resolved. */
    private static String entryValue(AppConfig config, Map<?, ?> entry, String key) {
        Object value = entry.get(key);
        return value == null ? null : config.resolve(String.valueOf(value));
    }

    /** The pre-provisioned extension cache directory (docs/duckdb.md, offline-first). */
    static Path extensionDirectory(AppConfig config, Path appHome) {
        Path base = appHome == null ? Path.of(".") : appHome;
        return config.getString("tesseraql.duckdb.extensionDirectory")
                .map(base::resolve)
                .orElseGet(() -> base.resolve("work/duckdb-extensions"))
                .normalize()
                .toAbsolutePath();
    }

    /** The dataset spool directory — the one bridge directory the fence admits beyond scopes. */
    static Path spoolDirectory(AppConfig config, Path appHome) {
        Path base = appHome == null ? Path.of(".") : appHome;
        return config.getString("tesseraql.duckdb.spoolDirectory")
                .map(base::resolve)
                .orElseGet(() -> base.resolve("work/duckdb-spool"))
                .normalize()
                .toAbsolutePath();
    }

    /** Resolves a declared scope root against the app home; roots must stay traversal-free. */
    static Path resolveRoot(Path appHome, String root) {
        Path resolved = (appHome == null ? Path.of(".") : appHome).resolve(root).normalize()
                .toAbsolutePath();
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
            Path appHome, DataSources.MainDatasourceOverride override) {
        if ("main".equals(name)) {
            throw new IllegalStateException("tesseraql.datasources.main cannot be a duckdb"
                    + " datasource: the engine holds nothing durable and framework tables live on"
                    + " main (docs/duckdb.md)");
        }
        List<String> extensions = extensions(config, name);
        List<Attach> attaches = attaches(config, name);
        boolean loadsAtInit = !extensions.isEmpty() || !attaches.isEmpty();
        hikari.addDataSourceProperty("autoinstall_known_extensions", "false");
        hikari.addDataSourceProperty("autoload_known_extensions", "false");
        if (loadsAtInit) {
            // LOAD and ATTACH need external access during connection setup; the init statements
            // below drop it (irreversibly for the database's lifetime) once they are done.
            hikari.addDataSourceProperty("extension_directory",
                    extensionDirectory(config, appHome).toString());
        } else {
            hikari.addDataSourceProperty("enable_external_access", "false");
        }
        Lake lake = lake(config, name);
        // The engine may read the declared scope roots, the dataset spool, and — when a lake is
        // declared — its Parquet data directory. Nothing else.
        List<String> roots = Stream.concat(Stream.concat(
                fileScopes(config, name).values().stream()
                        .map(scope -> resolveRoot(appHome, scope.root()) + "/"),
                Stream.of(spoolDirectory(config, appHome) + "/")),
                lake == null
                        ? Stream.empty()
                        : Stream.of(resolveRoot(appHome, lake.data()) + "/"))
                .distinct()
                .toList();
        hikari.addDataSourceProperty("allowed_directories",
                "[" + String.join(",", roots.stream().map(r -> "'" + r + "'").toList()) + "]");
        config.getString(prefix + "duckdb.memoryLimit")
                .ifPresent(limit -> hikari.addDataSourceProperty("memory_limit", limit));
        config.getString(prefix + "duckdb.threads")
                .ifPresent(threads -> hikari.addDataSourceProperty("threads", threads));
        // Each connection is its own in-memory database; keep the default pool small so a
        // deployment cannot silently multiply engine memory by Hikari's default of ten.
        if (config.getString(prefix + "maximumPoolSize").isEmpty()) {
            hikari.setMaximumPoolSize(4);
        }
        hikari.setConnectionInitSql(
                initSql(config, name, extensions, attaches, lake, loadsAtInit, override, appHome));
    }

    /**
     * The per-connection init sequence: LOAD declared extensions from the local cache, establish
     * declared attaches with credentials injected from the target datasource's declaration, then
     * drop external access (when it was needed for the loads) and lock the configuration — the
     * last statements every pooled connection runs before any app SQL.
     */
    private static String initSql(AppConfig config, String name, List<String> extensions,
            List<Attach> attaches, Lake lake, boolean loadsAtInit,
            DataSources.MainDatasourceOverride override, Path appHome) {
        List<String> statements = new ArrayList<>();
        for (String extension : extensions) {
            statements.add("LOAD " + extension);
        }
        for (Attach attach : attaches) {
            statements.add("ATTACH '" + conninfo(config, attach.datasource(), override)
                    .replace("'", "''")
                    + "' AS " + attach.alias() + " (TYPE postgres"
                    + (attach.readWrite() ? "" : ", READ_ONLY") + ")");
        }
        if (lake != null) {
            statements.add("ATTACH 'ducklake:postgres:"
                    + conninfo(config, lake.catalog(), override).replace("'", "''")
                    + "' AS " + lake.alias()
                    + " (DATA_PATH '" + resolveRoot(appHome, lake.data()) + "/'"
                    + ", METADATA_SCHEMA '" + lake.schema() + "'"
                    + (lake.readWrite() ? "" : ", READ_ONLY") + ")");
        }
        if (loadsAtInit) {
            statements.add("SET enable_external_access=false");
        }
        statements.add("SET GLOBAL lock_configuration=true");
        return String.join("; ", statements);
    }

    /**
     * The libpq conninfo for an attach target, derived from the declared datasource — SQL authors
     * never see credentials, and only PostgreSQL-dialect targets are attachable (postgres_scanner
     * is the one bridge the design ships).
     */
    static String conninfo(AppConfig config, String target,
            DataSources.MainDatasourceOverride override) {
        // `serve --embedded-db` replaces main's coordinates outside config; an attach on main
        // must follow the EFFECTIVE pool, not the declared one.
        if ("main".equals(target) && override != null) {
            return conninfoOf(override.jdbcUrl(), override.username(), override.password());
        }
        String prefix = "tesseraql.datasources." + target + ".";
        String jdbcUrl = config.requireString(prefix + "jdbcUrl");
        if (Dialect.fromJdbcUrl(jdbcUrl).filter(d -> d == Dialect.POSTGRES).isEmpty()) {
            throw new IllegalStateException("duckdb attach target '" + target
                    + "' must be a PostgreSQL datasource; its jdbcUrl is " + jdbcUrl);
        }
        return conninfoOf(jdbcUrl, config.getString(prefix + "username").orElse(null),
                config.getString(prefix + "password").orElse(null));
    }

    /** A libpq conninfo from explicit PostgreSQL coordinates. */
    private static String conninfoOf(String jdbcUrl, String username, String password) {
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        StringBuilder conninfo = new StringBuilder();
        conninfo.append("host=").append(quote(uri.getHost()));
        conninfo.append(" port=").append(uri.getPort() > 0 ? uri.getPort() : 5432);
        conninfo.append(" dbname=").append(quote(database));
        if (username != null) {
            conninfo.append(" user=").append(quote(username));
        }
        if (password != null) {
            conninfo.append(" password=").append(quote(password));
        }
        return conninfo.toString();
    }

    /** A single-quoted libpq conninfo value ({@code \} and {@code '} backslash-escaped). */
    private static String quote(String value) {
        String escaped = String.valueOf(value).replace("\\", "\\\\").replace("'", "\\'");
        return "'" + escaped + "'";
    }
}
