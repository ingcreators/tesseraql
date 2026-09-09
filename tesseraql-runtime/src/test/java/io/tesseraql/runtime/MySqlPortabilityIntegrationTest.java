package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Portability test (design ch. 42): the same example app runs on MySQL by swapping the JDBC driver,
 * and the dialect-specific SQL variant (search.mysql.sql) is selected automatically.
 */
@Testcontainers
class MySqlPortabilityIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
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
    void runsOnMySqlAndUsesDialectVariant() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/users?limit=10"))
                        .header("Authorization", "Bearer " + token())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.path("data")).isNotEmpty();
        // The 'source' column only exists in search.mysql.sql, proving the variant was selected.
        assertThat(body.path("data").get(0).path("source").asText()).isEqualTo("mysql");
    }

    /**
     * The export's named queries must run before the extraction opens its result set. They compose
     * on the extraction's own connection, and MySQL's row-streaming fetch size makes that
     * connection unusable for any other statement until the result set closes — so running them
     * inside the reader is the difference between a document and
     * {@code Streaming result set ... is still active}.
     */
    @Test
    void anExportWithANamedQueryStreamsOnAConnectionThatIsBusy() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port()
                        + "/api/users/export-with-totals"))
                        .header("Authorization", "Bearer " + token())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // The body is in the message because a route failure logs nothing: without it a red run
        // here says only "expected 200 but was 500".
        assertThat(response.statusCode()).as("body: %s", response.body()).isEqualTo(200);
        assertThat(response.body()).contains("sato");
    }

    @Test
    void managedIdentitySchemaAndBootstrapContractsWorkOnMySql() throws Exception {
        DialectIdentityChecks.seedAndAuthenticate(mysqlDataSource(), "mysql");
    }

    @Test
    void bundledScimGroupSetRoundTripsOnThisDialect() throws Exception {
        DialectScimGroupChecks.bundledGroupSetRoundTrip(mysqlDataSource(), "mysql");
    }

    @Test
    void japaneseIdentifiersRoundTripOnThisDialect() throws Exception {
        DialectRuntimeChecks.japaneseIdentifiersRoundTrip(mysqlDataSource(), "mysql",
                "varchar(%d)");
    }

    @Test
    void batchRunOwnershipAndHeartbeatWorkOnThisDialect() throws Exception {
        DialectRuntimeChecks.jobOwnershipRoundTrip(mysqlDataSource());
    }

    @Test
    void pollSourceExclusiveConsumptionWorksOnThisDialect() throws Exception {
        DialectRuntimeChecks.pollConsumedRoundTrip(mysqlDataSource());
    }

    @Test
    void clusterJobClaimingSkipsTheLoserOnThisDialect() throws Exception {
        DialectRuntimeChecks.jobClaimRoundTrip(mysqlDataSource());
    }

    @Test
    void theCatalogVersionTableAppliesOnThisDialect() throws Exception {
        DialectRuntimeChecks.catalogVersionTableApplies(mysqlDataSource());
    }

    @Test
    void documentSequencesSeedAndAllocateOnThisDialect() throws Exception {
        DialectRuntimeChecks.documentSequenceRoundTrip(mysqlDataSource());
    }

    private static javax.sql.DataSource mysqlDataSource() {
        com.mysql.cj.jdbc.MysqlDataSource dataSource = new com.mysql.cj.jdbc.MysqlDataSource();
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUser(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        return dataSource;
    }

    private static String token() throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(
                MAPPER.writeValueAsBytes(
                        TestClaims.addressed(Map.of("sub", "u1", "roles", List.of("USER_READ")))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table users (id int auto_increment primary key, "
                    + "name varchar(200), status varchar(32) not null, "
                    + "created_at timestamp default current_timestamp)");
            statement.execute("insert into users (name, status) values ('sato','ACTIVE')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-mysql-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        UserAdminAppJobs.parkDailyMaintenanceSchedule(target);
        writeExportWithANamedQuery(target);
        // The example's db/migration is Postgres DDL; this dialect test builds its own MySQL schema
        // in seedDatabase(), so disable the app migration for this mount.
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s

                tesseraql:
                  app:
                    name: my-sql-portability
                  migrations:
                    enabled: false
                """.formatted(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        return target;
    }

    /**
     * An export that declares a named source beside {@code main}, which the compiler turns into an
     * export query (docs/export-pipeline.md decision 2: they run before the extraction, on its
     * connection and inside its transaction, so a document reads exactly the state its rows came
     * from). Neither shipped example declares one, which is why nothing exercised the ordering.
     *
     * <p>It lives here rather than in the example because MySQL is where the ordering is
     * observable: the extraction streams at {@code Integer.MIN_VALUE}, and Connector/J refuses any
     * further statement on that connection while the result set is open.
     */
    private static void writeExportWithANamedQuery(Path target) throws IOException {
        Path dir = target.resolve("web/api/users/export-with-totals");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("get.yml"), """
                version: tesseraql/v1
                id: users.export.with.totals
                kind: route
                recipe: query-export

                security:
                  policy: users.read

                export:
                  format: csv
                  filename: users-with-totals.csv

                sources:
                  main:
                    sql:
                      file: export.sql
                  totals:
                    sql:
                      file: totals.sql
                """);
        Files.writeString(dir.resolve("export.sql"),
                "select u.id, u.name, u.status from users u order by u.id\n");
        Files.writeString(dir.resolve("totals.sql"),
                "select count(*) as total from users\n");
    }

    private static void copy(Path source, Path target, Path path) {
        try {
            Path destination = target.resolve(source.relativize(path).toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
            } else {
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
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

}
