package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

/**
 * Live runtime portability test on Oracle (design ch. 42): booting exercises the
 * {@code <component>-oracle} framework migrations and every store's vendor schema bootstrap, a
 * route answers from Oracle, and the managed identity pack's Oracle MERGE contracts seed and
 * authenticate an administrator. Gated behind {@code -Dtesseraql.dialect.its=true} (large image).
 */
@Testcontainers
@EnabledIfSystemProperty(named = "tesseraql.dialect.its", matches = "true")
class OraclePortabilityIntegrationTest {

    @Container
    static final OracleContainer ORACLE = new OracleContainer(
            "gvenzl/oracle-free:23-slim-faststart");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void bootsAndServesAQueryRouteOnOracle() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/users")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).path("data").get(0).path("name").asText())
                .isEqualTo("sato");
    }

    @Test
    void managedIdentityPackWorksOnOracle() throws Exception {
        DialectIdentityChecks.seedAndAuthenticate(dataSource(), "oracle");
    }

    @Test
    void outboxCommandClaimsAndDispatchesOnThisDialect() throws Exception {
        DialectRuntimeChecks.outboxRoundTrip(runtime);
    }

    @Test
    void messagingEventChannelClaimsOnThisDialect() throws Exception {
        DialectRuntimeChecks.eventChannelRoundTrip(dataSource());
    }

    @Test
    void fileTransfersRoundTripOnThisDialect() throws Exception {
        DialectRuntimeChecks.fileTransferRoundTrip(runtime, "oracle-demo");
    }

    private static javax.sql.DataSource dataSource() throws Exception {
        oracle.jdbc.datasource.impl.OracleDataSource dataSource = new oracle.jdbc.datasource.impl.OracleDataSource();
        dataSource.setURL(ORACLE.getJdbcUrl());
        dataSource.setUser(ORACLE.getUsername());
        dataSource.setPassword(ORACLE.getPassword());
        return dataSource;
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table users (name varchar2(200) primary key, "
                    + "status varchar2(32) not null)");
            statement.execute("insert into users (name, status) values ('sato', 'ACTIVE')");
            statement.execute(
                    "create table items (name varchar2(100) primary key, qty number(10) not null)");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-oracle-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: oracle-demo
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword()));
        Path route = home.resolve("web/api/users");
        Files.createDirectories(route);
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: users.list
                kind: route
                recipe: query-json
                sql:
                  file: list.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(route.resolve("list.sql"),
                "select name, status from users order by name\n;\n");
        DialectRuntimeChecks.writeTransferRoutes(home);
        return home;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
