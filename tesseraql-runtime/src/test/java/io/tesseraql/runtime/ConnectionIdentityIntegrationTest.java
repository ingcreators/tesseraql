package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.core.jdbc.ConnectionProperties;
import io.tesseraql.core.jdbc.DriverManagerDataSource;
import io.tesseraql.pipeline.tenant.PoolRole;
import io.tesseraql.yaml.config.AppConfig;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Every PostgreSQL connection TesseraQL opens says whose it is (docs/connection-liveness.md
 * decision 1), read back where an operator reads it: the server's {@code application_name}. It
 * used to be the driver's "PostgreSQL JDBC Driver" for every pool of every application.
 */
@Testcontainers
class ConnectionIdentityIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void anApplicationsPoolsSayWhoseTheyAre() throws Exception {
        AppConfig config = application("orders", with(coordinate(POSTGRES.getJdbcUrl()),
                "jobPool", Map.of("maximumPoolSize", 1, "minimumIdle", 0)));
        Map<String, HikariDataSource> pools = DataSources.createAll(config);
        MainRoles roles = MainRoles.create(config, null, null);
        try {
            assertThat(applicationName(pools.get("main"))).isEqualTo("tesseraql/orders/main");
            assertThat(applicationName(roles.of(PoolRole.JOBS)))
                    .isEqualTo("tesseraql/orders/main-jobs");
        } finally {
            roles.close();
            pools.values().forEach(HikariDataSource::close);
        }
    }

    @Test
    void aTenantsPoolAndTheStacksPoolSayWhoseTheyAre() throws Exception {
        TenantDataSources tenants = TenantDataSources.load(new AppConfig(Map.of(
                "tesseraql", Map.of("app", Map.of("name", "orders")),
                "tenancy", Map.of("mode", "database-per-tenant",
                        "datasources", Map.of("acme", coordinate(POSTGRES.getJdbcUrl()))))));
        try {
            assertThat(applicationName(tenants.resolve("acme", PoolRole.ONLINE)))
                    .isEqualTo("tesseraql/orders/tenant-acme");
        } finally {
            tenants.close();
        }

        try (HikariDataSource framework = DataSources.createStackFramework(new AppConfig(Map.of(
                "framework", Map.of("datasource", coordinate(POSTGRES.getJdbcUrl())))), null)) {
            assertThat(applicationName(framework)).as("the stack's pool names no application")
                    .isEqualTo("tesseraql/stack-framework");
        }
    }

    @Test
    void aUrlsOwnApplicationNameWins() throws Exception {
        Map<String, HikariDataSource> pools = DataSources.createAll(application("orders",
                coordinate(POSTGRES.getJdbcUrl() + "&ApplicationName=ops-probe")));
        try {
            assertThat(applicationName(pools.get("main"))).isEqualTo("ops-probe");
        } finally {
            pools.values().forEach(HikariDataSource::close);
        }
    }

    /** An application named in Japanese arrives in the wire form the front door routes it by. */
    @Test
    void aUnicodeApplicationNameArrivesPercentEncoded() throws Exception {
        Map<String, HikariDataSource> pools = DataSources.createAll(application("受注",
                coordinate(POSTGRES.getJdbcUrl())));
        try {
            assertThat(applicationName(pools.get("main")))
                    .isEqualTo("tesseraql/%E5%8F%97%E6%B3%A8/main");
        } finally {
            pools.values().forEach(HikariDataSource::close);
        }
    }

    @Test
    void aToolAndAJobRunSayWhoseTheyAre() throws Exception {
        DriverManagerDataSource tool = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        assertThat(applicationName(tool)).isEqualTo("tesseraql/tool");
        assertThat(applicationName(tool.labelled(ConnectionProperties.jobRunLabel("orders"))))
                .isEqualTo("tesseraql/orders/job-run");
    }

    private static String applicationName(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select current_setting('application_name')")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static AppConfig application(String name, Map<String, Object> main) {
        return new AppConfig(Map.of("tesseraql", Map.of(
                "app", Map.of("name", name),
                "datasources", Map.of("main", main))));
    }

    private static Map<String, Object> coordinate(String jdbcUrl) {
        return Map.of("jdbcUrl", jdbcUrl, "username", POSTGRES.getUsername(),
                "password", POSTGRES.getPassword());
    }

    private static Map<String, Object> with(Map<String, Object> base, Object... entries) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>(base);
        for (int i = 0; i < entries.length; i += 2) {
            merged.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return merged;
    }
}
