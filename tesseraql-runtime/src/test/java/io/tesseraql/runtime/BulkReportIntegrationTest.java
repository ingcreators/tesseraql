package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.rows.RowTokens;
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
import java.sql.Statement;
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
 * The bulk report end to end (docs/bulk-report.md slice 1): a snapshot grid page's bulk
 * action answers 307 back to the list with a parked report handle; the re-posted form
 * renders the same frozen membership carrying the bounded reason groups, the row marks, the
 * re-checked retry set and the row numbers — and the JSON outcomes contract is untouched.
 */
@Testcontainers
class BulkReportIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String cookie;
    static String csrf;

    static final List<String> KEY = List.of("id");

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
        SessionStore sessions = runtime.context().lookup(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal("actor-1", "actor-1", "Actor", null,
                List.of(), List.of("USER"), List.of(), Map.of()), SessionStore.ClientInfo.NONE);
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

    private static String token(String id) {
        return RowTokens.encode(Map.of("id", id), KEY);
    }

    @Test
    void theGridRendersRowNumbersAndTheSelectionMachinery() throws Exception {
        HttpResponse<String> page = send(HttpRequest.newBuilder(uri("/docs"))
                .header("Cookie", cookie).build());

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body())
                .contains("aria-label=\"Row number\"")
                .contains("name=\"ids\"")
                .contains("formaction=\"/api/docs/_bulk/submit\"")
                // The action button carries the current page for the round trip.
                .contains("name=\"page\" value=\"1\"");
    }

    @Test
    void aBulkActionRoundTripsTheReportOntoTheFrozenPage() throws Exception {
        // Membership = the five seeded docs in id order; act on B-1..B-4 from page 1.
        String membership = String.join("&", List.of("B-1", "B-2", "B-3", "B-4", "B-5")
                .stream().map(id -> "keys=" + encode(token(id))).toList());
        String selection = String.join("&", List.of("B-1", "B-2", "B-3", "B-4")
                .stream().map(id -> "ids=" + encode(token(id))).toList());
        String body = "_csrf=" + encode(csrf) + "&_return=" + encode("/docs") + "&page=1&"
                + membership + "&" + selection;

        HttpResponse<String> redirect = send(HttpRequest.newBuilder(
                uri("/api/docs/_bulk/submit"))
                .header("Cookie", cookie)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());

        assertThat(redirect.statusCode()).isEqualTo(307);
        String location = redirect.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith("/docs?bulkReport=");

        // The 307 re-post: the browser re-sends the intact form to the list's POST leg.
        HttpResponse<String> page = send(HttpRequest.newBuilder(uri(location))
                .header("Cookie", cookie)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());

        assertThat(page.statusCode()).isEqualTo(200);
        // B-1 and B-4 submitted; B-2's guard refused with its declared text; B-3 was
        // already in review, the state-as-lock's stale answer.
        assertThat(page.body())
                .contains("2 of 4 succeeded; 2 failed.")
                .contains("needs a positive amount (1)")
                .contains("TQL-WORKFLOW-3201 (1)")
                // The entry references position AND identity, and links by token.
                .contains("Row 2 — B-2")
                .contains("#row-" + token("B-2"))
                // The markup is the shared report pattern now (docs/csv-import.md decision 4),
                // filled here by the bulk feeder — same region, same anchors, one fragment.
                .contains("class=\"tql-report hc-stack\"")
                .contains("id=\"docs-bulk-report\"")
                // The failed rows are marked with their reason group; the retry set is
                // re-checked. B-2 sits in the first group rendered.
                .contains("aria-describedby=\"docs-bulk-group-0\"")
                .contains("data-attention=\"error\"")
                .containsPattern("value=\"" + token("B-2") + "\"\\s+aria-label=\"[^\"]+\" checked")
                .containsPattern("value=\"" + token("B-3") + "\"\\s+aria-label=\"[^\"]+\" checked");
        // Rows reflect what happened: the succeeded row renders its NEW state, live.
        assertThat(page.body()).contains("review");
        // The succeeded row is not re-checked.
        assertThat(page.body())
                .doesNotContainPattern(
                        "value=\"" + token("B-1") + "\"\\s+aria-label=\"[^\"]+\" checked");
    }

    @Test
    void anOffsetListsBulkActionTakesTheOrdinary303Leg() throws Exception {
        // No frozen membership in the form: the state lives in the _return URL, so the
        // round trip is a 303 and the report references rows by identity alone — a row
        // number would not be authoritative here (docs/bulk-report.md decision 4).
        String body = "_csrf=" + encode(csrf) + "&_return=" + encode("/docs-offset")
                + "&ids=" + encode(token("B-6")) + "&ids=" + encode(token("B-7"));

        HttpResponse<String> redirect = send(HttpRequest.newBuilder(
                uri("/api/docs/_bulk/submit"))
                .header("Cookie", cookie)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());

        assertThat(redirect.statusCode()).isEqualTo(303);
        String location = redirect.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith("/docs-offset?bulkReport=");

        HttpResponse<String> page = send(HttpRequest.newBuilder(uri(location))
                .header("Cookie", cookie).build());

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body())
                .contains("0 of 2 succeeded; 2 failed.")
                .contains("needs a positive amount (1)")
                .contains("TQL-WORKFLOW-3201 (1)")
                // Identity-only reference: no membership, no authoritative number.
                .contains(">B-6</a>")
                .doesNotContain("Row 1 — B-6")
                .contains("data-attention=\"error\"")
                // The offset grid still numbers its rows for orientation.
                .contains("aria-label=\"Row number\"");
        assertThat(page.body())
                .containsPattern("value=\"" + token("B-6") + "\"\\s+aria-label=\"[^\"]+\" checked");
    }

    @Test
    void aForeignOrExpiredHandleRendersThePlainList() throws Exception {
        HttpResponse<String> page = send(HttpRequest.newBuilder(
                uri("/docs?bulkReport=not-a-handle"))
                .header("Cookie", cookie).build());

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).doesNotContain("class=\"tql-report");
    }

    @Test
    void theJsonOutcomesContractIsUntouched() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                uri("/api/docs/_bulk/submit"))
                .header("Cookie", cookie)
                .header("X-CSRF-Token", csrf)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"keys\": [\"B-5\"]}"))
                .build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"requested\"").contains("\"succeeded\"").contains("\"outcomes\"");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static URI uri(String path) {
        return URI.create("http://localhost:" + runtime.port() + path);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table docs (id varchar(64) primary key,"
                    + " status varchar(32) not null, amount int not null)");
            statement.execute("insert into docs (id, status, amount) values"
                    + " ('B-1', 'draft', 100), ('B-2', 'draft', 0), ('B-3', 'review', 50),"
                    + " ('B-4', 'draft', 10), ('B-5', 'draft', 10),"
                    // The offset-leg fixtures: both fail deterministically whatever the test
                    // order — B-6's guard always refuses, B-7 is already past the transition.
                    + " ('B-6', 'draft', 0), ('B-7', 'review', 30)");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-bulk-report-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: bulk-report-app
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  workflow:
                    mode: app
                  security:
                    defaults:
                      routes:
                        - match: /**
                          auth: browser
                          csrf: auto
                    policies:
                      wf.act:
                        anyOf:
                          - role: USER
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Files.createDirectories(home.resolve("workflow"));
        Files.writeString(home.resolve("workflow/doc.yml"), """
                version: tesseraql/v1
                id: doc
                kind: workflow
                mode: app
                document: { type: doc, table: docs, key: id, stateColumn: status }
                basePath: /api/docs
                security: { auth: browser, policy: wf.act }
                initial: draft
                states:
                  - { id: draft, type: initial }
                  - { id: review }
                  - { id: done, type: terminal }
                transitions:
                  - id: submit
                    from: draft
                    to: review
                    bulk: true
                    guard:
                      expression: "document.amount > 0"
                      message: needs a positive amount
                    command: { file: touch.sql }
                  - id: approve
                    from: review
                    to: done
                    command: { file: touch.sql }
                """);
        Files.writeString(home.resolve("workflow/touch.sql"), """
                update docs set amount = amount where id = /* key */'B-1'
                ;
                """);
        Path docsDir = home.resolve("web/docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: docs.page
                kind: route
                recipe: query-html

                input:
                  q:
                    type: string
                    required: false
                    maxLength: 100

                security:
                  policy: wf.act

                pagination:
                  strategy: snapshot
                  size: 3
                  cap: 100

                sources:
                  main:
                    sql:
                      file: docs.sql
                      mode: query
                      params:
                        q: query.q
                        keys: params.keys
                response:
                  html:
                    status: 200
                    view: docs
                """);
        Files.writeString(docsDir.resolve("docs.sql"), """
                select id, status, amount
                from docs
                where 1 = 1
                /*%if q */
                  and lower(id) like lower('%' || /* q */ 'B' || '%')
                /*%end*/
                /*%if keys != null */
                  and id in /* keys */('B-1')
                /*%end*/
                order by id asc
                """);
        Files.writeString(docsDir.resolve("list.view.yml"), """
                version: tesseraql/v1
                id: docs
                kind: view
                recipe: list
                key: id
                title: Docs
                columns:
                  - { name: id, label: "#" }
                  - { name: status }
                  - { name: amount }
                actions:
                  - label: Submit
                    action: /api/docs/_bulk/submit
                """);
        // The offset sibling (docs/bulk-report.md slice 2): same key, same action, no frozen
        // membership — its bulk round trip is the 303 leg.
        Path offsetDir = home.resolve("web/docs-offset");
        Files.createDirectories(offsetDir);
        Files.writeString(offsetDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: docs.offset.page
                kind: route
                recipe: query-html

                security:
                  policy: wf.act

                sources:
                  main:
                    sql:
                      file: docs-offset.sql
                      mode: query
                response:
                  html:
                    status: 200
                    view: docs_offset
                """);
        Files.writeString(offsetDir.resolve("docs-offset.sql"), """
                select id, status, amount
                from docs
                order by id asc
                """);
        Files.writeString(offsetDir.resolve("list.view.yml"), """
                version: tesseraql/v1
                id: docs_offset
                kind: view
                recipe: list
                key: id
                title: Docs (offset)
                columns:
                  - { name: id, label: "#" }
                  - { name: status }
                  - { name: amount }
                actions:
                  - label: Submit
                    action: /api/docs/_bulk/submit
                """);
        return home;
    }
}
