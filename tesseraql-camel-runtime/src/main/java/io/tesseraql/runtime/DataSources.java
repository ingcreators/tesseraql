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
        return create(config, "tesseraql-" + name, "tesseraql.datasources." + name + ".");
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
        java.util.LinkedHashMap<String, HikariDataSource> pools = new java.util.LinkedHashMap<>();
        Object declared = config.navigate("tesseraql.datasources");
        if (declared instanceof java.util.Map<?, ?> map) {
            for (Object name : map.keySet()) {
                String poolName = String.valueOf(name);
                pools.put(poolName, override != null && "main".equals(poolName)
                        ? create(override)
                        : create(config, poolName));
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
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName(poolName);
        hikari.setJdbcUrl(config.requireString(prefix + "jdbcUrl"));
        config.getString(prefix + "username").ifPresent(hikari::setUsername);
        config.getString(prefix + "password").ifPresent(hikari::setPassword);
        config.getString(prefix + "maximumPoolSize")
                .map(Integer::parseInt)
                .ifPresent(hikari::setMaximumPoolSize);
        return new HikariDataSource(hikari);
    }
}
