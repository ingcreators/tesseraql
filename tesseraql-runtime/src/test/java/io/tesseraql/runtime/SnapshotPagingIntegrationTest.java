package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
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
 * Snapshot pagination end to end (docs/list-surface.md decision 10): the search freezes the
 * membership as row tokens the page carries, the pager posts them back with a page number,
 * each page fetches live state for its slice only, a vanished row renders as a tombstone —
 * and a search over the declared cap is refused with 422.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SnapshotPagingIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final String ALL_KEYS = "keys=MQ&keys=Mg&keys=Mw&keys=NA&keys=NQ";

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
    void theSearchFreezesTheMembershipAndShowsPageOne() throws Exception {
        HttpResponse<String> search = get("/queue");

        assertThat(search.statusCode()).isEqualTo(200);
        // All five hits ride as hidden membership tokens; only page 1 renders.
        assertThat(search.body().split("name=\"keys\"", -1)).hasSize(6);
        assertThat(search.body()).contains(">Alpha<").contains(">Beta<")
                .doesNotContain(">Gamma<");
        assertThat(search.body()).contains("1–2 of 5").contains("(as of search)");
        // The pager is POST buttons — the membership travels in the body.
        assertThat(search.body()).contains("name=\"page\"").contains("value=\"2\"");
    }

    @Test
    @Order(2)
    void aPagePostFetchesTheSliceInMembershipOrder() throws Exception {
        HttpResponse<String> page2 = postForm("/queue", ALL_KEYS + "&page=2");

        assertThat(page2.statusCode()).as(page2::body).isEqualTo(200);
        assertThat(page2.body()).contains(">Gamma<").contains(">Delta<")
                .doesNotContain(">Alpha<");
        assertThat(page2.body()).contains("3–4 of 5").contains("(as of search)");
        // The membership rides along unchanged for the next hop.
        assertThat(page2.body().split("name=\"keys\"", -1)).hasSize(6);
    }

    @Test
    @Order(3)
    void aVanishedRowRendersAsATombstoneAndTheCountHolds() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("delete from queue_items where id = 3");
        }

        HttpResponse<String> page2 = postForm("/queue", ALL_KEYS + "&page=2");

        assertThat(page2.statusCode()).isEqualTo(200);
        assertThat(page2.body()).contains("data-tombstone")
                .contains("No longer in this list.")
                .contains(">Delta<");
        // The count is the snapshot's — only a new search changes it.
        assertThat(page2.body()).contains("3–4 of 5");
    }

    @Test
    @Order(4)
    void aSearchOverTheCapIsRefusedWith422() throws Exception {
        HttpResponse<String> capped = get("/capped");

        assertThat(capped.statusCode()).isEqualTo(422);
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
            statement.execute("create table queue_items"
                    + " (id int primary key, name varchar(100) not null)");
            statement.execute("insert into queue_items (id, name) values (1, 'Alpha'),"
                    + " (2, 'Beta'), (3, 'Gamma'), (4, 'Delta'), (5, 'Echo')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-snapshot-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: snapshot-app
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        writeQueue(home, "queue", 10);
        writeQueue(home, "capped", 3);
        return home;
    }

    /** A snapshot-paginated queue page over the same table, with the given cap. */
    private static void writeQueue(Path home, String name, int cap) throws IOException {
        Path dir = home.resolve("web/" + name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("get.yml"), """
                version: tesseraql/v1
                id: %s.page
                kind: route
                recipe: query-html
                pagination: { strategy: snapshot, size: 2, cap: %d }
                sources:
                  main:
                    sql:
                      file: queue.sql
                      mode: query
                      params:
                        keys: params.keys
                response:
                  html:
                    view: %s
                """.formatted(name, cap, name));
        Files.writeString(dir.resolve("queue.sql"), """
                select t.id, t.name
                from queue_items t
                where 1 = 1
                /*%if keys != null */
                  and t.id in /* keys */(1)
                /*%end*/
                order by t.id
                ;
                """);
        Files.writeString(dir.resolve("list.view.yml"), """
                version: tesseraql/v1
                id: %s
                kind: view
                recipe: list
                key: id
                title: Queue
                columns:
                  - { name: id, label: "#" }
                  - { name: name }
                """.formatted(name));
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
