package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import io.tesseraql.yaml.notify.NotifyEvents;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The password-reset loop end to end (roadmap Phase 50 slice 1): the neutral request leg
 * issues a hashed one-time token and mails the link over the outbox; the confirm leg
 * consumes it exactly once, rotates the credential through the login path's own contract,
 * and kills every session of the subject; and none of the failure shapes leak whether an
 * account exists.
 */
@Testcontainers
class RecoveryIntegrationTest {

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
                        "userId", "reset-user",
                        "loginId", "reset-user",
                        "displayName", "Reset User",
                        "passwordHash", encoder.encode("beforeReset1"),
                        "passwordParams", encoder.defaultParams()));
        try (java.sql.Connection connection = main.getConnection();
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute(
                    "update tql_users set email = 'reset@example.com' "
                            + "where login_id = 'reset-user'");
        }
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
    void theFullLoopRotatesOnceKillsSessionsAndTheTokenNeverWorksTwice() throws Exception {
        // A live session that must die with the reset.
        SessionStore sessions = runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal(
                "reset-user", "reset-user", "Reset User", null, List.of(),
                List.of(), List.of(), Map.of()));

        assertThat(postForm("/_tesseraql/reset", "loginId=reset-user")
                .headers().firstValue("Location").orElse("")).contains("sent=1");

        String resetUrl = latestResetUrl();
        assertThat(resetUrl).contains("/_tesseraql/reset/confirm?token=");
        String token = URLDecoder.decode(resetUrl.substring(resetUrl.indexOf("token=") + 6),
                StandardCharsets.UTF_8);

        // Confirm rotates and lands on the login page with the success flag...
        HttpResponse<String> confirmed = postForm("/_tesseraql/reset/confirm",
                "token=" + token + "&next=afterReset2");
        assertThat(confirmed.headers().firstValue("Location").orElse(""))
                .contains("login?reset=1");
        // ...the old credential and every session are dead, the new credential lives...
        assertThat(sessions.session(sid)).isNull();
        assertThat(loginCookie("reset-user", "beforeReset1")).isNull();
        assertThat(loginCookie("reset-user", "afterReset2")).isNotNull();
        // ...and the same link never works twice.
        assertThat(postForm("/_tesseraql/reset/confirm",
                "token=" + token + "&next=thirdTry333")
                .headers().firstValue("Location").orElse("")).contains("invalid=1");
        assertThat(loginCookie("reset-user", "thirdTry333")).isNull();
    }

    /** No enumeration oracle: an unknown login answers identically and mails nothing. */
    @Test
    void anUnknownLoginAnswersNeutrallyAndMailsNothing() throws Exception {
        long before = resetMailCount();
        HttpResponse<String> response = postForm("/_tesseraql/reset", "loginId=nobody-here");
        assertThat(response.headers().firstValue("Location").orElse("")).contains("sent=1");
        assertThat(resetMailCount()).isEqualTo(before);
    }

    /** A too-short password bounces back with the token intact - still consumable. */
    @Test
    void aShortPasswordBouncesWithoutConsumingTheToken() throws Exception {
        postForm("/_tesseraql/reset", "loginId=reset-user");
        String token = URLDecoder.decode(
                latestResetUrl().substring(latestResetUrl().indexOf("token=") + 6),
                StandardCharsets.UTF_8);
        assertThat(postForm("/_tesseraql/reset/confirm", "token=" + token + "&next=tiny")
                .headers().firstValue("Location").orElse("")).contains("error=short");
        // The bounce did not consume it: the full-length retry succeeds.
        assertThat(postForm("/_tesseraql/reset/confirm",
                "token=" + token + "&next=longEnough99")
                .headers().firstValue("Location").orElse("")).contains("reset=1");
        // Restore the canonical password for test independence.
        postForm("/_tesseraql/reset", "loginId=reset-user");
        String restore = URLDecoder.decode(
                latestResetUrl().substring(latestResetUrl().indexOf("token=") + 6),
                StandardCharsets.UTF_8);
        postForm("/_tesseraql/reset/confirm", "token=" + restore + "&next=beforeReset1");
    }

    /** The public pages render: the form, the neutral sent box, the dead-link state. */
    @Test
    void thePagesRenderTheirStates() throws Exception {
        assertThat(get("/_tesseraql/reset").body()).contains("Send reset link");
        assertThat(get("/_tesseraql/reset?sent=1").body()).contains("on its way");
        assertThat(get("/_tesseraql/reset/confirm?invalid=1").body())
                .contains("no longer valid");
        assertThat(get("/_tesseraql/login").body()).contains("Forgot password?");
    }

    private static long resetMailCount() {
        return runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.OUTBOX_STORE_BEAN,
                io.tesseraql.operations.outbox.JdbcOutboxStore.class)
                .recent(200).stream()
                .filter(NotifyEvents::isNotification)
                .filter(event -> NotifyEvents.parse(event.payloadJson()).source()
                        .equals("identity.reset"))
                .count();
    }

    private static String latestResetUrl() {
        return runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.OUTBOX_STORE_BEAN,
                io.tesseraql.operations.outbox.JdbcOutboxStore.class)
                .recent(200).stream()
                .filter(NotifyEvents::isNotification)
                .map(event -> NotifyEvents.parse(event.payloadJson()))
                .filter(envelope -> envelope.source().equals("identity.reset"))
                .map(envelope -> String.valueOf(envelope.payload().get("resetUrl")))
                .findFirst().orElseThrow();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + runtime.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postForm(String path, String form) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String loginCookie(String loginId, String password) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/_tesseraql/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"loginId\":\"" + loginId + "\",\"password\":\"" + password
                                + "\"}"))
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
        Path target = Files.createTempDirectory("tesseraql-recovery-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: recovery-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
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
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Files.createDirectories(target.resolve("templates"));
        Files.writeString(target.resolve("templates/reset-mail.html"),
                "<p th:text=\"${payload.resetUrl}\">link</p>\n");
        return target;
    }
}
