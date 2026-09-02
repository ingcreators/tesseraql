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
    void anHtmxConfirmAnswersTheJobCardAndTheCardStopsWhenTheRunDoes() throws Exception {
        String token = tokenOf(upload("name,qty\nalpha,1\nbeta,2\n"));

        HttpResponse<String> accepted = confirm(token, true);

        // 202 and the card — the async-job contract's own shape, and the one place the
        // framework kicks a job off from a page.
        assertThat(accepted.statusCode()).isEqualTo(202);
        assertThat(accepted.body())
                .contains("data-hc-job")
                .contains("id=\"tql-job-")
                .contains("hx-target=\"this\"");

        String card = awaitTerminalCard(transferOf(accepted.body()));
        assertThat(card)
                .contains("data-state=\"done\"")
                // The stop condition IS the absent trigger: nothing left to poll with.
                .doesNotContain("hx-trigger=")
                // Terminal, so no Cancel either.
                .doesNotContain("tql.job.cancel")
                .doesNotContain("name=\"_csrf\"")
                // The denominator the review made knowable.
                .contains("2 of 2 rows");
        assertThat(itemCount()).isEqualTo(2);
    }

    @Test
    void anUnknownTransferIsATombstoneForTheCardAndA404ForTheApi() throws Exception {
        HttpResponse<String> card = send(HttpRequest.newBuilder(
                uri("/items/import/does-not-exist"))
                .header("Cookie", cookie).header("HX-Request", "true").build());

        // 200, because a polling card that receives an error keeps polling an error.
        assertThat(card.statusCode()).isEqualTo(200);
        assertThat(card.body()).contains("data-state=\"expired\"").doesNotContain("hx-trigger=");

        HttpResponse<String> api = send(HttpRequest.newBuilder(
                uri("/items/import/does-not-exist"))
                .header("Cookie", cookie).header("Accept", "application/json").build());
        assertThat(api.statusCode()).isEqualTo(404);
        assertThat(api.body()).contains("TQL-LD-2822");
    }

    @Test
    void aPlainNavigationToTheTransferGetsTheCardInsideTheAppsChrome() throws Exception {
        String token = tokenOf(upload("name,qty\nalpha,1\n"));
        HttpResponse<String> redirected = confirm(token, false);
        String target = redirected.headers().firstValue("Location").orElseThrow();

        HttpResponse<String> page = send(HttpRequest.newBuilder(uri(target))
                .header("Cookie", cookie).header("Accept", "text/html").build());

        // The no-JS leg lands on a real page: the same card, with the shell around it.
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("<title>").contains("data-hc-job");
    }

    @Test
    void cancellingARunningImportStopsItAndWritesNothing() throws Exception {
        // 500 rows that each sleep 10ms: the run is long enough for the loop to reach a
        // boundary and read the flag, which is the whole point of a cooperative stop.
        StringBuilder file = new StringBuilder("name,qty\n");
        for (int i = 0; i < 500; i++) {
            file.append("slow-").append(i).append(",1\n");
        }
        String token = tokenOf(upload("/items/slow-import", file.toString()));
        HttpResponse<String> accepted = confirm("/items/slow-import", token, true);
        String transferId = transferOf(accepted.body());

        HttpResponse<String> cancelled = send(HttpRequest.newBuilder(
                uri("/items/slow-import/" + transferId + "/cancel"))
                .header("Cookie", cookie)
                .header("X-CSRF-Token", csrf)
                .header("HX-Request", "true")
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build());
        // The answer is the state NOW, not the state it is about to reach: the loop decides
        // when, at its next row boundary.
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(cancelled.body()).contains("data-hc-job");

        String card = awaitTerminalCard("/items/slow-import", transferId);
        assertThat(card).contains("data-state=\"cancelled\"").doesNotContain("hx-trigger=");
        // An import is one transaction, so a stop before the commit takes everything with it.
        assertThat(slowCount()).isZero();
    }

    @Test
    void anExcelImportRidesTheSameDesignAndSaysSheetRow() throws Exception {
        // The format is an axis, not a name (docs/csv-import.md decision 8): the same recipe,
        // the same review, the same page — only `format:` differs. What the format supplies is
        // where a row sits, and a workbook's answer is a sheet and a row, never a line.
        HttpResponse<String> answer = uploadWorkbook();

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.body())
                .contains("1 of 2 row(s) can be imported; 1 were rejected.")
                // Sheet and row, and the row is the one the author sees in the workbook: the
                // header shifts the data ordinal by one, and row 3 is the bad line.
                .contains("items row 3")
                .doesNotContain("Line 3")
                .contains("is not a valid number")
                // Committable, so the confirm form is there — an Excel import is not a
                // different feature, it is this one with a different codec.
                .contains("name=\"token\"");
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

    /** The transfer id the card's own DOM id carries. */
    private static String transferOf(String card) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("id=\"tql-job-([^\"]+)\"").matcher(card);
        assertThat(matcher.find()).as("the card's transfer id").isTrue();
        return matcher.group(1);
    }

    private static HttpResponse<String> confirm(String token, boolean htmx) throws Exception {
        return confirm("/items/import", token, htmx);
    }

    private static HttpResponse<String> confirm(String path, String token, boolean htmx)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                uri(path + "/" + token + "/commit"))
                .header("Cookie", cookie)
                .header("Accept", "text/html")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + encode(csrf)
                        + "&token=" + encode(token)));
        if (htmx) {
            request.header("HX-Request", "true").header("X-CSRF-Token", csrf);
        }
        return send(request.build());
    }

    private static String awaitTerminalCard(String transferId) throws Exception {
        return awaitTerminalCard("/items/import", transferId);
    }

    /** Polls the card the way the card polls itself, until it stops carrying a trigger. */
    private static String awaitTerminalCard(String path, String transferId) throws Exception {
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(60);
        String body = "";
        while (java.time.Instant.now().isBefore(deadline)) {
            body = send(HttpRequest.newBuilder(uri(path + "/" + transferId))
                    .header("Cookie", cookie).header("HX-Request", "true").build()).body();
            if (!body.contains("hx-trigger=")) {
                return body;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("the card never stopped polling: " + body);
    }

    private static String tokenOf(HttpResponse<String> page) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("name=\"token\" value=\"([^\"]+)\"").matcher(page.body());
        assertThat(matcher.find()).as("the confirm form's token").isTrue();
        return matcher.group(1);
    }

    private static HttpResponse<String> upload(String content) throws Exception {
        return upload("/items/import", content);
    }

    /** A browser's multipart upload of {@code content} as the page's file field. */
    private static HttpResponse<String> upload(String path, String content) throws Exception {
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"_csrf\"\r\n\r\n" + csrf + "\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"items.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n" + content + "\r\n"
                + "--" + BOUNDARY + "--\r\n";
        return send(HttpRequest.newBuilder(uri(path))
                .header("Cookie", cookie)
                .header("X-CSRF-Token", csrf)
                .header("Accept", "text/html")
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    /** A two-row workbook whose second row's quantity is not a number. */
    private static HttpResponse<String> uploadWorkbook() throws Exception {
        byte[] xlsx;
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("items");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("name");
            header.createCell(1).setCellValue("qty");
            org.apache.poi.ss.usermodel.Row good = sheet.createRow(1);
            good.createCell(0).setCellValue("epsilon");
            good.createCell(1).setCellValue(3);
            org.apache.poi.ss.usermodel.Row bad = sheet.createRow(2);
            bad.createCell(0).setCellValue("broken");
            bad.createCell(1).setCellValue("not-a-number");
            workbook.write(out);
            xlsx = out.toByteArray();
        }
        String prologue = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"_csrf\"\r\n\r\n" + csrf + "\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"items.xlsx\"\r\n"
                + "Content-Type: application/vnd.openxmlformats-officedocument"
                + ".spreadsheetml.sheet\r\n\r\n";
        String epilogue = "\r\n--" + BOUNDARY + "--\r\n";
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(prologue.getBytes(StandardCharsets.UTF_8));
        body.write(xlsx);
        body.write(epilogue.getBytes(StandardCharsets.UTF_8));
        return send(HttpRequest.newBuilder(uri("/items/excel-import"))
                .header("Cookie", cookie)
                .header("X-CSRF-Token", csrf)
                .header("Accept", "text/html")
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
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

    private static long slowCount() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "select count(*) from items where name like 'slow-%'")) {
            rows.next();
            return rows.getLong(1);
        }
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
                # The completion signal (docs/csv-import.md decision 6): announced when the
                # import's transaction commits, not when the confirm's response goes out.
                emit:
                  - items.changed
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

        // The same import, one word different (docs/csv-import.md decision 8): `format: excel`
        // is not a second recipe, and the report's row reference is what proves the axis.
        Path excel = Files.createDirectories(home.resolve("web/items/excel-import"));
        Files.writeString(excel.resolve("excel-import.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: import
                title: Import items from a workbook
                action: /items/excel-import
                """);
        Files.writeString(excel.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.excelImportPage
                kind: route
                recipe: page
                security:
                  auth: browser
                  policy: items.write
                response:
                  html:
                    view: excel-import
                """);
        Files.writeString(excel.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.excelImport
                kind: route
                recipe: file-import
                security:
                  auth: browser
                  policy: items.write
                import:
                  format: excel
                  sheet: items
                  columns:
                    - name
                    - { name: qty, type: number }
                  onError: skip
                  review: required
                response:
                  html:
                    view: excel-import
                steps:
                  - id: row
                    sql:
                      file: upsert-item.sql
                """);
        Files.writeString(excel.resolve("upsert-item.sql"), """
                insert into items (name, qty)
                values ( /* name */ 'sample', cast( /* qty */ '1' as integer) )
                on conflict (name) do update set qty = excluded.qty
                ;
                """);

        // The same import over a deliberately slow row statement, so a cancel has a run to
        // land in: the stop is cooperative and takes effect at a row boundary.
        Path slow = Files.createDirectories(home.resolve("web/items/slow-import"));
        Files.writeString(slow.resolve("slow-import.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: import
                title: Slow import
                action: /items/slow-import
                """);
        Files.writeString(slow.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.slowImportPage
                kind: route
                recipe: page
                security:
                  auth: browser
                  policy: items.write
                response:
                  html:
                    view: slow-import
                """);
        Files.writeString(slow.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.slowImport
                kind: route
                recipe: file-import
                security:
                  auth: browser
                  policy: items.write
                import:
                  format: csv
                  columns: [name, qty]
                  onError: skip
                  review: required
                response:
                  html:
                    view: slow-import
                steps:
                  - id: row
                    sql:
                      file: slow-item.sql
                """);
        Files.writeString(slow.resolve("slow-item.sql"), """
                insert into items (name, qty)
                values ( /* name */ 'sample',
                         (select 1 from (select pg_sleep(0.01)) as paused) )
                ;
                """);
        return home;
    }
}
