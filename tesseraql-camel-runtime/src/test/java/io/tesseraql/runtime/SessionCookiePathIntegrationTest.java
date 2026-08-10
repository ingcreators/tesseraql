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
 * Who decides the session cookie's {@code Path} (docs/base-path.md decision 4).
 *
 * <p>Under a base path there are two correct answers and they conflict. A standalone application
 * behind a proxy at {@code /myapp} should not offer its session to whatever else lives on that
 * origin, so its cookie is scoped to its own prefix. A shared suite must do the opposite: one
 * sign-in across the suite <em>is</em> the mode, and scoping per prefix would make every
 * application a separate sign-in.
 *
 * <p>So the value is not derived from the base path. It is supplied by whatever starts the
 * runtime, and these two tests are the two answers.
 */
@Testcontainers
class SessionCookiePathIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static Path appHome;

    @BeforeAll
    static void seed() throws Exception {
        appHome = prepareAppHome();
        seedDatabase();
    }

    @AfterAll
    static void cleanUp() throws IOException {
        deleteRecursively(appHome);
    }

    /** No host says otherwise, so the standalone answer applies: the application's own prefix. */
    @Test
    void aStandaloneApplicationScopesItsCookieToItsOwnPrefix() throws Exception {
        try (TesseraqlRuntime runtime = TesseraqlRuntime.start(appHome, freePort(),
                "/myapp", null)) {
            assertThat(setCookieOnLogin(runtime)).contains("Path=/myapp");
        }
    }

    /** The suite host says otherwise, because only it knows these applications share a sign-in. */
    @Test
    void aSuiteHostIssuesTheCookieAtTheOriginRoot() throws Exception {
        try (TesseraqlRuntime runtime = TesseraqlRuntime.start(appHome, freePort(),
                "/apps/shop-a", "/")) {
            assertThat(setCookieOnLogin(runtime))
                    .contains("Path=/;")
                    .doesNotContain("Path=/apps/shop-a");
        }
    }

    private static String setCookieOnLogin(TesseraqlRuntime runtime) throws Exception {
        HttpResponse<String> login = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port()
                        + basePathOf(runtime) + "/_tesseraql/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"loginId\":\"admin\",\"password\":\"s3cret\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        return login.headers().firstValue("Set-Cookie").orElse("");
    }

    /** The prefix the runtime was started under — it serves there, so the test asks there. */
    private static String basePathOf(TesseraqlRuntime runtime) {
        return io.tesseraql.camel.BasePath.of(runtime.camelContext());
    }

    private static void seedDatabase() throws Exception {
        String hash = new Pbkdf2PasswordEncoder().encode("s3cret");
        String params = new Pbkdf2PasswordEncoder().defaultParams();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (String ddl : io.tesseraql.identity.DefaultIdentityPack.schema("postgres")
                    .split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
            statement.execute("insert into tql_users "
                    + "(user_id, login_id, display_name, status, password_hash, password_algo,"
                    + " password_params) values ('u1','admin','Administrator','ACTIVE','" + hash
                    + "','pbkdf2','" + params + "')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-cookie-path");
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
        if (root == null || !Files.exists(root)) {
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
