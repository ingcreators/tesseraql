package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The framework's own locales are reachable with zero configuration
 * (docs/internationalization.md): an app that declares no {@code tesseraql.i18n.locales} and
 * ships no {@code messages/} directory still serves the built-in Japanese catalog, so a
 * browser's {@code Accept-Language: ja} renders the framework's chrome in Japanese. The served
 * set used to be the app's own catalog files alone, which negotiated that header to English —
 * the language picker the account surface promises "with zero configuration" offered one
 * option. And a declared locale the runtime cannot serve ({@code ja_JP}, the spelling
 * {@code Locale.forLanguageTag} folds to {@code und}) is refused at boot with its key named,
 * where it used to boot and answer 500 to every error response.
 */
@Testcontainers
class DefaultLocalesIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("create table things (id serial primary key, "
                    + "name varchar(64) not null)");
        }
        appHome = prepareAppHome("");
        runtime = TesseraqlRuntime.start(appHome, 0);
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            delete(appHome);
        }
    }

    @Test
    void acceptLanguageJaRendersTheFrameworkChromeInJapaneseWithNoConfiguration()
            throws Exception {
        HttpResponse<String> japanese = get("/things", "ja, en;q=0.5");
        assertThat(japanese.statusCode()).isEqualTo(200);
        // The shell's lang attribute is the negotiated locale; the empty-table text is the
        // framework's tql.view.empty, which only the built-in catalog carries.
        assertThat(japanese.body()).contains("lang=\"ja\"")
                .contains("データがありません")
                .doesNotContain("No rows");

        // English stays the default: no header, and a header naming only a locale nobody
        // serves, both render the app's default locale.
        HttpResponse<String> english = get("/things", null);
        assertThat(english.body()).contains("lang=\"en\"").contains("No rows");
        HttpResponse<String> unserved = get("/things", "de");
        assertThat(unserved.body()).contains("lang=\"en\"").contains("No rows");
    }

    /**
     * The bundled sign-in surface follows the negotiated locale too (slice 8b, F127): the
     * auth-ui system app carried hard-coded English under a {@code lang="ja"} root, so the
     * language the framework negotiated stopped at the surface every end user meets first.
     */
    @Test
    void theSignInPageRendersInTheNegotiatedLocale() throws Exception {
        HttpResponse<String> japanese = get("/_tesseraql/login", "ja");
        assertThat(japanese.statusCode()).isEqualTo(200);
        assertThat(japanese.body()).contains("lang=\"ja\"")
                .contains("<h1>サインイン</h1>").contains("ログイン ID")
                .doesNotContain("<h1>Sign in</h1>");

        HttpResponse<String> english = get("/_tesseraql/login", "en");
        assertThat(english.body()).contains("<h1>Sign in</h1>").doesNotContain("サインイン");
    }

    @Test
    void aDefaultLocaleTheRuntimeCannotServeRefusesTheBootAtItsKey() throws Exception {
        Path misspelled = prepareAppHome("""
                  i18n:
                    defaultLocale: ja_JP
                """);
        try {
            assertThatThrownBy(() -> TesseraqlRuntime.start(misspelled, 0))
                    .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                    .hasMessageStartingWith("TQL-YAML-1065: tesseraql.i18n.defaultLocale:"
                            + " 'ja_JP' is not a language tag the JDK can format");
        } finally {
            delete(misspelled);
        }
    }

    private static HttpResponse<String> get(String path, String acceptLanguage)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path));
        if (acceptLanguage != null) {
            request.header("Accept-Language", acceptLanguage);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A list page over an empty table; {@code extraConfig} is appended under {@code tesseraql:}. */
    private static Path prepareAppHome(String extraConfig) throws IOException {
        Path target = Files.createTempDirectory("tesseraql-default-locales-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: default-locales-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                %s""".formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), extraConfig));
        Path list = target.resolve("web/things");
        Files.createDirectories(list);
        Files.writeString(list.resolve("get.yml"), """
                version: tesseraql/v1
                id: things.page
                kind: route
                recipe: query-html
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: list.sql
                      mode: query
                response:
                  html:
                    view: things
                """);
        Files.writeString(list.resolve("list.sql"),
                "select id, name from things order by id\n;\n");
        Files.writeString(list.resolve("list.view.yml"), """
                version: tesseraql/v1
                id: things
                kind: view
                recipe: list
                key: id
                title: Things
                columns:
                  - { name: id, label: "#" }
                  - { name: name }
                """);
        return target;
    }

    private static void delete(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            files.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> path.toFile().delete());
        }
    }
}
