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
 * each page fetches live state for its slice only, a vanished row renders as a tombstone.
 * Over-cap follows the result-cap contract (docs/hc-recipe-alignment.md): a search over the
 * declared cap is a user state — HTTP 200 with the reject block rendered in-page — while a
 * page fetch posting more keys than the cap is a broken client and gets 422. A warn-mode
 * maxRows truncation on a plain list renders the persistent banner and the hedged count.
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
    void aSearchOverTheCapRendersTheRejectBlockInPage() throws Exception {
        HttpResponse<String> capped = get("/capped");

        // Over-cap is a user state, not an error: the list page still renders (search chrome
        // included, so the user can narrow), the table gives way to the reject block, and
        // the count is withheld.
        assertThat(capped.statusCode()).isEqualTo(200);
        assertThat(capped.body()).contains("data-hc-result-cap")
                .contains("More than 3 rows match.")
                .doesNotContain(">Alpha<")
                .doesNotContain("name=\"page\"");
    }

    @Test
    @Order(5)
    void aPageFetchPostingMoreKeysThanTheCapIsRefusedWith422() throws Exception {
        HttpResponse<String> flooded = postForm("/capped", ALL_KEYS + "&page=1");

        // Five keys over a cap of 3: the framework never rendered that many, so the posted
        // membership can only come from a broken or hostile client.
        assertThat(flooded.statusCode()).isEqualTo(422);
    }

    @Test
    @Order(6)
    void aWarnTruncationRendersThePersistentBannerAndTheHedgedCount() throws Exception {
        HttpResponse<String> truncated = get("/truncated");

        assertThat(truncated.statusCode()).isEqualTo(200);
        // Mode A keeps the rows it has and says so: the warning banner names the shown count
        // and the sort, and the status line hedges the total as "cap+".
        // Gamma was deleted by the tombstone test above, so the first three are now
        // Alpha, Beta, Delta — Echo is the row the truncation cuts.
        assertThat(truncated.body()).contains("data-hc-result-cap")
                .contains("Showing the first 3 rows.")
                .contains("3+ results")
                .contains(">Alpha<")
                .doesNotContain(">Echo<");
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
        writeTruncated(home);
        return home;
    }

    /** A plain (non-snapshot) list whose warn-mode maxRows truncates the five seeded rows. */
    private static void writeTruncated(Path home) throws IOException {
        Path dir = home.resolve("web/truncated");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("get.yml"), """
                version: tesseraql/v1
                id: truncated.page
                kind: route
                recipe: query-html
                sources:
                  main:
                    sql:
                      file: items.sql
                      mode: query
                      materialize:
                        maxRows: 3
                        onOverflow: warn
                response:
                  html:
                    view: truncated
                """);
        Files.writeString(dir.resolve("items.sql"), """
                select t.id, t.name
                from queue_items t
                order by t.id
                ;
                """);
        Files.writeString(dir.resolve("list.view.yml"), """
                version: tesseraql/v1
                id: truncated
                kind: view
                recipe: list
                title: Items
                columns:
                  - { name: id, label: "#" }
                  - { name: name }
                """);
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
