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
import java.sql.Statement;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The decision-11 guarantee end to end (docs/list-surface.md): a page-frame list's row link
 * carries the list's own URL as {@code _return} with the acting row's fragment, the edit form
 * echoes it, and the command's {@code location: back} lands the browser back on the same
 * conditions, focused on the row — while a hostile {@code _return} falls back to the root.
 */
@Testcontainers
class ListReturnIntegrationTest {

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
    void aPageFrameRowLinkCarriesTheReturnTargetWithTheRowFragment() throws Exception {
        // Page 2 of size-2 over three rows holds row 3 alone; base64url("3") = "Mw".
        HttpResponse<String> response = get("/things?page=2");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("id=\"row-Mw\"");
        assertThat(response.body())
                .contains("/things/3/edit?_return=%2Fthings%3Fpage%3D2%23row-Mw");
    }

    @Test
    void theEditFormEchoesTheReturnTarget() throws Exception {
        HttpResponse<String> response = get("/things/3/edit?_return="
                + URLEncoder.encode("/things?page=2#row-Mw", StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("name=\"_return\"")
                .contains("value=\"/things?page=2#row-Mw\"");
    }

    @Test
    void locationBackLandsOnTheListFocusedOnTheRow() throws Exception {
        HttpResponse<String> response = postForm("/things/3/update",
                "name=Renamed&_return=" + URLEncoder.encode("/things?page=2#row-Mw",
                        StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(response.headers().firstValue("location"))
                .hasValue("/things?page=2#row-Mw");
    }

    @Test
    void locationBackRefusesAnOffSiteReturnTarget() throws Exception {
        HttpResponse<String> response = postForm("/things/1/update",
                "name=Renamed&_return=" + URLEncoder.encode("https://evil.example/x",
                        StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(response.headers().firstValue("location")).hasValue("/");
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
        Path home = Files.createTempDirectory("tesseraql-list-return-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: list-return-app
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
                pagination: { size: 2, count: true }
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
                  - { name: name, link: "/things/{id}/edit" }
                """);
        Path edit = home.resolve("web/things/{id}/edit");
        Files.createDirectories(edit);
        Files.writeString(edit.resolve("get.yml"), """
                version: tesseraql/v1
                id: things.edit
                kind: route
                recipe: query-html
                input:
                  id: { type: integer, required: true }
                sources:
                  main:
                    sql:
                      file: thing.sql
                      mode: query
                      params:
                        id: params.id
                response:
                  html:
                    view: things.edit
                """);
        Files.writeString(edit.resolve("thing.sql"),
                "select id, name from things where id = /* id */ 1\n;\n");
        Files.writeString(edit.resolve("edit.view.yml"), """
                version: tesseraql/v1
                id: things.edit
                kind: view
                recipe: form
                action: /things/{id}/update
                title: Edit thing
                fields:
                  - name: name
                """);
        Path update = home.resolve("web/things/{id}/update");
        Files.createDirectories(update);
        Files.writeString(update.resolve("post.yml"), """
                version: tesseraql/v1
                id: things.update
                kind: route
                recipe: command-json
                input:
                  id: { type: integer, required: true }
                  name: { type: string, required: true, maxLength: 100 }
                steps:
                  - id: main
                    sql:
                      file: update.sql
                      mode: update
                      params:
                        name: params.name
                        id: params.id
                response:
                  redirect:
                    location: back
                """);
        Files.writeString(update.resolve("update.sql"),
                "update things set name = /* name */ 'x' where id = /* id */ 1\n;\n");
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
