package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The reviewed upload's HTML face (docs/csv-import.md slice 4): one document at one URL — a GET
 * renders the kit's upload form, and the file-import POST answers the same page carrying the
 * report and, exactly when something can be committed, the confirm form.
 *
 * <p>Browser-authenticated on purpose. The page's forms carry a CSRF token and the batch belongs
 * to the principal who parked it, so a bearer fixture would assert both against nothing.
 */
@Testcontainers
class ImportPageIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final String BOUNDARY = "----tqlImportPageBoundary";

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String cookie;
    static String csrf;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
        SessionStore sessions = runtime.context().lookup(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal("importer", "importer", "Importer", null,
                List.of(), List.of("IMPORTER"), List.of(), Map.of()), SessionStore.ClientInfo.NONE);
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
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            }
        }
    }

    @BeforeEach
    void clearItems() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("delete from items");
        }
    }

    @Test
    void theEmptyPageIsTheKitsUploadFormAndNothingElse() throws Exception {
        HttpResponse<String> page = send(HttpRequest.newBuilder(uri("/items/import"))
                .header("Cookie", cookie).build());

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body())
                // Both encodings: the native submit reads enctype, htmx reads hx-encoding.
                .contains("enctype=\"multipart/form-data\"")
                .contains("hx-encoding=\"multipart/form-data\"")
                .contains("type=\"file\"")
                // The bar the kit's installUploadProgress drives, inside the requesting form.
                .contains("data-hc-upload-progress")
                .contains("class=\"hc-progress htmx-indicator\"")
                // The accepted types come from the declared format, not from the page.
                .contains("accept=\".csv,text/csv\"")
                // And what the file has to contain comes from the import's own columns.
                .contains("name, qty")
                .contains("name=\"_csrf\"");
        // Nothing was uploaded, so there is nothing to report and nothing to confirm.
        assertThat(page.body()).doesNotContain("class=\"tql-report");
        assertThat(page.body()).doesNotContain("name=\"token\"");
    }

    @Test
    void aValidUploadAnswersTheReportAndTheConfirmForm() throws Exception {
        HttpResponse<String> answer = upload("name,qty\nalpha,1\nbeta,2\n");

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.headers().firstValue("Content-Type").orElse("")).contains("text/html");
        assertThat(answer.body())
                .contains("2 row(s) ready to import.")
                // The confirm form: the token rides the address AND a hidden field.
                .contains("name=\"token\"")
                .contains("/items/import/")
                .contains("/commit");
        // Nothing is written until the confirm.
        assertThat(itemCount()).isZero();
    }

    @Test
    void aRejectedRowNamesTheFileLineTheColumnAndTheValue() throws Exception {
        HttpResponse<String> answer = upload("name,qty\ndelta,4\nbroken,not-a-number\n");

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.body())
                .contains("1 of 2 row(s) can be imported; 1 were rejected.")
                // The FILE line, not the data-row ordinal: the header row shifts them apart,
                // and line 3 is what the author sees in their editor.
                .contains("Line 3")
                .doesNotContain("Line 2")
                // The contract's Row / Field / Message table, with the rejected text quoted.
                .contains("<caption>Rejected rows (1 of 1 shown)</caption>")
                .contains("scope=\"row\"")
                .contains("not-a-number")
                .contains("is not a valid number")
                // A reason group links to the table row that details it.
                .contains("#items-import-import-row-0")
                // onError: skip, so the valid row can still be committed.
                .contains("name=\"token\"");
    }

    @Test
    void aFileWithNothingImportableAnswers422AndCarriesTheSwapMarker() throws Exception {
        // A header that does not map: no row was ever examined, so it is a file-level failure
        // with no row number to hang it on.
        HttpResponse<String> answer = upload("name,quantity\nalpha,1\n");

        assertThat(answer.statusCode()).isEqualTo(422);
        assertThat(answer.headers().firstValue("Content-Type").orElse("")).contains("text/html");
        // Without this marker htmx discards a 4xx body and the page renders nothing at all.
        assertThat(answer.body()).contains("data-tql-import-report");
        assertThat(answer.body())
                .contains("This file could not be imported.")
                .contains("The file could not be read")
                // No committable set, so no confirm form — the same fact the 422 was read off.
                .doesNotContain("name=\"token\"");
    }

    @Test
    void theConfirmRedirectsToTheTransferAndTheSecondTryIsAStaleTokenFragment()
            throws Exception {
        String token = tokenOf(upload("name,qty\nalpha,1\n"));

        HttpResponse<String> confirmed = send(HttpRequest.newBuilder(
                uri("/items/import/" + token + "/commit"))
                .header("Cookie", cookie)
                .header("Accept", "text/html")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + encode(csrf)
                        + "&token=" + encode(token)))
                .build());

        // Post/redirect/get to the transfer's own status resource — the leg the job card
        // replaces for an htmx caller, and the one that keeps working without JavaScript.
        assertThat(confirmed.statusCode()).isEqualTo(303);
        assertThat(confirmed.headers().firstValue("Location").orElseThrow())
                .startsWith("/items/import/");

        HttpResponse<String> replayed = send(HttpRequest.newBuilder(
                uri("/items/import/" + token + "/commit"))
                .header("Cookie", cookie)
                .header("Accept", "text/html")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + encode(csrf)
                        + "&token=" + encode(token)))
                .build());

        // A spent token is a conflict, and the fix is always a fresh upload — said in the
        // report region, carrying the marker that lets a 409 body reach the page.
        assertThat(replayed.statusCode()).isEqualTo(409);
        assertThat(replayed.body())
                .contains("data-tql-import-report")
                .contains("Upload the file again");
    }

    @Test
    void theJsonContractIsUntouched() throws Exception {
        HttpResponse<String> answer = send(HttpRequest.newBuilder(uri("/items/import"))
                .header("Cookie", cookie)
                .header("X-CSRF-Token", csrf)
                .header("Accept", "application/json")
                .header("Content-Type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofString("name,qty\nalpha,1\n"))
                .build());

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.headers().firstValue("Content-Type").orElse(""))
                .contains("application/json");
        assertThat(answer.body()).contains("\"token\"").contains("\"rowCount\":1");
    }

    private static String tokenOf(HttpResponse<String> page) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("name=\"token\" value=\"([^\"]+)\"").matcher(page.body());
        assertThat(matcher.find()).as("the confirm form's token").isTrue();
        return matcher.group(1);
    }

    /** A browser's multipart upload of {@code content} as the page's file field. */
    private static HttpResponse<String> upload(String content) throws Exception {
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"_csrf\"\r\n\r\n" + csrf + "\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"items.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n" + content + "\r\n"
                + "--" + BOUNDARY + "--\r\n";
        return send(HttpRequest.newBuilder(uri("/items/import"))
                .header("Cookie", cookie)
                .header("X-CSRF-Token", csrf)
                .header("Accept", "text/html")
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static URI uri(String path) {
        return URI.create("http://localhost:" + runtime.port() + path);
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private static long itemCount() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select count(*) from items")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-import-page-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: import-page-app
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  security:
                    defaults:
                      routes:
                        - match: /**
                          auth: browser
                          csrf: auto
                    policies:
                      items.write:
                        anyOf:
                          - role: IMPORTER
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__tables.sql"),
                "create table items (name varchar(100) primary key, qty integer not null);\n");

        Path route = Files.createDirectories(home.resolve("web/items/import"));
        // One document, two routes, one address (docs/csv-import.md decision 7).
        Files.writeString(route.resolve("items-import.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: import
                title: Import items
                action: /items/import
                """);
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.importPage
                kind: route
                recipe: page
                security:
                  auth: browser
                  policy: items.write
                response:
                  html:
                    view: items-import
                """);
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.import
                kind: route
                recipe: file-import
                security:
                  auth: browser
                  policy: items.write
                import:
                  format: csv
                  columns:
                    - name
                    - { name: qty, type: number }
                  onError: skip
                  review: required
                response:
                  html:
                    view: items-import
                steps:
                  - id: row
                    sql:
                      file: upsert-item.sql
                """);
        Files.writeString(route.resolve("upsert-item.sql"), """
                insert into items (name, qty)
                values ( /* name */ 'sample', cast( /* qty */ '1' as integer) )
                on conflict (name) do update set qty = excluded.qty
                ;
                """);
        return home;
    }
}
