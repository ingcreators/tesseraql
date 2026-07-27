package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
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
 * The credential throttle end to end (docs/credential-throttle.md), with tiny budgets:
 * failed logins trip the per-login budget before verification (the right password is
 * refused too — proof the check precedes hashing), the browser bounces to the rate
 * message while the API caller gets 429 + Retry-After + TQL-RATE-4292, windows expire on
 * their own, other login ids are untouched, and a throttled reset keeps its neutral
 * answer.
 */
@Testcontainers
class CredentialThrottleIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        javax.sql.DataSource main = runtime.camelContext().getRegistry()
                .lookupByNameAndType("main", javax.sql.DataSource.class);
        try (java.sql.Connection connection = main.getConnection();
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute(io.tesseraql.identity.DefaultIdentityPack.schema("postgres"));
        }
        io.tesseraql.identity.IdentityService identity = new io.tesseraql.identity.IdentityService(
                name -> main);
        io.tesseraql.security.password.Pbkdf2PasswordEncoder encoder = new io.tesseraql.security.password.Pbkdf2PasswordEncoder();
        identity.executeUpdate(io.tesseraql.identity.RealmConfig.managed("bootstrap", "main"),
                io.tesseraql.identity.IdentityContracts.SEED_ADMIN_USER, Map.of(
                        "userId", "throttled-user",
                        "loginId", "throttled-user",
                        "displayName", "Throttled User",
                        "passwordHash", encoder.encode("RightPass1"),
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
    void failedLoginsThrottleBeforeVerificationAndTheWindowReadmits() throws Exception {
        // Two failures reach the budget (loginAttempts: 2).
        assertThat(login("throttled-user", "wrong-1", true).statusCode()).isEqualTo(303);
        assertThat(login("throttled-user", "wrong-2", true).statusCode()).isEqualTo(303);

        // The browser form bounces to the rate message, not the invalid-credentials one.
        HttpResponse<String> browser = login("throttled-user", "wrong-3", true);
        assertThat(browser.statusCode()).isEqualTo(303);
        assertThat(browser.headers().firstValue("location").orElse(""))
                .contains("error=rate");

        // The RIGHT password is refused too: the check precedes verification.
        HttpResponse<String> right = login("throttled-user", "RightPass1", false);
        assertThat(right.statusCode()).isEqualTo(429);
        assertThat(right.headers().firstValue("Retry-After")).isPresent();
        assertThat(right.body()).contains("TQL-RATE-4292");

        // Another login id is untouched by this budget.
        assertThat(login("someone-else", "whatever", false).statusCode()).isEqualTo(401);

        // Windows expire on their own - there is no lockout to lift.
        Thread.sleep(2_500);
        HttpResponse<String> readmitted = login("throttled-user", "RightPass1", false);
        assertThat(readmitted.statusCode()).isEqualTo(200);
        assertThat(readmitted.headers().firstValue("Set-Cookie")).isPresent();
    }

    @Test
    void aThrottledResetKeepsItsNeutralAnswer() throws Exception {
        // Every reset request counts; past the budget the answer must not change - a 429
        // here would itself be an oracle. (loginAttempts: 2 keys reset budgets too.)
        int first = resetRequest("reset-target").statusCode();
        for (int i = 0; i < 4; i++) {
            assertThat(resetRequest("reset-target").statusCode())
                    .as("the neutral answer never changes, throttled or not")
                    .isEqualTo(first);
        }
    }

    private static HttpResponse<String> login(String loginId, String password,
            boolean browserForm) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/_tesseraql/login"))
                .header("Content-Type", browserForm
                        ? "application/x-www-form-urlencoded"
                        : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(browserForm
                        ? "loginId=" + loginId + "&password=" + password
                        : "{\"loginId\":\"" + loginId + "\",\"password\":\"" + password
                                + "\"}"));
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> resetRequest(String loginId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/_tesseraql/reset"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("loginId=" + loginId))
                .build();
        return HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-throttle-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  security:
                    credentialThrottle:
                      loginAttempts: 2
                      loginWindow: 2s
                      addressAttempts: 100
                      addressWindow: 2s
                  notifications:
                    channels:
                      user-mail:
                        type: mail
                        host: localhost
                        from: noreply@example.com
                        template: reset-mail.html
                  identity:
                    recovery:
                      enabled: true
                      channel: user-mail
                      url: http://localhost/_tesseraql/reset/confirm
                  app:
                    name: throttle-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Files.createDirectories(target.resolve("templates"));
        Files.writeString(target.resolve("templates/reset-mail.html"),
                "<p th:text=\"${payload.resetUrl}\">link</p>\n");
        return target;
    }
}
