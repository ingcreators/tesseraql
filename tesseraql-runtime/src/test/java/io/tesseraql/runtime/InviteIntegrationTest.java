package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import io.tesseraql.yaml.notify.NotifyEvents;
import java.io.IOException;
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
 * login refuses. And the operator's decision holds against the link they mailed
 * (docs/audit-low-leads.md slice 4, G39): withdrawing kills the token, the accept leg
 * activates only an account that is still INVITED, and a withdrawn login can be invited
 * again with a corrected address.
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
        runtime = TesseraqlRuntime.start(appHome, 0);
        javax.sql.DataSource main = runtime.context().lookup("main", javax.sql.DataSource.class);
        try (java.sql.Connection connection = main.getConnection();
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute(io.tesseraql.identity.DefaultIdentityPack.schema("postgres"));
        }
        SessionStore sessions = runtime.context().lookup(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal("iam-admin", "iam-admin", "IAM Admin",
                null, List.of(), List.of("ADMIN"),
                List.of("tql.iam.admin.view", "tql.iam.admin.write"), Map.of()),
                SessionStore.ClientInfo.NONE);
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

    /**
     * Withdrawing an invitation: the console offers it on the INVITED row (and neither
     * Enable nor Disable), the login can be invited again — to a corrected address, with a
     * fresh link that works — and the first link is dead. The re-invite comes first and is
     * what proves the token died with the withdrawal: a live one would still be inside its
     * cooldown and no second mail could go out, and once the account is INVITED again only
     * the token's absence keeps the first link from activating it. (Posting the first link
     * while the account is DISABLED would consume it and hide a missing revoke — that leg is
     * the next test's.)
     */
    @Test
    void aWithdrawnInvitationsLinkIsDeadAndTheLoginCanBeInvitedAgain() throws Exception {
        invite("regretted", "Regretted Hire", "wrong-address@example.com");
        String userId = userId("regretted");
        String firstToken = URLDecoder.decode(latestAcceptUrl("regretted")
                .replaceAll(".*token=", ""), StandardCharsets.UTF_8);

        String detail = adminGet("/_tesseraql/admin/users/" + userId).body();
        assertThat(detail).contains("/_tesseraql/admin/users/" + userId + "/withdraw")
                .doesNotContain("/_tesseraql/admin/users/" + userId + "/enable")
                .doesNotContain("/_tesseraql/admin/users/" + userId + "/disable");

        HttpResponse<String> withdrawn = adminPost(
                "/_tesseraql/admin/users/" + userId + "/withdraw");
        assertThat(withdrawn.statusCode()).as(withdrawn::body).isEqualTo(303);
        assertThat(withdrawn.headers().firstValue("Location").orElse(""))
                .endsWith("/_tesseraql/admin/users/" + userId + "?withdrawn=1");
        assertThat(adminGet("/_tesseraql/admin/users/" + userId + "?withdrawn=1").body())
                .contains("Invitation withdrawn").contains("DISABLED");
        assertThat(status("regretted")).isEqualTo("DISABLED");

        // Invited again, to the right address: back to INVITED, a second mail, a link that
        // works — and the first link is dead.
        long mails = inviteMailCount("regretted");
        assertThat(invite("regretted", "Regretted Hire", "right-address@example.com")
                .headers().firstValue("Location").orElse("")).contains("invited=1");
        assertThat(status("regretted")).isEqualTo("INVITED");
        assertThat(inviteMailCount("regretted")).isEqualTo(mails + 1);
        assertThat(latestInvite("regretted").payload().get("to"))
                .isEqualTo("right-address@example.com");
        String secondToken = URLDecoder.decode(latestAcceptUrl("regretted")
                .replaceAll(".*token=", ""), StandardCharsets.UTF_8);
        assertThat(secondToken).isNotEqualTo(firstToken);
        assertThat(postForm("/_tesseraql/invite", "token=" + firstToken + "&next=Sneaky123")
                .headers().firstValue("Location").orElse("")).contains("invalid=1");
        assertThat(status("regretted")).as("the withdrawn link activates nothing")
                .isEqualTo("INVITED");
        assertThat(loginCookie("regretted", "Sneaky123")).isNull();
        assertThat(postForm("/_tesseraql/invite", "token=" + secondToken + "&next=Welcome456")
                .headers().firstValue("Location").orElse("")).contains("invited=1");
        assertThat(loginCookie("regretted", "Welcome456")).isNotNull();
        // Once usable, the login refuses another invite - a withdrawn account was never
        // signed into; this one has been.
        assertThat(invite("regretted", "", "again@example.com").statusCode()).isEqualTo(409);
    }

    /**
     * The accept leg's own gate, apart from the token's death: an account that is no longer
     * INVITED — here disabled straight in the store, the token left live — is not activated
     * by its link. The dead-link answer, no password written, no sign-in.
     */
    @Test
    void anAcceptLinkCannotActivateAnAccountThatIsNoLongerInvited() throws Exception {
        invite("sidelined", "", "sidelined@example.com");
        String token = URLDecoder.decode(latestAcceptUrl("sidelined")
                .replaceAll(".*token=", ""), StandardCharsets.UTF_8);
        javax.sql.DataSource main = runtime.context().lookup("main", javax.sql.DataSource.class);
        try (java.sql.Connection connection = main.getConnection();
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute(
                    "update tql_users set status = 'DISABLED' where login_id = 'sidelined'");
        }

        assertThat(postForm("/_tesseraql/invite", "token=" + token + "&next=Welcome789")
                .headers().firstValue("Location").orElse("")).contains("invalid=1");
        assertThat(status("sidelined")).isEqualTo("DISABLED");
        assertThat(loginCookie("sidelined", "Welcome789")).isNull();
        try (java.sql.Connection connection = main.getConnection();
                java.sql.Statement statement = connection.createStatement();
                java.sql.ResultSet rs = statement.executeQuery(
                        "select password_hash from tql_users where login_id = 'sidelined'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).as("no password written for a dead link").isNull();
        }
    }

    /** The invite action sits behind the tql.iam.admin.write atom. */
    @Test
    void aSessionWithoutTheRoleIsRefused() throws Exception {
        SessionStore sessions = runtime.context().lookup(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal("mortal", "mortal", "Mortal", null,
                List.of(), List.of(), List.of(), Map.of()), SessionStore.ClientInfo.NONE);
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

    private static String userId(String loginId) throws Exception {
        return column(loginId, "user_id");
    }

    private static String status(String loginId) throws Exception {
        return column(loginId, "status");
    }

    private static String column(String loginId, String column) throws Exception {
        javax.sql.DataSource main = runtime.context().lookup("main", javax.sql.DataSource.class);
        try (java.sql.Connection connection = main.getConnection();
                java.sql.PreparedStatement ps = connection.prepareStatement(
                        "select " + column + " from tql_users where login_id = ?")) {
            ps.setString(1, loginId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("a tql_users row for " + loginId).isTrue();
                return rs.getString(1);
            }
        }
    }

    private static HttpResponse<String> adminGet(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Cookie", adminCookie)
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> adminPost(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Cookie", adminCookie)
                .header("X-CSRF-Token", adminCsrf)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static NotifyEvents.Envelope latestInvite(String loginId) {
        return runtime.context().lookup(
                TesseraqlProperties.OUTBOX_STORE_BEAN,
                io.tesseraql.operations.outbox.JdbcOutboxStore.class)
                .recent(200).stream()
                .filter(NotifyEvents::isNotification)
                .map(event -> NotifyEvents.parse(event.payloadJson()))
                .filter(envelope -> envelope.source().equals("identity.invite")
                        && loginId.equals(envelope.payload().get("loginId")))
                .findFirst().orElseThrow();
    }

    private static long inviteMailCount(String loginId) {
        return runtime.context().lookup(
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
        return runtime.context().lookup(
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
                    # This fixture fails credentials on purpose; the throttle under test
                    # elsewhere is disabled visibly (docs/credential-throttle.md).
                    credentialThrottle:
                      enabled: false
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
