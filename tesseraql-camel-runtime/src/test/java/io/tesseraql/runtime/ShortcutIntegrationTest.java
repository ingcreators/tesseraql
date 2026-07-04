package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.account.ShortcutStore;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
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
 * Pins end to end (roadmap Phase 51 slice 1): the header toggle pins the current page
 * with its query string, the Pinned group appears in every page's sidebar, the toggle
 * flips honestly, the account card removes, off-site hrefs are refused, and the cap keeps
 * the newest twenty.
 */
@Testcontainers
class ShortcutIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String cookie;
    static String csrf;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        SessionStore sessions = runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal("pin-user", "pin-user", "Pin User",
                null, List.of(), List.of("ADMIN"), List.of(), Map.of()));
        cookie = sessions.cookieName() + "=" + sid;
        csrf = sessions.session(sid).csrfToken();
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
    void pinningAFilteredPageShowsItInEverySidebarAndTogglesOff() throws Exception {
        // Pinning a URL WITH its query string is saving the filter.
        HttpResponse<String> pinned = toggle("/home?q=open&min=5000", "Open over 5000");
        assertThat(pinned.statusCode()).isEqualTo(303);
        assertThat(pinned.headers().firstValue("Location").orElse(""))
                .isEqualTo("/_tesseraql/account");

        // The Pinned group renders on ANY shell page, label and query intact...
        HttpResponse<String> other = get("/home");
        assertThat(other.body()).contains("Pinned").contains("Open over 5000")
                .contains("/home?q=open&amp;min=5000");
        // ...the pinned page itself offers Unpin, others offer Pin...
        assertThat(get("/home?q=open&min=5000").body()).contains(">Unpin<");
        assertThat(other.body()).contains(">Pin<");
        // ...and the account card lists it.
        assertThat(get("/_tesseraql/account").body()).contains("Open over 5000");

        // The same toggle unpins; the sidebar group disappears with its last entry.
        assertThat(toggle("/home?q=open&min=5000", "whatever").statusCode()).isEqualTo(303);
        assertThat(get("/home").body()).doesNotContain("Open over 5000")
                .doesNotContain("tql-nav-pinned");
    }

    /** A pin can never point off-site: absolute and protocol-relative forms refuse. */
    @Test
    void offSiteHrefsAreRefused() throws Exception {
        assertThat(toggle("https://evil.example", "x").statusCode()).isEqualTo(400);
        assertThat(toggle("//evil.example", "x").statusCode()).isEqualTo(400);
        assertThat(toggle("/\\evil.example", "x").statusCode()).isEqualTo(400);
        assertThat(store().list(null, "pin-user", ShortcutStore.PIN, 50)).isEmpty();
    }

    /** The ring keeps the newest twenty; a re-pin bumps instead of duplicating. */
    @Test
    void theCapKeepsTheNewestTwentyAndRePinBumps() {
        ShortcutStore shortcuts = store();
        for (int i = 1; i <= 22; i++) {
            shortcuts.put(null, "cap-user", ShortcutStore.PIN, "/p/" + i, "p" + i, 20);
        }
        List<ShortcutStore.Shortcut> pins = shortcuts.list(null, "cap-user",
                ShortcutStore.PIN, 50);
        assertThat(pins).hasSize(20);
        assertThat(pins.stream().map(ShortcutStore.Shortcut::href))
                .doesNotContain("/p/1", "/p/2").contains("/p/22", "/p/3");

        shortcuts.put(null, "cap-user", ShortcutStore.PIN, "/p/3", "renamed", 20);
        List<ShortcutStore.Shortcut> bumped = shortcuts.list(null, "cap-user",
                ShortcutStore.PIN, 50);
        assertThat(bumped).hasSize(20);
        assertThat(bumped.get(0).href()).isEqualTo("/p/3");
        assertThat(bumped.get(0).label()).isEqualTo("renamed");
    }

    @Test
    void theAccountCardRemovesAPin() throws Exception {
        toggle("/home?keep=me", "Keeper");
        HttpResponse<String> removed = postForm("/_tesseraql/account/shortcuts/remove",
                "kind=pin&href=" + URLEncoder.encode("/home?keep=me", StandardCharsets.UTF_8));
        assertThat(removed.statusCode()).isEqualTo(303);
        assertThat(store().list(null, "pin-user", ShortcutStore.PIN, 50))
                .noneMatch(pin -> pin.href().equals("/home?keep=me"));
    }

    private static ShortcutStore store() {
        return runtime.camelContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.SHORTCUT_STORE_BEAN, ShortcutStore.class);
    }

    private static HttpResponse<String> toggle(String href, String label) throws Exception {
        return postForm("/_tesseraql/account/pins/toggle",
                "href=" + URLEncoder.encode(href, StandardCharsets.UTF_8)
                        + "&label=" + URLEncoder.encode(label, StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> postForm(String path, String form) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Cookie", cookie)
                .header("X-CSRF-Token", csrf)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + runtime.port() + path))
                        .header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-shortcut-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: shortcut-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path home = target.resolve("web/home");
        Files.createDirectories(home);
        Files.writeString(home.resolve("get.yml"), """
                version: tesseraql/v1
                id: home
                kind: route
                recipe: query-html
                input:
                  q:
                    type: string
                    required: false
                  min:
                    type: string
                    required: false
                  keep:
                    type: string
                    required: false
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
