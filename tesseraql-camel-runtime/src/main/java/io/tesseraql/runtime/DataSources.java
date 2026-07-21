package io.tesseraql.runtime;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.yaml.config.AppConfig;

/**
 * Builds JDBC connection pools from TesseraQL configuration (design ch. 5.2 {@code datasources}).
 */
public final class DataSources {

    private DataSources() {
    }

    /**
     * An out-of-config connection for the {@code main} pool. The {@code serve --embedded-db} path
     * starts an embedded PostgreSQL and supplies its coordinates here, so the runtime points
     * {@code main} at the embedded instance instead of {@code tesseraql.datasources.main.jdbcUrl}
     * (and the app's config need not declare {@code main} at all).
     */
    public record MainDatasourceOverride(String jdbcUrl, String username, String password) {
    }

    /** Creates a HikariCP pool for the datasource named {@code name} under {@code tesseraql.datasources}. */
    public static HikariDataSource create(AppConfig config, String name) {
        return create(config, name, (java.nio.file.Path) null);
    }

    /**
     * Like {@link #create(AppConfig, String)}, with the app home a duckdb datasource resolves its
     * declared file-scope roots against (docs/duckdb.md).
     */
    public static HikariDataSource create(AppConfig config, String name,
            java.nio.file.Path appHome) {
        String prefix = "tesseraql.datasources." + name + ".";
        HikariConfig hikari = base(config, "tesseraql-" + name, prefix);
        if (!DuckDbDatasources.isDuckDb(config, name)) {
            return new HikariDataSource(hikari);
        }
        DuckDbDatasources.configure(hikari, config, name, prefix, appHome);
        try {
            return new HikariDataSource(hikari);
        } catch (RuntimeException failure) {
            // TQL-APP-4204: the duckdb engine refused its init sequence — most commonly a
            // declared extension missing from the offline cache. Name the fix.
            throw new io.tesseraql.core.error.TqlException(
                    new io.tesseraql.core.error.TqlErrorCode(
                            io.tesseraql.core.error.TqlDomain.APP, 4204),
                    "duckdb datasource '" + name + "' failed to initialize: "
                            + rootMessage(failure) + ". If a declared extension is missing from"
                            + " the local cache, provision it offline-first with"
                            + " 'tesseraql duckdb install-extensions --app <dir>'"
                            + " (docs/duckdb.md)",
                    failure);
        }
    }

    /** The innermost cause message — the engine's own words, not Hikari's wrapper. */
    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage()).replace('\n', ' ');
    }

    /** Creates the {@code main} HikariCP pool from an explicit override rather than config. */
    public static HikariDataSource create(MainDatasourceOverride override) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("tesseraql-main");
        hikari.setJdbcUrl(override.jdbcUrl());
        if (override.username() != null) {
            hikari.setUsername(override.username());
        }
        if (override.password() != null) {
            hikari.setPassword(override.password());
        }
        return new HikariDataSource(hikari);
    }

    /**
     * Creates one pool per datasource declared under {@code tesseraql.datasources} ({@code main}
     * is required), keyed by name in declaration order.
     */
    public static java.util.LinkedHashMap<String, HikariDataSource> createAll(AppConfig config) {
        return createAll(config, null);
    }

    /**
     * Like {@link #createAll(AppConfig)}, but when {@code override} is non-null the {@code main}
     * pool is built from it instead of config — {@code main} may then be absent from config.
     */
    public static java.util.LinkedHashMap<String, HikariDataSource> createAll(AppConfig config,
            MainDatasourceOverride override) {
        return createAll(config, override, null);
    }

    /**
     * Like {@link #createAll(AppConfig, MainDatasourceOverride)}, with the app home a duckdb
     * datasource resolves its declared file-scope roots against (docs/duckdb.md).
     */
    public static java.util.LinkedHashMap<String, HikariDataSource> createAll(AppConfig config,
            MainDatasourceOverride override, java.nio.file.Path appHome) {
        java.util.LinkedHashMap<String, HikariDataSource> pools = new java.util.LinkedHashMap<>();
        Object declared = config.navigate("tesseraql.datasources");
        if (declared instanceof java.util.Map<?, ?> map) {
            for (Object name : map.keySet()) {
                String poolName = String.valueOf(name);
                pools.put(poolName, override != null && "main".equals(poolName)
                        ? create(override)
                        : create(config, poolName, appHome));
            }
        }
        if (!pools.containsKey("main")) {
            if (override != null) {
                pools.put("main", create(override));
            } else {
                pools.values().forEach(HikariDataSource::close);
                // Reuse the single-pool path for its clear missing-key error.
                pools.put("main", create(config, "main"));
            }
        }
        return pools;
    }

    /** Creates a HikariCP pool from the {@code jdbcUrl}/{@code username}/{@code password} keys at {@code prefix}. */
    public static HikariDataSource create(AppConfig config, String poolName, String prefix) {
        return new HikariDataSource(base(config, poolName, prefix));
    }

    /** The shared Hikari knob mapping every pool builds from. */
    private static HikariConfig base(AppConfig config, String poolName, String prefix) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName(poolName);
        hikari.setJdbcUrl(config.requireString(prefix + "jdbcUrl"));
        config.getString(prefix + "username").ifPresent(hikari::setUsername);
        config.getString(prefix + "password").ifPresent(hikari::setPassword);
        config.getString(prefix + "maximumPoolSize")
                .map(Integer::parseInt)
                .ifPresent(hikari::setMaximumPoolSize);
        // How long a borrower waits for a connection before failing (ms). Also bounds the
        // readiness probe's detection latency when the database is down (roadmap Phase 45).
        config.getString(prefix + "connectionTimeoutMillis")
                .map(Long::parseLong)
                .ifPresent(hikari::setConnectionTimeout);
        // The remaining pool tuning knobs (roadmap Phase 45): every production-relevant
        // Hikari setting is reachable from config instead of being locked to a default.
        config.getString(prefix + "minimumIdle")
                .map(Integer::parseInt)
                .ifPresent(hikari::setMinimumIdle);
        config.getString(prefix + "idleTimeoutMillis")
                .map(Long::parseLong)
                .ifPresent(hikari::setIdleTimeout);
        config.getString(prefix + "maxLifetimeMillis")
                .map(Long::parseLong)
                .ifPresent(hikari::setMaxLifetime);
        config.getString(prefix + "keepaliveTimeMillis")
                .map(Long::parseLong)
                .ifPresent(hikari::setKeepaliveTime);
        config.getString(prefix + "leakDetectionThresholdMillis")
                .map(Long::parseLong)
                .ifPresent(hikari::setLeakDetectionThreshold);
        return hikari;
    }
}
