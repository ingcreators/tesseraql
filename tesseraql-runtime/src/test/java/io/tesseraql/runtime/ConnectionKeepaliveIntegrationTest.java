package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.core.jdbc.DriverManagerDataSource;
import io.tesseraql.core.jdbc.KeepaliveSocketFactory;
import io.tesseraql.yaml.config.AppConfig;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.sql.Connection;
import java.util.Map;
import java.util.Set;
import javax.net.SocketFactory;
import jdk.net.ExtendedSocketOptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A PostgreSQL connection's own socket keeps alive with TesseraQL's timings
 * (docs/connection-liveness.md decision 2), read from the driver's socket itself. The driver used
 * to leave keepalive off and wait on a read forever, so a statement whose database host vanished
 * never failed.
 *
 * <p>A vanished host is not simulated: the test's peer here is Docker's userland proxy, whose
 * kernel answers the probes whatever the container does. So this holds the configuration, and the
 * kernel holds the behaviour.
 */
@Testcontainers
class ConnectionKeepaliveIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void aPooledConnectionsSocketKeepsAliveWithTesseraqlsTimings() throws Exception {
        java.util.LinkedHashMap<String, HikariDataSource> pools = DataSources.createAll(
                application(POSTGRES.getJdbcUrl()));
        try (Connection connection = pools.get("main").getConnection()) {
            assertKeptAliveWithTesseraqlsTimings(socketOf(connection));
        } finally {
            pools.values().forEach(HikariDataSource::close);
        }
    }

    @Test
    void aToolsConnectionKeepsAliveToo() throws Exception {
        try (Connection connection = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()).getConnection()) {
            assertKeptAliveWithTesseraqlsTimings(socketOf(connection));
        }
    }

    /**
     * A URL that names its own factory — a cloud provider's connector, say — keeps it, and the
     * connection still keeps alive, with the operating system's timings.
     */
    @Test
    void aUrlsOwnSocketFactoryWins() throws Exception {
        java.util.LinkedHashMap<String, HikariDataSource> pools = DataSources.createAll(
                application(POSTGRES.getJdbcUrl() + "&socketFactory="
                        + PlainSocketFactory.class.getName()));
        try (Connection connection = pools.get("main").getConnection()) {
            Socket socket = socketOf(connection);
            assertThat(socket.getKeepAlive()).isTrue();
            if (supportsTimings(socket)) {
                assertThat(socket.getOption(ExtendedSocketOptions.TCP_KEEPIDLE))
                        .as("the URL's factory, not TesseraQL's")
                        .isNotEqualTo(KeepaliveSocketFactory.IDLE_SECONDS);
            }
        } finally {
            pools.values().forEach(HikariDataSource::close);
        }
    }

    /** The factory a URL names in the test above: plain sockets, no timings of its own. */
    public static final class PlainSocketFactory extends SocketFactory {

        @Override
        public Socket createSocket() {
            return new Socket();
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return new Socket(host, port);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws IOException {
            return new Socket(host, port, localHost, localPort);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return new Socket(host, port);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
                int localPort) throws IOException {
            return new Socket(address, port, localAddress, localPort);
        }
    }

    private static void assertKeptAliveWithTesseraqlsTimings(Socket socket) throws IOException {
        assertThat(socket.getKeepAlive()).as("SO_KEEPALIVE").isTrue();
        if (supportsTimings(socket)) {
            assertThat(socket.getOption(ExtendedSocketOptions.TCP_KEEPIDLE))
                    .isEqualTo(KeepaliveSocketFactory.IDLE_SECONDS);
            assertThat(socket.getOption(ExtendedSocketOptions.TCP_KEEPINTERVAL))
                    .isEqualTo(KeepaliveSocketFactory.INTERVAL_SECONDS);
            assertThat(socket.getOption(ExtendedSocketOptions.TCP_KEEPCOUNT))
                    .isEqualTo(KeepaliveSocketFactory.COUNT);
        }
    }

    private static boolean supportsTimings(Socket socket) {
        return socket.supportedOptions().containsAll(Set.of(ExtendedSocketOptions.TCP_KEEPIDLE,
                ExtendedSocketOptions.TCP_KEEPINTERVAL, ExtendedSocketOptions.TCP_KEEPCOUNT));
    }

    /**
     * The driver's own socket for {@code connection}. The driver keeps it in its query executor's
     * stream and offers no public way to it, so the test reads the field.
     */
    private static Socket socketOf(Connection connection) throws Exception {
        Object executor = connection.unwrap(org.postgresql.core.BaseConnection.class)
                .getQueryExecutor();
        java.lang.reflect.Field stream = org.postgresql.core.QueryExecutorBase.class
                .getDeclaredField("pgStream");
        stream.setAccessible(true);
        return ((org.postgresql.core.PGStream) stream.get(executor)).getSocket();
    }

    private static AppConfig application(String jdbcUrl) {
        return new AppConfig(Map.of("tesseraql", Map.of(
                "app", Map.of("name", "orders"),
                "datasources", Map.of("main", Map.of("jdbcUrl", jdbcUrl,
                        "username", POSTGRES.getUsername(),
                        "password", POSTGRES.getPassword())))));
    }
}
