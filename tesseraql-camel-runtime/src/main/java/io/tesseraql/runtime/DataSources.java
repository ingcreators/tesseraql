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

    /** Creates a HikariCP pool for the datasource named {@code name} under {@code tesseraql.datasources}. */
    public static HikariDataSource create(AppConfig config, String name) {
        return create(config, "tesseraql-" + name, "tesseraql.datasources." + name + ".");
    }

    /**
     * Creates one pool per datasource declared under {@code tesseraql.datasources} ({@code main}
     * is required), keyed by name in declaration order.
     */
    public static java.util.LinkedHashMap<String, HikariDataSource> createAll(AppConfig config) {
        java.util.LinkedHashMap<String, HikariDataSource> pools = new java.util.LinkedHashMap<>();
        Object declared = config.navigate("tesseraql.datasources");
        if (declared instanceof java.util.Map<?, ?> map) {
            for (Object name : map.keySet()) {
                pools.put(String.valueOf(name), create(config, String.valueOf(name)));
            }
        }
        if (!pools.containsKey("main")) {
            pools.values().forEach(HikariDataSource::close);
            // Reuse the single-pool path for its clear missing-key error.
            pools.put("main", create(config, "main"));
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
