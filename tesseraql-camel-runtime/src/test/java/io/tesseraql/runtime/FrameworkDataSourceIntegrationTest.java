package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The framework-datasource split (docs/framework-datasource.md): with
 * {@code tesseraql.framework.datasource} naming a second database, bucket-3 state —
 * sessions here as the representative — bootstraps and lives THERE, while business
 * tables stay on {@code main}. An unknown name refuses the boot: a typo that silently
 * fell back to {@code main} would defeat the isolation someone configured.
 */
@Testcontainers
class FrameworkDataSourceIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String frameworkUrl;

    @BeforeAll
    static void start() throws Exception {
        // A second database in the same container: the genuinely-separate shape.
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create database framework_state");
        }
        frameworkUrl = POSTGRES.getJdbcUrl().replaceAll("/[^/?]+(\\?|$)", "/framework_state$1");
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            try (Stream<Path> files = Files.walk(appHome)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void sessionsLiveOnTheFrameworkDatabaseNotOnMain() throws Exception {
        io.tesseraql.security.session.SessionStore sessions = runtime.camelContext()
                .getRegistry().lookupByNameAndType(
                        io.tesseraql.camel.TesseraqlProperties.SESSION_STORE_BEAN,
                        io.tesseraql.security.session.SessionStore.class);
        String sid = sessions.create(new io.tesseraql.security.Principal(
                "fw-user", "fw-user", "FW User", null, List.of(), List.of("ADMIN"),
                List.of(), Map.of()), io.tesseraql.security.session.SessionStore.ClientInfo.NONE);
        assertThat(sessions.session(sid)).isNotNull();

        // The row is IN the framework database...
        assertThat(count(frameworkUrl, "select count(*) from tql_session"))
                .isGreaterThanOrEqualTo(1);
        // ...and main has no session table at all.
        assertThat(count(POSTGRES.getJdbcUrl(),
                "select count(*) from information_schema.tables"
                        + " where table_name = 'tql_session'"))
                .isZero();
    }

    /**
     * The 2026-08-10 amendment (docs/app-isolation-model.md): the route audit store writes at
     * business request rate, so it stays on the business datasource rather than the pool that
     * exists to keep business load off login. It is the one bucket-3 store that moved back.
     */
    @Test
    void auditBootstrapsOnTheBusinessDatabaseNotOnFramework() throws Exception {
        assertThat(count(POSTGRES.getJdbcUrl(),
                "select count(*) from information_schema.tables"
                        + " where table_name = 'tql_route_audit'"))
                .as("the audit table belongs with the other ops tables, on the business database")
                .isEqualTo(1);

        assertThat(count(frameworkUrl,
                "select count(*) from information_schema.tables"
                        + " where table_name = 'tql_route_audit'"))
                .as("business-rate writes must not land on the login pool's database")
                .isZero();
    }

    @Test
    void anUnknownDatasourceNameRefusesTheBoot() throws Exception {
        Path broken = Files.createTempDirectory("tesseraql-fwds-broken");
        Files.createDirectories(broken.resolve("config"));
        Files.writeString(broken.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  framework:
                    datasource: no-such-pool
                  app:
                    name: fwds-broken
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        try {
            assertThatThrownBy(() -> TesseraqlRuntime.start(broken, freePort()))
                    .hasMessageContaining("no-such-pool")
                    .hasMessageContaining("tesseraql.framework.datasource");
        } finally {
            try (Stream<Path> files = Files.walk(broken)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static long count(String url, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url,
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-fwds-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  sessions:
                    store: jdbc
                  audit:
                    routes:
                      enabled: true
                  framework:
                    datasource: framework
                  app:
                    name: fwds-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                    framework:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), frameworkUrl, POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        return target;
    }
}
