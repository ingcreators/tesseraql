package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for shared browser sessions across runtime nodes (design ch. 11.2): with
 * tesseraql.sessions.store=jdbc, a login made on one node is honored by another node sharing the
 * same database, and an invalidated session disappears everywhere.
 */
@Testcontainers
class SharedSessionIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime nodeA;
    static TesseraqlRuntime nodeB;
    static Path homeA;
    static Path homeB;

    @BeforeAll
    static void start() throws Exception {
        homeA = prepareAppHome("node-a");
        homeB = prepareAppHome("node-b");
        nodeA = TesseraqlRuntime.start(homeA, freePort());
        nodeB = TesseraqlRuntime.start(homeB, freePort());
        seedDatabase();
    }

    @AfterAll
    static void stop() throws IOException {
        for (TesseraqlRuntime runtime : new TesseraqlRuntime[]{nodeA, nodeB}) {
            if (runtime != null) {
                runtime.close();
            }
        }
        for (Path home : new Path[]{homeA, homeB}) {
            if (home != null) {
                deleteRecursively(home);
            }
        }
    }

    @Test
    void loginOnOneNodeAuthorizesOnAnother() throws Exception {
        // Login happens on node A...
        HttpResponse<String> login = post(nodeA.port(), "/_tesseraql/login",
                "{\"loginId\":\"admin\",\"password\":\"s3cret\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        String cookie = cookieOf(login);

        // ...and the session cookie authorizes a browser-auth route on node B.
        HttpResponse<String> fragment = getWithCookie(nodeB.port(), "/users/fragments/table",
                cookie);
        assertThat(fragment.statusCode()).isEqualTo(200);
        assertThat(fragment.body()).contains("<table");
    }

    @Test
    void unknownSessionIsRejectedOnEveryNode() throws Exception {
        String cookie = "tesseraql_sid=00000000-0000-0000-0000-000000000000";
        assertThat(getWithCookie(nodeA.port(), "/users/fragments/table", cookie).statusCode())
                .isEqualTo(401);
        assertThat(getWithCookie(nodeB.port(), "/users/fragments/table", cookie).statusCode())
                .isEqualTo(401);
    }

    private static String cookieOf(HttpResponse<String> login) {
        String setCookie = login.headers().firstValue("Set-Cookie").orElse("");
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private static HttpResponse<String> getWithCookie(int port, String path, String cookie)
            throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(int port, String path, String json) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
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
            statement.execute("truncate table users restart identity");
            statement.execute("insert into users (name, status) values ('sato','ACTIVE')");
            for (String ddl : io.tesseraql.identity.DefaultIdentityPack.schema("postgres")
                    .split(";")) {
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
        }
    }

    private static Path prepareAppHome(String node) throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-session-" + node);
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

                tesseraql:
                  sessions:
                    store: jdbc
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
