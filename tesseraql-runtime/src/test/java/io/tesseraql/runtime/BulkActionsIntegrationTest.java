package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Bulk actions over the grid page's row selection, end to end (docs/list-surface.md decision
 * 9): the list renders the selection column and bar, the checked rows' tokens post as repeated
 * {@code ids}, the compiler-wired decoder turns them back into key values before binding, the
 * declared array input coerces them, and {@code location: back} lands on the list.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BulkActionsIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
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
    @Order(1)
    void theListRendersTheSelectionColumnAndBar() throws Exception {
        HttpResponse<String> list = get("/things");

        assertThat(list.statusCode()).isEqualTo(200);
        // base64url("1") = MQ — the checkbox value is the row token.
        assertThat(list.body()).contains("name=\"ids\"").contains("value=\"MQ\"");
        assertThat(list.body()).contains("data-hc-datagrid-actions=\"#things-table\"")
                .contains("data-hc-datagrid-count")
                .contains("formaction=\"/things/close\"");
        // The header select-all checkbox carries no name — it can never leak into the payload.
        assertThat(list.body()).doesNotContain("name=\"ids\" aria-label=\"Select all\"");
    }

    @Test
    @Order(2)
    void aSelectionOfTokensDecodesBindsAndReturns() throws Exception {
        // Tokens for rows 1 and 3 (MQ, Mw); the decoder hands the route bare ids.
        HttpResponse<String> response = postForm("/things/close",
                "ids=MQ&ids=Mw&_return=" + URLEncoder.encode("/things?page=1",
                        StandardCharsets.UTF_8));

        assertThat(response.statusCode()).as(response::body).isEqualTo(303);
        assertThat(response.headers().firstValue("location")).hasValue("/things?page=1");
        assertThat(names()).containsExactly("closed", "Beta", "closed");
    }

    @Test
    @Order(3)
    void aSingleCheckedRowStillBindsAsASelection() throws Exception {
        // One checked box posts one ids value; the declared array input keeps its list-ness.
        HttpResponse<String> response = postForm("/things/close", "ids=Mg");

        assertThat(response.statusCode()).as(response::body).isEqualTo(303);
        assertThat(names()).containsExactly("closed", "closed", "closed");
    }

    @Test
    @Order(4)
    void aMalformedSelectionTokenIsRefused() throws Exception {
        HttpResponse<String> response = postForm("/things/close", "ids=%7Cnope");

        assertThat(response.statusCode()).isEqualTo(400);
    }

    private static java.util.List<String> names() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "select name from things order by id")) {
            java.util.List<String> names = new java.util.ArrayList<>();
            while (rows.next()) {
                names.add(rows.getString(1));
            }
            return names;
        }
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postForm(String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "create table things (id int primary key, name varchar(100) not null)");
            statement.execute("insert into things (id, name) values"
                    + " (1, 'Alpha'), (2, 'Beta'), (3, 'Gamma')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-bulk-actions-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: bulk-actions-app
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path list = home.resolve("web/things");
        Files.createDirectories(list);
        Files.writeString(list.resolve("get.yml"), """
                version: tesseraql/v1
                id: things.page
                kind: route
                recipe: query-html
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
                layout: page
                key: id
                title: Things
                actions:
                  - label: Close
                    action: /things/close
                """);
        Path close = home.resolve("web/things/close");
        Files.createDirectories(close);
        Files.writeString(close.resolve("post.yml"), """
                version: tesseraql/v1
                id: things.close
                kind: route
                recipe: command-json
                input:
                  ids:
                    type: array
                    required: true
                    items: { type: integer }
                steps:
                  - id: main
                    sql:
                      file: close.sql
                      mode: update
                      params:
                        ids: params.ids
                response:
                  redirect:
                    location: back
                """);
        Files.writeString(close.resolve("close.sql"),
                "update things set name = 'closed' where id in /* ids */(1)\n");
        return home;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }
}
