package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.identity.DefaultIdentityPack;
import io.tesseraql.security.password.Pbkdf2PasswordEncoder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for IAM password login (design ch. 10.8, 11.2): a managed-realm user logs in
 * with a password and the issued session cookie authorizes a browser route.
 */
@Testcontainers
class PasswordLoginIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
    void loginIssuesSessionThatAuthorizesBrowserRoute() throws Exception {
        HttpResponse<String> login = post("/_tesseraql/login",
                "{\"loginId\":\"admin\",\"password\":\"s3cret\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        String setCookie = login.headers().firstValue("Set-Cookie").orElse("");
        assertThat(setCookie).contains("tesseraql_sid=");

        String cookie = setCookie.substring(0, setCookie.indexOf(';'));
        HttpResponse<String> fragment = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/users/fragments/table"))
                        .header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(fragment.statusCode()).isEqualTo(200);
        assertThat(fragment.body()).contains("<table");
    }

    @Test
    void adminUsersListServedFromIdentityContract() throws Exception {
        HttpResponse<String> login = post("/_tesseraql/login",
                "{\"loginId\":\"admin\",\"password\":\"s3cret\"}");
        String setCookie = login.headers().firstValue("Set-Cookie").orElse("");
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));

        HttpResponse<String> page = HttpClient.newHttpClient().send(
                HttpRequest
                        .newBuilder(
                                URI.create("http://localhost:" + runtime.port() + "/admin/users"))
                        .header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type").orElse("")).contains("text/html");
        assertThat(page.body()).contains("User Management").contains("admin"); // login_id from the contract
    }

    @Test
    void adminDisableUserViaWriteContract() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/admin/users/u2/disable"))
                        .header("Authorization", "Bearer " + writerToken())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(status("u2")).isEqualTo("DISABLED");
    }

    private static String status(String userId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                java.sql.ResultSet rs = statement.executeQuery(
                        "select status from tql_users where user_id = '" + userId + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static String writerToken() throws Exception {
        java.util.Base64.Encoder enc = java.util.Base64.getUrlEncoder().withoutPadding();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String header = enc.encodeToString(
                "{\"alg\":\"HS256\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String payload = enc.encodeToString(mapper.writeValueAsBytes(
                java.util.Map.of("sub", "ops", "roles", java.util.List.of("USER_WRITE"))));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                "dev-only-secret-change-me-in-production"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload)
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        HttpResponse<String> login = post("/_tesseraql/login",
                "{\"loginId\":\"admin\",\"password\":\"wrong\"}");
        assertThat(login.statusCode()).isEqualTo(401);
    }

    private static HttpResponse<String> post(String path, String json) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void seedDatabase() throws Exception {
        String hash = new Pbkdf2PasswordEncoder().encode("s3cret");
        String params = new Pbkdf2PasswordEncoder().defaultParams();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            // The app `users` table used by the htmx fragment route is created and demo-seeded by
            // the example's db/migration at mount. Standard IAM schema + a managed user follows.
            for (String ddl : DefaultIdentityPack.schema("postgres").split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
            statement.execute("insert into tql_users "
                    + "(user_id, login_id, display_name, status, password_hash, password_algo, password_params) "
                    + "values ('u1','admin','Administrator','ACTIVE','" + hash
                    + "','pbkdf2','" + params + "')");
            statement.execute("insert into tql_roles (role_id, role_code, role_name) "
                    + "values ('r1','USER_READ','User Read')");
            statement.execute("insert into tql_user_roles (user_id, role_id) values ('u1','r1')");
            // A second user to disable via the admin write contract.
            statement.execute("insert into tql_users (user_id, login_id, display_name, status) "
                    + "values ('u2','bob','Bob','ACTIVE')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-login-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
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
        return target;
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

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
