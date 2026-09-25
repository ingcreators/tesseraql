package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.yaml.config.AppConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * An overridden {@code main} (docs/capacity-defaults.md decision 11): {@code dev --embedded-db}
 * and the stack surface relocate the database, and the pool keeps what
 * {@code tesseraql.datasources.main} declares about its size. It used to be built from the
 * override's three fields alone, so the declared sizing was read by nothing.
 *
 * <p>Against a real database, because a HikariCP pool connects when it is built.
 */
@Testcontainers
class OverriddenMainIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void anOverriddenMainConnectsToTheOverrideAndKeepsItsDeclaredSizing() {
        AppConfig config = new AppConfig(Map.of("tesseraql", Map.of("datasources", Map.of(
                "main", Map.of(
                        // Where production points: the override replaces it.
                        "jdbcUrl", "jdbc:postgresql://nowhere.invalid:5432/app",
                        "maximumPoolSize", 4,
                        "minimumIdle", 1,
                        "connectionTimeoutMillis", 7000)))));

        Map<String, HikariDataSource> pools = DataSources.createAll(config, override());
        try {
            HikariDataSource main = pools.get("main");
            assertThat(main.getPoolName()).isEqualTo("tesseraql-main");
            assertThat(main.getJdbcUrl()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(main.getMaximumPoolSize()).isEqualTo(4);
            assertThat(main.getMinimumIdle()).isEqualTo(1);
            assertThat(main.getConnectionTimeout()).isEqualTo(7_000L);
        } finally {
            pools.values().forEach(HikariDataSource::close);
        }
    }

    /** With no {@code main} declared, the override alone makes it, at TesseraQL's defaults. */
    @Test
    void anUndeclaredMainUnderAnOverrideTakesTesseraqlsDefaults() {
        Map<String, HikariDataSource> pools = DataSources.createAll(new AppConfig(Map.of()),
                override());
        try {
            HikariDataSource main = pools.get("main");
            assertThat(main.getJdbcUrl()).isEqualTo(POSTGRES.getJdbcUrl());
            assertThat(main.getMaximumPoolSize()).isEqualTo(10);
            assertThat(main.getConnectionTimeout()).isEqualTo(30_000L);
        } finally {
            pools.values().forEach(HikariDataSource::close);
        }
    }

    private static DataSources.MainDatasourceOverride override() {
        return new DataSources.MainDatasourceOverride(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
