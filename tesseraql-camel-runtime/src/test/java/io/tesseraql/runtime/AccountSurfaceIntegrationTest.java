package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.account.PreferenceStore;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * The account surface end to end (roadmap Phase 48 slice 1): the bundled
 * {@code /_tesseraql/account} app renders the session principal's profile, the shared shell
 * grows the avatar + user menu for any signed-in page, the managed preference store is live,
 * and turning the surface off removes both the route and the settings link.
 */
@Testcontainers
class AccountSurfaceIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String sessionCookie;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome(true);
        runtime = TesseraqlRuntime.start(appHome, freePort());
        sessionCookie = establishSession(runtime);
        // A local-realm user for the password-change loop (slice 4): the standard identity
        // schema plus one seeded account, through the same pack contracts the CLI's
        // identity-schema command runs, on the runtime's own datasource.
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
                        "userId", "pw-user",
                        "loginId", "pw-user",
                        "displayName", "pw-user",
                        "passwordHash", encoder.encode("originalPass1"),
                        "passwordParams", encoder.defaultParams()));
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
    void theProfilePageRendersTheSessionPrincipalAndTheUserMenu() throws Exception {
        HttpResponse<String> page = get(runtime, sessionCookie, "/_tesseraql/account");

        assertThat(page.statusCode()).isEqualTo(200);
        // The profile facts come from the session principal, never from request input.
        assertThat(page.body()).contains("Account User")
                .contains("account-user")
                .contains("ADMIN");
        // The shared shell renders the avatar + user menu from the reserved `_account`
        // variable: initials, the settings link (the app is mounted), and sign-out.
        // No inbox channel is declared in this app, so the shell renders no bell.
        assertThat(page.body()).doesNotContain("tql-inbox-bell");
        assertThat(page.body()).contains("hc-avatar")
                .contains("tql-account-menu")
                .contains("Account settings")
                .contains("Sign out")
                .contains("/_tesseraql/logout");
    }

    @Test
    void anonymousRequestsAreRefused() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/_tesseraql/account"))
                .build();
        HttpResponse<String> page = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(401);
    }

    @Test
    void thePreferenceStoreIsLiveAndPersists() {
        PreferenceStore store = runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.PREFERENCE_STORE_BEAN, PreferenceStore.class);
        assertThat(store).isNotNull();

        store.put(null, "account-user", "ui.theme", "dark");
        // The write invalidated the cache, so this read proves the tql_user_preference row.
        assertThat(store.preferences(null, "account-user"))
                .containsEntry("ui.theme", "dark");
        store.remove(null, "account-user", "ui.theme");
        assertThat(store.preferences(null, "account-user")).isEmpty();
    }

    /** Slice 2: the saved language flows through the Phase 22 locale chain on the next page. */
    @Test
    void savingALanguageSwitchesRenderingEverywhere() throws Exception {
        try {
            HttpResponse<String> saved = postForm(runtime, sessionCookie,
                    "/_tesseraql/account/language", "locale=ja");
            assertThat(saved.statusCode()).isEqualTo(303);

            // Any shell page now renders in the stored locale (the html lang attribute),
            // even though the request carries no Accept-Language.
            HttpResponse<String> home = get(runtime, sessionCookie, "/home");
            assertThat(home.body()).contains("lang=\"ja\"");
            // And the account page shows the choice as selected.
            // (the option tag wraps across source lines, so match the selected suffix)
            assertThat(get(runtime, sessionCookie, "/_tesseraql/account").body())
                    .contains("selected=\"selected\">\u65e5\u672c\u8a9e");
        } finally {
            preferenceStore().remove(null, "account-user", "ui.locale");
        }
    }

    /** Slice 2: a language the app does not serve is refused (TQL-ACCOUNT-4802). */
    @Test
    void anUnsupportedLanguageIsRefused() throws Exception {
        assertThat(postForm(runtime, sessionCookie, "/_tesseraql/account/language",
                "locale=xx").statusCode()).isEqualTo(400);
        assertThat(preferenceStore().preferences(null, "account-user"))
                .doesNotContainKey("ui.locale");
    }

    /** Slice 2: the saved theme re-skins the shell and re-syncs the pre-login cookie. */
    @Test
    void savingAThemeReskinsTheShellAndSyncsTheCookie() throws Exception {
        try {
            assertThat(postForm(runtime, sessionCookie, "/_tesseraql/account/appearance",
                    "theme=light").statusCode()).isEqualTo(303);

            HttpResponse<String> home = get(runtime, sessionCookie, "/home");
            assertThat(home.body()).contains("data-theme=\"light\"");
            // The stored choice differs from the (absent) cookie, so the response syncs it —
            // which is what carries the theme onto pre-login pages.
            assertThat(home.headers().firstValue("Set-Cookie").orElse(""))
                    .contains("tesseraql_theme=light");
            HttpResponse<String> login = get(runtime, "tesseraql_theme=light",
                    "/_tesseraql/login");
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(login.body()).contains("data-theme=\"light\"");
        } finally {
            preferenceStore().remove(null, "account-user", "ui.theme");
        }
    }

    /** Slice 2: the theme input is an enum — anything else stops at the route, 400. */
    @Test
    void anUnknownThemeIsRefusedByTheInputConstraint() throws Exception {
        assertThat(postForm(runtime, sessionCookie, "/_tesseraql/account/appearance",
                "theme=hotdog").statusCode()).isEqualTo(400);
    }

    /** hc 0.1.9 adoption: the signed-in shell offers the kit's light/dark toggle. */
    @Test
    void theShellRendersTheThemeToggleWhenTheAccountAppIsMounted() throws Exception {
        HttpResponse<String> home = get(runtime, sessionCookie, "/home");
        assertThat(home.statusCode()).isEqualTo(200);
        // The toggle carries no data-persist: the bootstrap mirrors hc:themechange to the
        // appearance route (the same header-authenticated POST shape postForm() proves all
        // over this suite), so the stored preference - not localStorage - is what lasts.
        assertThat(home.body()).contains("data-hc-theme-toggle")
                .contains("icons.svg#sun-moon")
                .doesNotContain("data-persist=\"hc-theme\"");
    }

    /** Slice 3: the account page offers the user-facing channel and persists the choice. */
    @Test
    void theNotificationOptOutTogglePersists() throws Exception {
        try {
            assertThat(get(runtime, sessionCookie, "/_tesseraql/account").body())
                    .contains("Opt out of")
                    .contains("user-mail");
            assertThat(postForm(runtime, sessionCookie, "/_tesseraql/account/notifications",
                    "channel=user-mail&optOut=true").statusCode()).isEqualTo(303);
            assertThat(preferenceStore().preferences(null, "account-user"))
                    .containsEntry("notify.user-mail.optOut", "true");
            // Unchecking posts no optOut field - that reads as opting back in.
            assertThat(postForm(runtime, sessionCookie, "/_tesseraql/account/notifications",
                    "channel=user-mail").statusCode()).isEqualTo(303);
            assertThat(preferenceStore().preferences(null, "account-user"))
                    .doesNotContainKey("notify.user-mail.optOut");
        } finally {
            preferenceStore().remove(null, "account-user", "notify.user-mail.optOut");
        }
    }

    /** Slice 3: a channel without userOptOut is not writable through the account surface. */
    @Test
    void anUnmarkedChannelRefusesTheOptOut() throws Exception {
        assertThat(postForm(runtime, sessionCookie, "/_tesseraql/account/notifications",
                "channel=ops-alerts&optOut=true").statusCode()).isEqualTo(400);
    }

    /** Slice 3: a recipient-naming notification skips enqueue for an opted-out subject. */
    @Test
    void aRecipientNotificationHonorsTheOptOutAtEnqueue() throws Exception {
        io.tesseraql.operations.outbox.JdbcOutboxStore outbox = runtime.camelContext()
                .getRegistry().lookupByNameAndType(TesseraqlProperties.OUTBOX_STORE_BEAN,
                        io.tesseraql.operations.outbox.JdbcOutboxStore.class);
        try {
            preferenceStore().put(null, "account-user", "notify.user-mail.optOut", "true");
            HttpResponse<String> optedOut = postForm(runtime, sessionCookie, "/notify-me", "");
            assertThat(optedOut.statusCode()).isEqualTo(200);
            // The command reports the decision and no outbox row exists for it.
            assertThat(optedOut.body()).contains("optedOut");
            long before = outbox.recent(100).stream()
                    .filter(io.tesseraql.yaml.notify.NotifyEvents::isNotification).count();

            preferenceStore().remove(null, "account-user", "notify.user-mail.optOut");
            HttpResponse<String> delivered = postForm(runtime, sessionCookie, "/notify-me", "");
            assertThat(delivered.statusCode()).isEqualTo(200);
            assertThat(delivered.body()).contains("eventId");
            long after = outbox.recent(100).stream()
                    .filter(io.tesseraql.yaml.notify.NotifyEvents::isNotification).count();
            assertThat(after).isEqualTo(before + 1);
        } finally {
            preferenceStore().remove(null, "account-user", "notify.user-mail.optOut");
        }
    }

    /** Slice 4: the session list counts this device and sign-out-others keeps only it. */
    @Test
    void theSessionListCountsAndSignOutOthersKeepsOnlyThisSession() throws Exception {
        SessionStore sessions = runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String otherSid = sessions.create(new Principal(
                "account-user", "account-user", "Account User", null, List.of(),
                List.of("ADMIN"), List.of(), Map.of()));
        try {
            assertThat(get(runtime, sessionCookie, "/_tesseraql/account").body())
                    .contains("2 active")
                    .contains("Sign out other sessions");

            String csrf = csrfFor(runtime, sessionCookie);
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + runtime.port()
                            + "/_tesseraql/logout-others"))
                    .header("Cookie", sessionCookie)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + csrf))
                    .build();
            HttpResponse<String> signedOut = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(signedOut.statusCode()).isEqualTo(303);

            // The other session is gone, this one survives, and the page agrees.
            assertThat(sessions.session(otherSid)).isNull();
            HttpResponse<String> after = get(runtime, sessionCookie, "/_tesseraql/account");
            assertThat(after.statusCode()).isEqualTo(200);
            assertThat(after.body()).contains("1 active");
        } finally {
            sessions.invalidate(otherSid);
        }
    }

    /** Slice 4: the missing CSRF token refuses the sign-out-others post. */
    @Test
    void signOutOthersWithoutTheCsrfTokenIsRefused() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/_tesseraql/logout-others"))
                .header("Cookie", sessionCookie)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build();
        assertThat(HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isIn(401, 403);
    }

    /** Slice 4: the full loop - old password in, changed, old refused, new accepted. */
    @Test
    void passwordChangeRotatesTheLocalCredential() throws Exception {
        String cookie = loginCookie("pw-user", "originalPass1");
        assertThat(cookie).isNotNull();
        try {
            HttpResponse<String> changed = postForm(runtime, cookie,
                    "/_tesseraql/account/password",
                    "current=originalPass1&next=rotatedPass2");
            assertThat(changed.statusCode()).isEqualTo(303);

            assertThat(loginCookie("pw-user", "originalPass1")).isNull();
            String renewed = loginCookie("pw-user", "rotatedPass2");
            assertThat(renewed).isNotNull();
        } finally {
            // Restore for test independence (order is not guaranteed).
            String cookieNow = loginCookie("pw-user", "rotatedPass2");
            if (cookieNow != null) {
                postForm(runtime, cookieNow, "/_tesseraql/account/password",
                        "current=rotatedPass2&next=originalPass1");
            }
        }
    }

    /** Slice 4: a wrong current password is refused (TQL-ACCOUNT-4804) and nothing rotates. */
    @Test
    void aWrongCurrentPasswordIsRefused() throws Exception {
        String cookie = loginCookie("pw-user", "originalPass1");
        assertThat(cookie).isNotNull();
        assertThat(postForm(runtime, cookie, "/_tesseraql/account/password",
                "current=not-the-password&next=whateverPass9").statusCode()).isEqualTo(400);
        assertThat(loginCookie("pw-user", "originalPass1")).isNotNull();
    }

    /** JSON login; returns the session cookie, or null when the credentials are refused. */
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
        String setCookie = response.headers().firstValue("Set-Cookie").orElse(null);
        return setCookie == null ? null : setCookie.split(";")[0];
    }

    /** Slice 5: declared preferences render, save within bounds, and reject the rest. */
    @Test
    void declaredAppPreferencesRenderAndSaveWithinTheirDeclaration() throws Exception {
        try {
            HttpResponse<String> page = get(runtime, sessionCookie, "/_tesseraql/account");
            assertThat(page.body()).contains("App settings").contains("pageSize");

            assertThat(postForm(runtime, sessionCookie, "/_tesseraql/account/app",
                    "key=pageSize&value=50").statusCode()).isEqualTo(303);
            assertThat(preferenceStore().preferences(null, "account-user"))
                    .containsEntry("app.pageSize", "50");
            // Out-of-declaration writes: a value outside the options, an undeclared key.
            assertThat(postForm(runtime, sessionCookie, "/_tesseraql/account/app",
                    "key=pageSize&value=999").statusCode()).isEqualTo(400);
            assertThat(postForm(runtime, sessionCookie, "/_tesseraql/account/app",
                    "key=hacks&value=1").statusCode()).isEqualTo(400);
        } finally {
            preferenceStore().remove(null, "account-user", "app.pageSize");
        }
    }

    /** Slice 5: routes read the declaration-bounded preference.<key> namespace back. */
    @Test
    void aRouteReadsThePreferenceNamespaceWithDefaultThenChoice() throws Exception {
        try {
            // Never chosen: the declared default binds into the SQL.
            assertThat(get(runtime, sessionCookie, "/page-size").body())
                    .contains("\"size\":\"25\"");
            postForm(runtime, sessionCookie, "/_tesseraql/account/app",
                    "key=pageSize&value=50");
            assertThat(get(runtime, sessionCookie, "/page-size").body())
                    .contains("\"size\":\"50\"");
        } finally {
            preferenceStore().remove(null, "account-user", "app.pageSize");
        }
    }

    private static PreferenceStore preferenceStore() {
        return runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.PREFERENCE_STORE_BEAN, PreferenceStore.class);
    }

    @Test
    void disablingTheSurfaceRemovesTheRouteAndTheSettingsLink() throws Exception {
        Path disabledHome = prepareAppHome(false);
        TesseraqlRuntime disabled = TesseraqlRuntime.start(disabledHome, freePort());
        try {
            String cookie = establishSession(disabled);
            // The bundled app is not mounted...
            assertThat(get(disabled, cookie, "/_tesseraql/account").statusCode())
                    .isEqualTo(404);
            // ...and no preference store is bound.
            assertThat(disabled.camelContext().getRegistry().lookupByNameAndType(
                    TesseraqlProperties.PREFERENCE_STORE_BEAN, PreferenceStore.class))
                    .isNull();
            // A signed-in shell page still shows the user menu — just without the settings
            // link or the theme toggle, so the chrome never links (or posts to) a 404.
            HttpResponse<String> shellPage = get(disabled, cookie, "/home");
            assertThat(shellPage.statusCode()).isEqualTo(200);
            assertThat(shellPage.body()).contains("tql-account-menu")
                    .contains("Sign out")
                    .doesNotContain("Account settings")
                    .doesNotContain("data-hc-theme-toggle");
        } finally {
            disabled.close();
            deleteRecursively(disabledHome);
        }
    }

    private static String establishSession(TesseraqlRuntime target) {
        SessionStore sessions = target.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal(
                "account-user", "account-user", "Account User", null, List.of(),
                List.of("ADMIN"), List.of(), Map.of()));
        return sessions.cookieName() + "=" + sid;
    }

    private static String csrfFor(TesseraqlRuntime target, String cookie) {
        SessionStore sessions = target.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        return sessions.csrfTokenFromCookie(cookie);
    }

    private static HttpResponse<String> postForm(TesseraqlRuntime target, String cookie,
            String path, String form) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + target.port() + path))
                .header("Cookie", cookie)
                .header("X-CSRF-Token", csrfFor(target, cookie))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(TesseraqlRuntime target, String cookie, String path)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + target.port() + path))
                .header("Cookie", cookie)
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private static Path prepareAppHome(boolean accountEnabled) throws IOException {
        Path target = Files.createTempDirectory("tesseraql-account-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: account-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  i18n:
                    defaultLocale: en
                    locales: [en, ja]
                  notifications:
                    channels:
                      user-mail:
                        type: mail
                        userOptOut: "true"
                  apps:
                    account:
                      enabled: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), accountEnabled));
        // A minimal shell page of the host app, to see the chrome outside the account app.
        Path home = target.resolve("web/home");
        Files.createDirectories(home);
        Files.writeString(home.resolve("get.yml"), """
                version: tesseraql/v1
                id: home
                kind: route
                recipe: query-html
                security:
                  auth: browser
                sql:
                  file: home.sql
                  mode: query
                response:
                  html:
                    status: 200
                    template: home.html
                    model:
                      rows: sql.rows
                """);
        Files.writeString(home.resolve("home.sql"), "select 1 as x\n");
        // A command that notifies its own caller - the recipient-aware opt-out testbed.
        Path notifyMe = target.resolve("web/notify-me");
        Files.createDirectories(notifyMe);
        Files.writeString(notifyMe.resolve("post.yml"), """
                version: tesseraql/v1
                id: notify.me
                kind: route
                recipe: command-json
                security:
                  auth: browser
                  csrf: true
                sql:
                  file: notify-me.sql
                  mode: update
                notify:
                  ping:
                    channel: user-mail
                    recipient: principal.subject
                    payload:
                      login: principal.loginId
                response:
                  json:
                    status: 200
                    body:
                      result: notify
                """);
        Files.writeString(notifyMe.resolve("notify-me.sql"),
                "delete from tql_user_preference where tenant_id = '_never_'\n");
        // Declared app preferences (slice 5) and a route reading one back.
        Files.writeString(target.resolve("config/preferences.yml"), """
                preferences:
                  - key: pageSize
                    label: app.pref.pageSize
                    type: choice
                    options: ["10", "25", "50"]
                    default: "25"
                  - key: beta
                    type: boolean
                    default: "false"
                """);
        Path pageSize = target.resolve("web/page-size");
        Files.createDirectories(pageSize);
        Files.writeString(pageSize.resolve("get.yml"), """
                version: tesseraql/v1
                id: page.size
                kind: route
                recipe: query-json
                security:
                  auth: browser
                sql:
                  file: page-size.sql
                  mode: query
                  params:
                    size: preference.pageSize
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(pageSize.resolve("page-size.sql"),
                "select /* size */'25' as size\n");
        Files.writeString(home.resolve("home.html"), """
                <!DOCTYPE html>
                <html xmlns:th="http://www.thymeleaf.org"
                      th:replace="~{tql/shell :: shell(|Home|, ~{}, ~{}, ~{:: #page-content})}">
                <div id="page-content"><p>home</p></div>
                </html>
                """);
        return target;
    }
}
