package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.core.jdbc.KeepaliveSocketFactory;
import io.tesseraql.yaml.config.AppConfig;
import java.net.Socket;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import jdk.net.ExtendedSocketOptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.mysql.MySQLContainer;

/**
 * MySQL's and MariaDB's connections say whose they are, and MariaDB's keeps alive with
 * TesseraQL's timings (docs/connection-liveness.md decision 6). The name is read back where the
 * servers keep a client's connection attributes, {@code performance_schema.session_connect_attrs}.
 * MySQL's driver offers no property for the timings, so its connections keep alive with the host's.
 *
 * <p>Both run as root, so the attributes table can be read without a grant.
 */
@Testcontainers
class MySqlFamilyConnectionIntegrationTest {

    @Container
    @SuppressWarnings("resource") // lifecycle is managed by the @Container extension
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0").withUsername("root");

    // MariaDB keeps the attributes only with the Performance Schema on, which its image leaves off.
    @Container
    @SuppressWarnings("resource") // lifecycle is managed by the @Container extension
    static final MariaDBContainer MARIADB = new MariaDBContainer("mariadb:11.4")
            .withUsername("root").withCommand("--performance-schema=ON");

    @Test
    void aMySqlConnectionSaysWhoseItIs() throws Exception {
        java.util.LinkedHashMap<String, HikariDataSource> pools = DataSources.createAll(
                application(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        try (Connection connection = pools.get("main").getConnection()) {
            assertThat(programName(connection)).isEqualTo("tesseraql/orders/main");
        } finally {
            pools.values().forEach(HikariDataSource::close);
        }
    }

    @Test
    void aMariaDbConnectionSaysWhoseItIsAndKeepsAliveWithTesseraqlsTimings() throws Exception {
        java.util.LinkedHashMap<String, HikariDataSource> pools = DataSources.createAll(
                application(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword()));
        try (Connection connection = pools.get("main").getConnection()) {
            assertThat(programName(connection)).isEqualTo("tesseraql/orders/main");

            Socket socket = socketOf(connection);
            assertThat(socket.getKeepAlive()).isTrue();
            if (socket.supportedOptions().containsAll(Set.of(ExtendedSocketOptions.TCP_KEEPIDLE,
                    ExtendedSocketOptions.TCP_KEEPINTERVAL, ExtendedSocketOptions.TCP_KEEPCOUNT))) {
                assertThat(socket.getOption(ExtendedSocketOptions.TCP_KEEPIDLE))
                        .isEqualTo(KeepaliveSocketFactory.IDLE_SECONDS);
                assertThat(socket.getOption(ExtendedSocketOptions.TCP_KEEPINTERVAL))
                        .isEqualTo(KeepaliveSocketFactory.INTERVAL_SECONDS);
                assertThat(socket.getOption(ExtendedSocketOptions.TCP_KEEPCOUNT))
                        .isEqualTo(KeepaliveSocketFactory.COUNT);
            }
        } finally {
            pools.values().forEach(HikariDataSource::close);
        }
    }

    private static String programName(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select ATTR_VALUE"
                        + " from performance_schema.session_connect_attrs"
                        + " where PROCESSLIST_ID = connection_id()"
                        + " and ATTR_NAME = 'program_name'")) {
            assertThat(rs.next()).as("the connection carries a program_name attribute").isTrue();
            return rs.getString(1);
        }
    }

    /**
     * MariaDB's driver's own socket. It keeps it in its client and offers no public way to it, so
     * the test reads the field.
     */
    private static Socket socketOf(Connection connection) throws Exception {
        Object client = connection.unwrap(org.mariadb.jdbc.Connection.class).getClient();
        java.lang.reflect.Field socket = org.mariadb.jdbc.client.impl.StandardClient.class
                .getDeclaredField("socket");
        socket.setAccessible(true);
        return (Socket) socket.get(client);
    }

    private static AppConfig application(String jdbcUrl, String username, String password) {
        return new AppConfig(Map.of("tesseraql", Map.of(
                "app", Map.of("name", "orders"),
                "datasources", Map.of("main", Map.of("jdbcUrl", jdbcUrl,
                        "username", username, "password", password)))));
    }
}
