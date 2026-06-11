package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.identity.DefaultIdentityPack;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for the bundled IAM admin console app (design ch. 10, 32): the yaml/sql/template
 * app shipped in tesseraql-identity mounts automatically, serving the user list, a per-user detail
 * page (roles, groups, permissions) and post/redirect/get status actions; callers without a bearer
 * principal are denied.
 */
@Testcontainers
class IamAdminIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
    void listsUsersForAuthorizedCaller() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/admin/users", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/html"));
        assertThat(response.headers().firstValue("content-security-policy"))
                .hasValueSatisfying(value -> assertThat(value).contains("default-src 'self'"));
        assertThat(response.body()).startsWith("<!DOCTYPE html>");
        assertThat(response.body()).contains("IAM Admin").contains("admin").contains("bob");
        assertThat(response.body()).contains("/_tesseraql/admin/users/u1");
    }

    @Test
    void showsUserDetailWithRolesGroupsPermissions() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/admin/users/u1", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Administrator");
        assertThat(response.body()).contains("USER_READ");
        assertThat(response.body()).contains("OPS");
        assertThat(response.body()).contains("users:read");
    }

    @Test
    void disableThenEnableUserViaForm() throws Exception {
        // The detail page offers the status actions as plain form posts; the destructive one is
        // guarded by the hc confirm behavior (data-hc-confirm dialog before submit).
        HttpResponse<String> before = get("/_tesseraql/admin/users/u2", true);
        assertThat(before.body()).contains("Disable user")
                .contains("/_tesseraql/admin/users/u2/disable")
                .contains("data-hc-confirm=\"Disable user bob?\"");

        // post/redirect/get: the command answers 303 back to the detail page.
        HttpResponse<String> disabled = post("/_tesseraql/admin/users/u2/disable");
        assertThat(disabled.statusCode()).isEqualTo(303);
        assertThat(disabled.headers().firstValue("location"))
                .hasValue("/_tesseraql/admin/users/u2");
        assertThat(get("/_tesseraql/admin/users/u2", true).body()).contains("DISABLED");

        HttpResponse<String> enabled = post("/_tesseraql/admin/users/u2/enable");
        assertThat(enabled.statusCode()).isEqualTo(303);
        assertThat(get("/_tesseraql/admin/users/u2", true).body()).contains("ACTIVE");
    }

    @Test
    void writeRequiresAuthentication() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + runtime.port()
                                + "/_tesseraql/admin/users/u2/disable"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void rendersNotFoundPageForUnknownUser() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/admin/users/missing", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("User not found.");
    }

    @Test
    void requiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/admin/users", false).statusCode()).isEqualTo(401);
    }

    private static HttpResponse<String> get(String path, boolean auth) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path));
        if (auth) {
            request.header("Authorization", "Bearer " + token());
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + runtime.port() + path))
                .header("Authorization", "Bearer " + token())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(
                MAPPER.writeValueAsBytes(Map.of("sub", "iam-admin", "roles", List.of("ADMIN"))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table users (id serial primary key, name varchar(200), "
                    + "status varchar(32) not null, created_at timestamp default now())");
            for (String ddl : DefaultIdentityPack.schema("postgres").split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
            statement.execute("insert into tql_users (user_id, login_id, display_name, email, status) "
                    + "values ('u1','admin','Administrator','admin@example.com','ACTIVE')");
            statement.execute("insert into tql_users (user_id, login_id, display_name, status) "
                    + "values ('u2','bob','Bob','ACTIVE')");
            statement.execute("insert into tql_roles (role_id, role_code, role_name) "
                    + "values ('r1','USER_READ','User Read')");
            statement.execute("insert into tql_user_roles (user_id, role_id) values ('u1','r1')");
            statement.execute("insert into tql_groups (group_id, group_code, group_name) "
                    + "values ('g1','OPS','Operations')");
            statement.execute("insert into tql_user_groups (user_id, group_id) values ('u1','g1')");
            statement.execute("insert into tql_permissions "
                    + "(permission_id, permission_code, permission_name) "
                    + "values ('p1','users:read','Read users')");
            statement.execute("insert into tql_role_permissions (role_id, permission_id) "
                    + "values ('r1','p1')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-iam-admin-it");
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
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
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
