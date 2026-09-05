package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for the large-data materialization guard (design ch. 28.7): a query that would
 * materialize more than the configured maxRows fails instead of loading an unbounded result set.
 */
@Testcontainers
class MaterializationGuardIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
        seedDatabase();
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
    void overBudgetQueryIsRejected() throws Exception {
        HttpResponse<String> response = get("/api/users"); // 3 rows, maxRows is 2
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(MAPPER.readTree(response.body()).path("error").path("code").asText())
                .isEqualTo("TQL-LD-0001");
    }

    /**
     * The row bound reaches a contract read, which it never did.
     *
     * <p>A contract compiled to its own step taking four constructor arguments where the SQL step
     * took nine, and {@code effectiveMaxRows} was one of the five it never received — so
     * {@code tesseraql.resultMaterialization.maxRows} did not apply to a contract read at all.
     * This asserts the contract arm now answers exactly as its {@code sql:} sibling does one test
     * up, because after the branch collapsed there is one step serving both.
     */
    @Test
    void anOverBudgetContractReadIsRejectedLikeItsSqlSibling() throws Exception {
        HttpResponse<String> response = get("/api/contract-users"); // 3 identity rows, maxRows 2

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(MAPPER.readTree(response.body()).path("error").path("code").asText())
                .isEqualTo("TQL-LD-0001");
    }

    @Test
    void withinBudgetQuerySucceeds() throws Exception {
        HttpResponse<String> response = get("/api/users?limit=1"); // 1 row, within maxRows
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).path("data")).hasSize(1);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("Authorization", "Bearer " + token())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("truncate table users restart identity");
            statement.execute("insert into users (name, status) values "
                    + "('a','ACTIVE'),('b','ACTIVE'),('c','ACTIVE')");
            // The identity store the contract arm reads, past the same budget. The example app
            // declares a managed realm but ships no identity migrations, so the standard schema
            // is applied here the way the identity suites apply it.
            for (String ddl : io.tesseraql.identity.DefaultIdentityPack.schema("postgres")
                    .split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
            statement.execute("insert into tql_users (user_id, login_id, display_name, status)"
                    + " values ('m1','m-one','One','ACTIVE'),('m2','m-two','Two','ACTIVE'),"
                    + "('m3','m-three','Three','ACTIVE')"
                    + " on conflict (user_id) do nothing");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-ld-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        UserAdminAppJobs.parkDailyMaintenanceSchedule(target);
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        // A contract-backed sibling of /api/users, so the two arms differ only in where their
        // statement comes from. Written here rather than shipped in the example, because its
        // whole purpose is to be read under a deliberately tiny budget.
        Path contractRoute = target.resolve("web/api/contract-users");
        Files.createDirectories(contractRoute);
        Files.writeString(contractRoute.resolve("get.yml"), """
                version: tesseraql/v1
                id: users.contract.search
                kind: route
                recipe: query-json

                security:
                  policy: users.read

                sources:
                  main:
                    contract:
                      name: identity.list-users

                response:
                  json:
                    status: 200
                    body:
                      data: main.rows
                """);
        // Tighten the global materialization budget (block is under the tesseraql: root).
        Files.writeString(target.resolve("config/tesseraql.yml"), """

                  resultMaterialization:
                    maxRows: 2
                    onOverflow: fail
                """, java.nio.file.StandardOpenOption.APPEND);
        return target;
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
