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
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Live runtime portability test on SQL Server (design ch. 42): booting exercises the
 * {@code <component>-sqlserver} framework migrations and every store's vendor schema bootstrap,
 * a route answers from SQL Server, and the managed identity pack's MERGE contracts seed and
 * authenticate an administrator. Gated behind {@code -Dtesseraql.dialect.its=true} (large,
 * license-gated image).
 */
@Testcontainers
@EnabledIfSystemProperty(named = "tesseraql.dialect.its", matches = "true")
class SqlServerPortabilityIntegrationTest {

    @Container
    static final MSSQLServerContainer<?> SQLSERVER = new MSSQLServerContainer<>(
            "mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense();

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
    void bootsAndServesAQueryRouteOnSqlServer() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/users")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).path("data").get(0).path("name").asText())
                .isEqualTo("sato");
    }

    @Test
    void managedIdentityPackWorksOnSqlServer() throws Exception {
        DialectIdentityChecks.seedAndAuthenticate(dataSource(), "sqlserver");
    }

    @Test
    void outboxCommandClaimsAndDispatchesOnThisDialect() throws Exception {
        DialectRuntimeChecks.outboxRoundTrip(runtime);
    }

    @Test
    void fileTransfersRoundTripOnThisDialect() throws Exception {
        DialectRuntimeChecks.fileTransferRoundTrip(runtime, "sqlserver-demo");
    }

    private static javax.sql.DataSource dataSource() {
        com.microsoft.sqlserver.jdbc.SQLServerDataSource dataSource = new com.microsoft.sqlserver.jdbc.SQLServerDataSource();
        dataSource.setURL(SQLSERVER.getJdbcUrl());
        dataSource.setUser(SQLSERVER.getUsername());
        dataSource.setPassword(SQLSERVER.getPassword());
        return dataSource;
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                SQLSERVER.getJdbcUrl(), SQLSERVER.getUsername(), SQLSERVER.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table users (name varchar(200) primary key, "
                    + "status varchar(32) not null)");
            statement.execute("insert into users (name, status) values ('sato', 'ACTIVE')");
            statement.execute(
                    "create table items (name varchar(100) primary key, qty int not null)");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-sqlserver-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: sqlserver-demo
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(SQLSERVER.getJdbcUrl(), SQLSERVER.getUsername(),
                SQLSERVER.getPassword()));
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
