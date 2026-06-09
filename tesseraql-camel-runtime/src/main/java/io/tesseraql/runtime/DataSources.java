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
        String prefix = "tesseraql.datasources." + name + ".";
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("tesseraql-" + name);
        hikari.setJdbcUrl(config.requireString(prefix + "jdbcUrl"));
        config.getString(prefix + "username").ifPresent(hikari::setUsername);
        config.getString(prefix + "password").ifPresent(hikari::setPassword);
        config.getString(prefix + "maximumPoolSize")
                .map(Integer::parseInt)
                .ifPresent(hikari::setMaximumPoolSize);
        return new HikariDataSource(hikari);
    }
}
