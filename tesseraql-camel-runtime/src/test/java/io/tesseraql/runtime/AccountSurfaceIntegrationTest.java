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
            // link, so the chrome never links a 404.
            HttpResponse<String> shellPage = get(disabled, cookie, "/home");
            assertThat(shellPage.statusCode()).isEqualTo(200);
            assertThat(shellPage.body()).contains("tql-account-menu")
                    .contains("Sign out")
                    .doesNotContain("Account settings");
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
