package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.credential.TotpStore;
import io.tesseraql.security.session.SessionStore;
import io.tesseraql.security.totp.Totp;
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
 * The TOTP second factor end to end (roadmap Phase 50 slice 3): enrollment confirms
 * before anything enforces, a confirmed enrollment makes the login code required (missing,
 * wrong, and REPLAYED codes all answer exactly like a wrong password), the replay guard is
 * the store's compare-and-set, and disabling re-verifies the password.
 */
@Testcontainers
class TotpIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static javax.sql.DataSource mainDataSource;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        mainDataSource = runtime.camelContext().getRegistry()
                .lookupByNameAndType("main", javax.sql.DataSource.class);
        try (java.sql.Connection connection = mainDataSource.getConnection();
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute(io.tesseraql.identity.DefaultIdentityPack.schema("postgres"));
        }
        io.tesseraql.identity.IdentityService identity = new io.tesseraql.identity.IdentityService(
                name -> mainDataSource);
        io.tesseraql.security.password.Pbkdf2PasswordEncoder encoder = new io.tesseraql.security.password.Pbkdf2PasswordEncoder();
        identity.executeUpdate(io.tesseraql.identity.RealmConfig.managed("bootstrap", "main"),
                io.tesseraql.identity.IdentityContracts.SEED_ADMIN_USER, Map.of(
                        "userId", "totp-user",
                        "loginId", "totp-user",
                        "displayName", "Totp User",
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
    void theSecondFactorLifecycleEnforcesAndRefusesReplays() throws Exception {
        // Before enrollment: password alone signs in, the card offers setup.
        String cookie = loginCookie("totp-user", "FirstPass1", null);
        assertThat(cookie).isNotNull();
        String csrf = csrfFor(cookie);
        assertThat(get(cookie, "/_tesseraql/account").body())
                .contains("Set up two-factor authentication");

        // Begin: the pending secret renders to its owner; nothing enforces yet.
        assertThat(postForm(cookie, csrf, "/_tesseraql/account/totp/begin", "")
                .statusCode()).isEqualTo(303);
        TotpStore store = runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.TOTP_STORE_BEAN, TotpStore.class);
        String secret = store.enrollment(null, "totp-user").orElseThrow().secret();
        String pendingPage = get(cookie, "/_tesseraql/account").body();
        // The pending enrollment renders the QR, the manual secret, and the recovery codes
        // (docs/credential-lifecycle.md) — all to the owner only, none enforced yet.
        assertThat(pendingPage).contains(secret).contains("otpauth://totp/")
                .contains("TOTP enrollment QR code").contains("Recovery codes");
        String recoveryCode = store.pendingRecovery(null, "totp-user").orElseThrow()
                .split(" ")[0];
        assertThat(pendingPage).contains(recoveryCode);
        assertThat(loginCookie("totp-user", "FirstPass1", null)).isNotNull();

        // A wrong confirm code is refused and still nothing enforces.
        assertThat(postForm(cookie, csrf, "/_tesseraql/account/totp/confirm",
                "code=000000").statusCode()).isEqualTo(400);
        assertThat(loginCookie("totp-user", "FirstPass1", null)).isNotNull();

        // Confirm with the real code: from here the login requires it.
        long confirmStep = Totp.currentStep();
        assertThat(postForm(cookie, csrf, "/_tesseraql/account/totp/confirm",
                "code=" + Totp.codeAt(secret, confirmStep)).statusCode()).isEqualTo(303);
        // Confirmation activates the recovery codes (hashed at rest) and drops the plain
        // pending copy: the page never shows them again.
        assertThat(get(cookie, "/_tesseraql/account").body())
                .contains("Disable two-factor").doesNotContain("Recovery codes");
        assertThat(store.pendingRecovery(null, "totp-user")).isEmpty();

        // Missing and wrong codes fail exactly like a wrong password.
        assertThat(loginCookie("totp-user", "FirstPass1", null)).isNull();
        assertThat(loginCookie("totp-user", "FirstPass1", "000000")).isNull();

        // The next step's code (inside the +1 window) signs in - once.
        long loginStep = confirmStep + 1;
        String code = Totp.codeAt(secret, loginStep);
        assertThat(loginCookie("totp-user", "FirstPass1", code)).isNotNull();
        assertThat(loginCookie("totp-user", "FirstPass1", code)).isNull();
        // Lowering the compare-and-set floor lets the very same code pass again: the
        // refusal above was exactly the replay guard, not the clock.
        try (java.sql.Connection connection = mainDataSource.getConnection();
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute("update tql_user_totp set last_used_step = "
                    + (loginStep - 1) + " where subject = 'totp-user'");
        }
        assertThat(loginCookie("totp-user", "FirstPass1", code)).isNotNull();

        // A recovery code signs in once when the authenticator is lost — and only once:
        // consuming the hash is the single-use guarantee.
        assertThat(loginCookie("totp-user", "FirstPass1", recoveryCode)).isNotNull();
        assertThat(loginCookie("totp-user", "FirstPass1", recoveryCode)).isNull();

        // Disabling needs the password; wrong one changes nothing.
        assertThat(postForm(cookie, csrf, "/_tesseraql/account/totp/disable",
                "current=not-the-password").statusCode()).isEqualTo(400);
        assertThat(loginCookie("totp-user", "FirstPass1", null)).isNull();
        assertThat(postForm(cookie, csrf, "/_tesseraql/account/totp/disable",
                "current=FirstPass1").statusCode()).isEqualTo(303);
        assertThat(loginCookie("totp-user", "FirstPass1", null)).isNotNull();
    }

    private static String csrfFor(String cookie) {
        SessionStore sessions = runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        return sessions.csrfTokenFromCookie(cookie);
    }

    private static HttpResponse<String> get(String cookie, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + runtime.port() + path))
                        .header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postForm(String cookie, String csrf, String path,
            String form) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Cookie", cookie)
                .header("X-CSRF-Token", csrf)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String loginCookie(String loginId, String password, String otp)
            throws Exception {
        String body = otp == null
                ? "{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}"
                : "{\"loginId\":\"" + loginId + "\",\"password\":\"" + password
                        + "\",\"otp\":\"" + otp + "\"}";
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/_tesseraql/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        return response.headers().firstValue("Set-Cookie").map(c -> c.split(";")[0])
                .orElse(null);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-totp-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: totp-it
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
