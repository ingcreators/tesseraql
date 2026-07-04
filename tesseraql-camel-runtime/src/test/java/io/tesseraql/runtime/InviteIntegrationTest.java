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
 * The invitation loop end to end (roadmap Phase 50 slice 2): the IAM admin invites, the
 * account exists as INVITED — refused at login — the accept link sets the first password
 * and flips it ACTIVE, the token works once, re-inviting resends politely, and a taken
 * login refuses.
 */
@Testcontainers
class InviteIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String adminCookie;
    static String adminCsrf;

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
        SessionStore sessions = runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal("iam-admin", "iam-admin", "IAM Admin",
                null, List.of(), List.of("ADMIN"), List.of(), Map.of()));
        adminCookie = sessions.cookieName() + "=" + sid;
        adminCsrf = sessions.session(sid).csrfToken();
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
    void theInviteLoopActivatesFromZeroAndTheLinkWorksOnce() throws Exception {
        assertThat(invite("new-hire", "New Hire", "new-hire@example.com")
                .headers().firstValue("Location").orElse("")).contains("invited=1");

        // INVITED cannot sign in - with any password, and without an oracle about why.
        assertThat(loginCookie("new-hire", "anythingAtAll1")).isNull();

        String acceptUrl = latestAcceptUrl("new-hire");
        assertThat(acceptUrl).contains("/_tesseraql/invite?token=");
        String token = URLDecoder.decode(acceptUrl.substring(acceptUrl.indexOf("token=") + 6),
                StandardCharsets.UTF_8);
        assertThat(get("/_tesseraql/invite?token=" + token).body())
                .contains("Accept your invitation");

        assertThat(postForm("/_tesseraql/invite", "token=" + token + "&next=Welcome123")
                .headers().firstValue("Location").orElse("")).contains("invited=1");
        // From zero to signed in, and the operator never knew the password.
        assertThat(loginCookie("new-hire", "Welcome123")).isNotNull();
        // The link is dead now.
        assertThat(postForm("/_tesseraql/invite", "token=" + token + "&next=SecondTry99")
                .headers().firstValue("Location").orElse("")).contains("invalid=1");
        assertThat(loginCookie("new-hire", "SecondTry99")).isNull();
    }

    /** Re-inviting a still-INVITED account is a polite resend, not an error. */
    @Test
    void reInvitingAnInvitedAccountAnswersOkWithoutASecondMailInsideTheCooldown()
            throws Exception {
        invite("slow-starter", "", "slow@example.com");
        long mails = inviteMailCount("slow-starter");
        assertThat(invite("slow-starter", "", "slow@example.com")
                .headers().firstValue("Location").orElse("")).contains("invited=1");
        assertThat(inviteMailCount("slow-starter")).isEqualTo(mails);
    }

    /** A login that is already usable refuses - no silent account takeover by invite. */
    @Test
    void anActiveLoginRefusesTheInvite() throws Exception {
        invite("becomes-active", "", "active@example.com");
        String token = URLDecoder.decode(latestAcceptUrl("becomes-active")
                .replaceAll(".*token=", ""), StandardCharsets.UTF_8);
        postForm("/_tesseraql/invite", "token=" + token + "&next=Activated1");

        assertThat(invite("becomes-active", "", "again@example.com").statusCode())
                .isEqualTo(409);
    }

    /** The invite action sits behind the iam.admin.write policy. */
    @Test
    void aSessionWithoutTheRoleIsRefused() throws Exception {
        SessionStore sessions = runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal("mortal", "mortal", "Mortal", null,
                List.of(), List.of(), List.of(), Map.of()));
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port()
                        + "/_tesseraql/admin/users/invite"))
                .header("Cookie", sessions.cookieName() + "=" + sid)
                .header("X-CSRF-Token", sessions.session(sid).csrfToken())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "loginId=x&email=x@example.com"))
                .build();
        assertThat(HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(403);
    }

    private static HttpResponse<String> invite(String loginId, String displayName,
            String email) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port()
                        + "/_tesseraql/admin/users/invite"))
                .header("Cookie", adminCookie)
                .header("X-CSRF-Token", adminCsrf)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "loginId=" + loginId + "&displayName=" + displayName
                                + "&email=" + email))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static long inviteMailCount(String loginId) {
        return runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.OUTBOX_STORE_BEAN,
                io.tesseraql.operations.outbox.JdbcOutboxStore.class)
                .recent(200).stream()
                .filter(NotifyEvents::isNotification)
                .map(event -> NotifyEvents.parse(event.payloadJson()))
                .filter(envelope -> envelope.source().equals("identity.invite")
                        && loginId.equals(envelope.payload().get("loginId")))
                .count();
    }

    private static String latestAcceptUrl(String loginId) {
        return runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.OUTBOX_STORE_BEAN,
                io.tesseraql.operations.outbox.JdbcOutboxStore.class)
                .recent(200).stream()
                .filter(NotifyEvents::isNotification)
                .map(event -> NotifyEvents.parse(event.payloadJson()))
                .filter(envelope -> envelope.source().equals("identity.invite")
                        && loginId.equals(envelope.payload().get("loginId")))
                .map(envelope -> String.valueOf(envelope.payload().get("acceptUrl")))
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
        Path target = Files.createTempDirectory("tesseraql-invite-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: invite-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  security:
                    policies:
                      iam.admin.view:
                        anyOf:
                          - role: ADMIN
                      iam.admin.write:
                        anyOf:
                          - role: ADMIN
                  notifications:
                    channels:
                      invite-mail:
                        type: mail
                        host: localhost
                        from: noreply@example.com
                        template: invite-mail.html
                  identity:
                    invite:
                      channel: invite-mail
                      url: http://localhost/_tesseraql/invite
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Files.createDirectories(target.resolve("templates"));
        Files.writeString(target.resolve("templates/invite-mail.html"),
                "<p th:text=\"${payload.acceptUrl}\">link</p>\n");
        return target;
    }
}
