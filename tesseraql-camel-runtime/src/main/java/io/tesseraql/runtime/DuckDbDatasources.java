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
    record Lake(String catalog, String schema, String data, String alias, boolean readWrite,
            Remote remote) {

        /** Whether the Parquet data lives on object storage rather than a local directory. */
        boolean isRemote() {
            return remote != null;
        }
    }

    /**
     * Object-storage coordinates for a remote lake (docs/duckdb.md "Remote data paths"):
     * S3 or any S3-compatible store; {@code instanceChain} selects the AWS credential chain
     * instead of static keys.
     */
    record Remote(String region, String endpoint, boolean pathStyle, boolean useSsl,
            String keyId, String secret, boolean instanceChain) {
    }

    /** A declared ad-hoc read prefix (docs/duckdb.md): {@code ${remote.<name>}} resolves under it. */
    record RemoteRead(String name, String url, Remote coordinates) {
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
        Remote remote = null;
        if (data.startsWith("s3://")) {
            if (!data.endsWith("/")) {
                throw new IllegalStateException(prefix + "data must be an s3:// PREFIX ending"
                        + " in '/' (the scoped secret covers exactly this prefix)");
            }
            if (!extensions.contains("httpfs")) {
                throw new IllegalStateException("tesseraql.datasources." + name
                        + ".duckdb.lake on object storage needs httpfs in extensions:, so the"
                        + " offline cache provisioning covers it (docs/duckdb.md)");
            }
            remote = remoteCoordinates(config, prefix);
        } else if (data.contains("://")) {
            throw new IllegalStateException(prefix + "data must be a local directory or an"
                    + " s3:// prefix (S3-compatible stores use s3:// plus endpoint:)");
        }
        return new Lake(catalog, schema, data, alias, "readwrite".equals(mode), remote);
    }

    /** A string value of an attach-entry key, with config placeholders resolved. */
    private static String entryValue(AppConfig config, Map<?, ?> entry, String key) {
        Object value = entry.get(key);
        return value == null ? null : config.resolve(String.valueOf(value));
    }

    /** Parses the shared object-storage coordinate + credential block at {@code prefix}. */
    private static Remote remoteCoordinates(AppConfig config, String prefix) {
        Object credentials = config.navigate(prefix.substring(0, prefix.length() - 1)
                + ".credentials");
        String keyId = null;
        String secretKey = null;
        boolean instanceChain = false;
        if (credentials instanceof Map) {
            keyId = config.requireString(prefix + "credentials.keyId");
            secretKey = config.requireString(prefix + "credentials.secret");
        } else if ("instance".equals(config.getString(prefix + "credentials").orElse(null))) {
            instanceChain = true;
        } else {
            throw new IllegalStateException(prefix + "credentials must be a"
                    + " {keyId, secret} mapping of secret references, or the string"
                    + " 'instance' for the AWS credential chain");
        }
        return new Remote(
                config.getString(prefix + "region").orElse(null),
                config.getString(prefix + "endpoint").orElse(null),
                "path".equals(config.getString(prefix + "urlStyle").orElse("vhost")),
                config.getString(prefix + "useSsl").map(Boolean::parseBoolean).orElse(true),
                keyId, secretKey, instanceChain);
    }

    /** The declared ad-hoc read prefixes of a duckdb datasource (docs/duckdb.md), maybe empty. */
    static List<RemoteRead> remoteReads(AppConfig config, String name) {
        List<RemoteRead> reads = new ArrayList<>();
        Object declared = config.navigate("tesseraql.datasources." + name + ".duckdb.remotes");
        if (!(declared instanceof Map<?, ?> map)) {
            return reads;
        }
        for (Object remoteName : map.keySet()) {
            String prefix = "tesseraql.datasources." + name + ".duckdb.remotes." + remoteName
                    + ".";
            String url = config.requireString(prefix + "url");
            if (!url.startsWith("s3://") || !url.endsWith("/")) {
                throw new IllegalStateException(prefix + "url must be an s3:// prefix ending"
                        + " in '/' (the scoped secret covers exactly this prefix)");
            }
            if (!String.valueOf(remoteName).matches("[A-Za-z0-9_-]+")) {
                throw new IllegalStateException(prefix + " name must be a plain identifier");
            }
            reads.add(new RemoteRead(String.valueOf(remoteName), url,
                    remoteCoordinates(config, prefix)));
        }
        return reads;
    }

    /** Whether the datasource runs the remote tier (a remote lake or declared remotes). */
    static boolean isRemoteTier(AppConfig config, String name) {
        Lake lake = lake(config, name);
        return (lake != null && lake.isRemote()) || !remoteReads(config, name).isEmpty();
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
        List<RemoteRead> remoteReads = remoteReads(config, name);
        boolean remoteTier = (lake != null && lake.isRemote()) || !remoteReads.isEmpty();
        if (remoteTier && !fileScopes(config, name).isEmpty()) {
            throw new IllegalStateException("tesseraql.datasources." + name + " declares a"
                    + " remote tier (remote lake or remotes:) and fileScopes: - a remote-tier"
                    + " datasource has no governed local-file surface; compose across two duckdb"
                    + " datasources instead (docs/duckdb.md)");
        }
        if (remoteTier && !extensions(config, name).contains("httpfs")) {
            throw new IllegalStateException("tesseraql.datasources." + name
                    + " runs the remote tier and needs httpfs in extensions:, so the offline"
                    + " cache provisioning covers it (docs/duckdb.md)");
        }
        if (lake != null && !lake.isRemote()) {
            // The data directory must exist before the first connection attaches; creating it
            // here keeps a fresh checkout bootable (the metadata catalog needs no such help —
            // DuckLake creates its schema on the catalog datasource itself).
            try {
                java.nio.file.Files.createDirectories(resolveRoot(appHome, lake.data()));
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(
                        "Could not create the lake data directory " + lake.data(), failure);
            }
        }
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
        hikari.setConnectionInitSql(initSql(config, name, extensions, attaches, lake,
                remoteReads, loadsAtInit, override, appHome));
    }

    /**
     * The per-connection init sequence: LOAD declared extensions from the local cache, establish
     * declared attaches with credentials injected from the target datasource's declaration, then
     * drop external access (when it was needed for the loads) and lock the configuration — the
     * last statements every pooled connection runs before any app SQL.
     */
    private static String initSql(AppConfig config, String name, List<String> extensions,
            List<Attach> attaches, Lake lake, List<RemoteRead> remoteReads, boolean loadsAtInit,
            DataSources.MainDatasourceOverride override, Path appHome) {
        List<String> statements = new ArrayList<>();
        for (String extension : extensions) {
            statements.add("LOAD " + extension);
        }
        for (RemoteRead read : remoteReads) {
            statements.add(remoteSecret("tql_remote_" + read.name(), read.url(),
                    read.coordinates()));
        }
        for (Attach attach : attaches) {
            statements.add("ATTACH '" + conninfo(config, attach.datasource(), override)
                    .replace("'", "''")
                    + "' AS " + attach.alias() + " (TYPE postgres"
                    + (attach.readWrite() ? "" : ", READ_ONLY") + ")");
        }
        if (lake != null) {
            if (lake.isRemote()) {
                statements.add(lakeSecret(lake));
            }
            statements.add("ATTACH 'ducklake:postgres:"
                    + conninfo(config, lake.catalog(), override).replace("'", "''")
                    + "' AS " + lake.alias()
                    + " (DATA_PATH '" + (lake.isRemote()
                            ? lake.data()
                            : resolveRoot(appHome, lake.data()) + "/")
                    + "', METADATA_SCHEMA '" + lake.schema() + "'"
                    + (lake.readWrite() ? "" : ", READ_ONLY") + ")");
        }
        if (loadsAtInit && (lake == null || !lake.isRemote()) && remoteReads.isEmpty()) {
            // The remote tier keeps external access on (httpfs needs it); its engine-hard
            // controls are the lock and the prefix-scoped secret, and the statement rules
            // move to lint (docs/duckdb.md "Remote data paths", decision point 13).
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

    /**
     * The prefix-scoped engine secret for a remote lake: credentials answer only for the
     * declared {@code data:} prefix, and SQL never carries them — this statement runs at
     * connection setup, before the configuration locks.
     */
    private static String lakeSecret(Lake lake) {
        return remoteSecret("tql_lake", lake.data(), lake.remote());
    }

    /** A prefix-scoped engine secret: the credentials answer only for {@code scope}. */
    private static String remoteSecret(String secretName, String scope, Remote remote) {
        StringBuilder secret = new StringBuilder(
                "CREATE SECRET " + secretName + " (TYPE s3");
        if (remote.instanceChain()) {
            secret.append(", PROVIDER credential_chain");
        } else {
            secret.append(", KEY_ID '").append(remote.keyId().replace("'", "''")).append('\'');
            secret.append(", SECRET '").append(remote.secret().replace("'", "''")).append('\'');
        }
        if (remote.region() != null) {
            secret.append(", REGION '").append(remote.region().replace("'", "''")).append('\'');
        }
        if (remote.endpoint() != null) {
            secret.append(", ENDPOINT '").append(remote.endpoint().replace("'", "''"))
                    .append('\'');
        }
        if (remote.pathStyle()) {
            secret.append(", URL_STYLE 'path'");
        }
        if (!remote.useSsl()) {
            secret.append(", USE_SSL false");
        }
        secret.append(", SCOPE '").append(scope.replace("'", "''")).append("')");
        return secret.toString();
    }

    /** A single-quoted libpq conninfo value ({@code \} and {@code '} backslash-escaped). */
    private static String quote(String value) {
        String escaped = String.valueOf(value).replace("\\", "\\\\").replace("'", "\\'");
        return "'" + escaped + "'";
    }
}
