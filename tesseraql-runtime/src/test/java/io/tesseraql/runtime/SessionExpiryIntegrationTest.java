package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The kit's session-expiry recipe end to end (docs/hypermedia-ui.md "Session expiry"): an
 * htmx request whose session is gone answers 401 with the re-login dialog retargeted at the
 * shell's shared host; the dialog's own login post answers the recipe's three shapes (200 +
 * {@code hc:sessionrenewed} carrying the fresh CSRF token, 422 re-rendering the dialog on bad
 * credentials, 429 on a throttled attempt is covered by the throttle suite); and the classic
 * full-page redirect stays what it was for non-htmx navigation.
 */
@Testcontainers
class SessionExpiryIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
        javax.sql.DataSource mainDataSource = runtime.context().lookup("main",
                javax.sql.DataSource.class);
        try (java.sql.Connection connection = mainDataSource.getConnection();
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute(io.tesseraql.identity.DefaultIdentityPack.schema("postgres"));
        }
        io.tesseraql.identity.IdentityService identity = new io.tesseraql.identity.IdentityService(
                name -> mainDataSource);
        io.tesseraql.security.password.Pbkdf2PasswordEncoder encoder = new io.tesseraql.security.password.Pbkdf2PasswordEncoder();
        identity.executeUpdate(io.tesseraql.identity.RealmConfig.managed("bootstrap", "main"),
                io.tesseraql.identity.IdentityContracts.SEED_ADMIN_USER, Map.of(
                        "userId", "expiry-user",
                        "loginId", "expiry-user",
                        "displayName", "Expiry User",
                        "passwordHash", encoder.encode("FirstPass1"),
                        "passwordParams", encoder.defaultParams()));
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
    void anHtmxRequestWithoutASessionGetsTheReLoginDialog() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                uri("/_tesseraql/account"))
                .header("HX-Request", "true")
                .header("Accept", "text/html").build());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("HX-Retarget"))
                .hasValue("[data-hc-session-expiry]");
        assertThat(response.headers().firstValue("HX-Reswap")).hasValue("innerHTML");
        assertThat(response.body())
                .contains("data-tql-session-expired")
                .contains("hc-dialog")
                .contains("Session expired")
                .contains("/_tesseraql/login")
                .contains("name=\"otp\"")
                // Cancel is a declarative <form method="dialog"> close, per the recipe.
                .contains("<form method=\"dialog\">");
    }

    @Test
    void theDialogLoginPostAnswersTheRecipeShapes() throws Exception {
        // Wrong password: 422 re-renders the dialog with the error inline, retargeted at the
        // same host — never the login page's 303 bounce, which would navigate the page whose
        // work the dialog preserves.
        HttpResponse<String> invalid = send(htmxLogin("expiry-user", "WrongPass1"));
        assertThat(invalid.statusCode()).isEqualTo(422);
        assertThat(invalid.headers().firstValue("HX-Retarget"))
                .hasValue("[data-hc-session-expiry]");
        assertThat(invalid.body())
                .contains("data-tql-session-expired")
                .contains("data-hc-field-errors")
                .contains("Invalid credentials");

        // The right password: 200, a session cookie, no body — only the hc:sessionrenewed
        // trigger, whose payload hands the bootstrap the fresh session's CSRF token so the
        // kit's replay does not fail on the page's stale meta.
        HttpResponse<String> renewed = send(htmxLogin("expiry-user", "FirstPass1"));
        assertThat(renewed.statusCode()).isEqualTo(200);
        assertThat(renewed.headers().firstValue("Set-Cookie")).isPresent();
        String trigger = renewed.headers().firstValue("HX-Trigger").orElseThrow();
        assertThat(trigger).contains("hc:sessionrenewed").contains("csrfToken");
        assertThat(renewed.body()).isEmpty();

        // The replayed request is an ordinary authenticated htmx request: it succeeds, and
        // the page it lands on carries the armed host for the next expiry.
        String cookie = renewed.headers().firstValue("Set-Cookie").orElseThrow()
                .split(";")[0];
        HttpResponse<String> replay = send(HttpRequest.newBuilder(
                uri("/_tesseraql/account"))
                .header("Cookie", cookie)
                .header("Accept", "text/html").build());
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).contains("data-hc-session-expiry");
    }

    @Test
    void aFullPageNavigationKeepsTheClassicLoginRedirect() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                uri("/_tesseraql/account"))
                .header("Accept", "text/html").build());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElseThrow())
                .startsWith("/_tesseraql/login?redirect=");
    }

    private static HttpRequest htmxLogin(String loginId, String password) {
        return HttpRequest.newBuilder(uri("/_tesseraql/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("HX-Request", "true")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "loginId=" + loginId + "&password=" + password))
                .build();
    }

    private static URI uri(String path) {
        return URI.create("http://localhost:" + runtime.port() + path);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-session-expiry-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  # This fixture fails credentials on purpose; the throttle's own dialog
                  # shape is unit-scoped, and leaving it on would flake the 422 leg.
                  security:
                    credentialThrottle:
                      enabled: false
                  app:
                    name: session-expiry-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        return target;
    }
}
