package io.tesseraql.scim;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class JdbcScimResourceMappingTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static HikariDataSource dataSource;
    static JdbcScimResourceMapping mapping;

    @BeforeAll
    static void start() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        dataSource = new HikariDataSource(config);
        mapping = new JdbcScimResourceMapping(dataSource);
        mapping.ensureSchema();
    }

    @AfterAll
    static void stop() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void persistsUpdatesAndRemovesMappings() {
        assertThat(mapping.remoteId("local-1")).isEmpty();

        mapping.put("local-1", "remote-1");
        assertThat(mapping.remoteId("local-1")).contains("remote-1");

        // Re-putting the same local id updates the remote id (idempotent upsert, no duplicate row).
        mapping.put("local-1", "remote-2");
        assertThat(mapping.remoteId("local-1")).contains("remote-2");

        mapping.remove("local-1");
        assertThat(mapping.remoteId("local-1")).isEmpty();
    }

    @Test
    void ensureSchemaIsIdempotent() {
        mapping.ensureSchema(); // second call must not fail
        mapping.put("local-2", "remote-9");
        assertThat(mapping.remoteId("local-2")).contains("remote-9");
    }
}
