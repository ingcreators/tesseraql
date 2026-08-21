package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.identity.DefaultIdentityPack;
import io.tesseraql.security.password.Pbkdf2PasswordEncoder;
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
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A person can get a token (docs/stack-architecture.md Decision 20).
 *
 * <p>{@link TokenExchangeIntegrationTest} proves the endpoint mints and that what it mints is
 * accepted. This proves the two routes to reaching it, because the endpoint was correct and
 * unreachable: it requires the session's CSRF token, and that value left the server only inside a
 * page, as {@code <meta name="csrf-token">}. A command-line client could authenticate and then had
 * nowhere to go, and a human's only option was reading a cookie and a meta tag out of browser
 * developer tools.
 *
 * <p>So the assertions here are about arrival rather than about signing: a JSON login answers with
 * the CSRF token, that token is the session's real one, and the console page issues through the
 * same mint without any of it.
 */
@Testcontainers
class TokenAcquisitionIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TOKEN_PAGE = "/_tesseraql/ops/console/token";

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

    /**
     * The whole {@code tesseraql token --url} flow, over the wire, with no browser anywhere: sign
     * in, read the CSRF token out of the JSON answer, exchange, use the result.
     *
     * <p>Each step is what the command does, in the order it does it, so a change that breaks the
     * command breaks this — the CLI's own test drives a stub and cannot notice the server moving.
     */
    @Test
    void aCommandLineCallerSignsInReadsTheCsrfTokenAndExchanges() throws Exception {
        HttpResponse<String> session = post("/_tesseraql/login",
                "{\"loginId\":\"admin\",\"password\":\"s3cret\"}", null, null);
        assertThat(session.statusCode()).isEqualTo(200);

        String cookie = sessionCookie(session);
        assertThat(cookie).as("the session cookie").isNotNull();
        String csrf = MAPPER.readTree(session.body()).path("csrfToken").asText(null);
        assertThat(csrf).as("the CSRF token a non-browser caller cannot otherwise obtain")
                .isNotBlank();

        HttpResponse<String> minted = post("/_tesseraql/token", "{}", cookie, csrf);
        assertThat(minted.statusCode()).isEqualTo(200);

        // The round trip: nothing here is worth anything unless the token opens a bearer route.
        HttpResponse<String> api = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base() + "/api/users"))
                        .header("Authorization",
                                "Bearer " + MAPPER.readTree(minted.body()).path("token").asText())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(api.statusCode()).isEqualTo(200);
    }

    /**
     * The value returned is the session's own token, not a decorative one: the guard that accepts
     * it is the same {@code CsrfValidator} every state-changing browser route runs.
     */
    @Test
    void theReturnedCsrfTokenIsTheOneTheGuardChecks() throws Exception {
        HttpResponse<String> session = post("/_tesseraql/login",
                "{\"loginId\":\"admin\",\"password\":\"s3cret\"}", null, null);
        String cookie = sessionCookie(session);
        String csrf = MAPPER.readTree(session.body()).path("csrfToken").asText();

        assertThat(post("/_tesseraql/token", "{}", cookie, csrf + "x").statusCode())
                .as("a token that is nearly right is still refused")
                .isEqualTo(403);
        assertThat(post("/_tesseraql/token", "{}", cookie, csrf).statusCode()).isEqualTo(200);
    }

    /**
     * The console page: one form post, and the token comes back rendered for copying.
     *
     * <p>It mints through {@link SessionTokens} exactly as the endpoint does, so the claims cannot
     * drift between the page and the API — which is the reason the signing lives in one place.
     */
    @Test
    void theConsolePageIssuesATokenThroughTheSameMint() throws Exception {
        HttpResponse<String> session = post("/_tesseraql/login",
                "{\"loginId\":\"admin\",\"password\":\"s3cret\"}", null, null);
        String cookie = sessionCookie(session);
        String csrf = MAPPER.readTree(session.body()).path("csrfToken").asText();

        HttpResponse<String> page = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base() + TOKEN_PAGE))
                        .header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("Issue a token").doesNotContain("issued-token");

        HttpResponse<String> issued = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base() + TOKEN_PAGE))
                        .header("Cookie", cookie)
                        .header("X-CSRF-Token", csrf)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(issued.statusCode()).isEqualTo(200);
        assertThat(issued.body()).contains("issued-token");
        assertThat(bearerFrom(issued.body())).as("the rendered token")
                .matches("[\\w-]+\\.[\\w-]+\\.[\\w-]+");

        // Rendered is not enough — the page would be a convincing way to hand out a string that
        // opens nothing.
        HttpResponse<String> api = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base() + "/api/users"))
                        .header("Authorization", "Bearer " + bearerFrom(issued.body())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(api.statusCode()).isEqualTo(200);
    }

    /** A session cookie alone does not issue from the page either. */
    @Test
    void theConsolePageRefusesAPostWithoutTheCsrfToken() throws Exception {
        HttpResponse<String> session = post("/_tesseraql/login",
                "{\"loginId\":\"admin\",\"password\":\"s3cret\"}", null, null);

        HttpResponse<String> issued = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base() + TOKEN_PAGE))
                        .header("Cookie", sessionCookie(session))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(""))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(issued.statusCode()).isEqualTo(403);
        assertThat(issued.body()).doesNotContain("issued-token");
    }

    /** The token out of {@code <code id="issued-token">…</code>}. */
    private static String bearerFrom(String html) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("id=\"issued-token\"[^>]*>([^<]+)<").matcher(html);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    private static String sessionCookie(HttpResponse<String> response) {
        for (String header : response.headers().allValues("Set-Cookie")) {
            String pair = header.split(";", 2)[0].trim();
            if (pair.contains("=") && !pair.endsWith("=")) {
                return pair;
            }
        }
        return null;
    }

    private static String base() {
        return "http://localhost:" + runtime.port();
    }

    private static HttpResponse<String> post(String path, String json, String cookie, String csrf)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        if (csrf != null) {
            request.header("X-CSRF-Token", csrf);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void seedDatabase() throws Exception {
        String hash = new Pbkdf2PasswordEncoder().encode("s3cret");
        String params = new Pbkdf2PasswordEncoder().defaultParams();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (String ddl : DefaultIdentityPack.schema("postgres").split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
            statement.execute("insert into tql_users "
                    + "(user_id, login_id, display_name, status, password_hash, password_algo,"
                    + " password_params) values ('u1','admin','Administrator','ACTIVE','" + hash
                    + "','pbkdf2','" + params + "')");
            statement.execute("insert into tql_roles (role_id, role_code, role_name) "
                    + "values ('r1','USER_READ','User Read')");
            statement.execute("insert into tql_user_roles (user_id, role_id) values ('u1','r1')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-token-acquisition-it");
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
        // Opt in: the endpoint does not exist until somebody decides it should.
        Path config = target.resolve("config/tesseraql.yml");
        Files.writeString(config, Files.readString(config).replace("""
                    jwt:
                """, """
                    token:
                      enabled: true
                      ttl: 15m
                    jwt:
                """));
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
