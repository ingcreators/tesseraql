package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The bundled "My exports" page (docs/job-inbox.md decisions 4, 5 and 6): the signed-in user's
 * exports of this application as the job card per row, each polling the route's own subtree;
 * another user's page holds none of them; a transfer whose route is no longer declared renders
 * without links; the account menu links the page where an export can start; and under a base
 * path every URL a card carries is the wire URL the route's subtree answers at.
 */
@Testcontainers
class ExportsPageIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static TesseraqlRuntime runtime;
    static Path appHome;
    static Session userA;
    static Session userB;

    /** One signed-in user: the cookie the shell reads and the token its forms carry. */
    record Session(String cookie, String csrf) {
    }

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome("exports-demo", null);
        runtime = TesseraqlRuntime.start(appHome, 0);
        execute("insert into tickets (id, subject) values ('T-1', 'vpn'), ('T-2', 'printer')");
        userA = signIn(runtime, "user-a");
        userB = signIn(runtime, "user-b");
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
    void thePageListsTheCallersOwnExportsAsCardsAndNobodyElses() throws Exception {
        String transferId = startExport(runtime, "/tickets/export", userA);
        awaitTerminal(runtime, "/tickets/export/" + transferId, userA);

        HttpResponse<String> page = get(runtime, "/_tesseraql/exports", userA);
        assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
        String html = page.body();
        // The shipped card, polling the route's own subtree — the URLs the route serves.
        assertThat(html)
                .contains("data-hc-job")
                .contains("id=\"tql-job-" + transferId + "\"")
                .contains("hx-get=\"/tickets/export/" + transferId + "\"")
                // Done: the Download is the route's file leg, and the card carries no trigger.
                .contains("href=\"/tickets/export/" + transferId + "/file\"")
                .contains("data-state=\"done\"")
                .doesNotContain("hx-trigger=")
                // The heading line the card does not carry.
                .contains("tickets.csv")
                .contains("tickets.export")
                .contains("started ");
        // The account menu links the page: this application declares an export route.
        assertThat(html).contains("href=\"/_tesseraql/exports\"").contains("My exports");

        // Whose it is decides what the page lists: B's page holds no card and says so.
        HttpResponse<String> other = get(runtime, "/_tesseraql/exports", userB);
        assertThat(other.statusCode()).isEqualTo(200);
        assertThat(other.body())
                .doesNotContain("tql-job-")
                .contains("Nothing exported yet.");
    }

    @Test
    void aTransferWhoseRouteIsGoneRendersWithoutLinks() throws Exception {
        execute("insert into tql_file_transfer (transfer_id, route_id, app_name, direction,"
                + " format, filename, row_count, created_at, subject) values ('gone-1',"
                + " 'tickets.retired', 'exports-demo', 'EXPORT', 'csv', 'old.csv', 3,"
                + " current_timestamp, 'user-a')");

        String html = get(runtime, "/_tesseraql/exports", userA).body();
        assertThat(html)
                .contains("old.csv")
                .contains("tickets.retired")
                .contains("The route that produced this export is no longer declared.")
                .doesNotContain("tql-job-gone-1")
                .doesNotContain("/gone-1");
    }

    /**
     * Under a base path the cards carry the prefixed URLs (the base path as a request
     * expression, decision 5): a card's poll, cancel and download are wire URLs by
     * construction, so the prefix has to be there — unprefixed they were 404s through the
     * gateway on every stack deployment.
     */
    @Test
    void underABasePathTheCardsCarryPrefixedUrls() throws Exception {
        Path prefixedHome = prepareAppHome("exports-prefixed", "/erp");
        TesseraqlRuntime prefixed = TesseraqlRuntime.start(prefixedHome, 0);
        try {
            Session user = signIn(prefixed, "user-p");
            String transferId = startExport(prefixed, "/erp/tickets/export", user);
            awaitTerminal(prefixed, "/erp/tickets/export/" + transferId, user);

            HttpResponse<String> page = get(prefixed, "/erp/_tesseraql/exports", user);
            assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
            assertThat(page.body())
                    .contains("hx-get=\"/erp/tickets/export/" + transferId + "\"")
                    .contains("href=\"/erp/tickets/export/" + transferId + "/file\"")
                    .contains("href=\"/erp/_tesseraql/exports\"")
                    .doesNotContain("hx-get=\"/tickets/export/");
        } finally {
            prefixed.close();
            deleteRecursively(prefixedHome);
        }
    }

    /**
     * The grid page's job region remembers (docs/job-inbox.md decision 8): the caller's
     * exports of this list's route that still need them are rendered at page render, so a
     * return to the page — or a snapshot page turn's whole-document POST — finds the card the
     * htmx kick-off swapped in; a second kick-off joins the first instead of replacing it; a
     * fetched file leaves the region and stays on the page that lists everything.
     */
    @Test
    void theGridPagesJobRegionRemembersTheCallersPendingExports() throws Exception {
        Session user = signIn(runtime, "user-r");
        String first = startExportFromThePage(runtime, "/tickets/export", user);
        awaitTerminal(runtime, "/tickets/export/" + first, user);

        // Back on the page: the region holds the card, built from the store, not from a swap.
        String page = get(runtime, "/tickets", user).body();
        assertThat(page)
                .contains("id=\"tickets-export-job\"")
                .contains("id=\"tql-job-" + first + "\"")
                .contains("hx-get=\"/tickets/export/" + first + "\"")
                .contains("href=\"/tickets/export/" + first + "/file\"")
                // The kick-off adds its card above the ones the region holds.
                .contains("hx-swap=\"afterbegin\"")
                .doesNotContain("hx-swap=\"innerHTML\"");

        // A second export joins the first.
        String second = startExportFromThePage(runtime, "/tickets/export", user);
        awaitTerminal(runtime, "/tickets/export/" + second, user);
        String both = get(runtime, "/tickets", user).body();
        assertThat(both)
                .contains("id=\"tql-job-" + second + "\"")
                .contains("id=\"tql-job-" + first + "\"");
        // Newest first: the second card precedes the first in the region.
        assertThat(both.indexOf("tql-job-" + second)).isLessThan(both.indexOf("tql-job-" + first));

        // Somebody else's grid page holds none of them: the region is theirs alone.
        assertThat(get(runtime, "/tickets", userB).body()).doesNotContain("tql-job-");

        // Fetched, dealt with: the region lets the file go; the exports page still lists it.
        assertThat(HTTP.send(HttpRequest.newBuilder(uri(runtime, "/tickets/export/" + first
                + "/file")).header("Cookie", user.cookie()).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        String after = get(runtime, "/tickets", user).body();
        assertThat(after)
                .doesNotContain("tql-job-" + first)
                .contains("tql-job-" + second);
        assertThat(get(runtime, "/_tesseraql/exports", user).body())
                .contains("tql-job-" + first)
                .contains("tql-job-" + second);
    }

    /** The htmx kick-off from the grid page: the card comes back, 202, into the region. */
    private static String startExportFromThePage(TesseraqlRuntime target, String path,
            Session session) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(uri(target, path))
                .header("Cookie", session.cookie())
                .header("X-CSRF-Token", session.csrf())
                .header("HX-Request", "true")
                .header("Accept", "text/html")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + session.csrf()))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        assertThat(response.body()).contains("data-hc-job");
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("id=\"tql-job-([^\"]+)\"")
                .matcher(response.body());
        assertThat(id.find()).as("the card's transfer id").isTrue();
        return id.group(1);
    }

    private static Session signIn(TesseraqlRuntime target, String subject) {
        SessionStore sessions = target.context().lookup(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal(subject, subject, subject, null, List.of(),
                List.of("USER"), List.of(), Map.of()), SessionStore.ClientInfo.NONE);
        return new Session(sessions.cookieName() + "=" + sid, sessions.session(sid).csrfToken());
    }

    private static String startExport(TesseraqlRuntime target, String path, Session session)
            throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(uri(target, path))
                .header("Cookie", session.cookie())
                .header("X-CSRF-Token", session.csrf())
                .header("Accept", "application/json")
                .header("Content-Type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return MAPPER.readTree(response.body()).get("transferId").asString();
    }

    private static JsonNode awaitTerminal(TesseraqlRuntime target, String statusPath,
            Session session) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (true) {
            JsonNode status = MAPPER.readTree(HTTP.send(HttpRequest.newBuilder(
                    uri(target, statusPath))
                    .header("Cookie", session.cookie())
                    .header("Accept", "application/json")
                    .build(), HttpResponse.BodyHandlers.ofString()).body());
            if (!"RUNNING".equals(status.get("status").asString())) {
                return status;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Transfer did not finish: " + status);
            }
            Thread.sleep(100);
        }
    }

    private static HttpResponse<String> get(TesseraqlRuntime target, String path,
            Session session) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(uri(target, path))
                .header("Cookie", session.cookie())
                .header("Accept", "text/html")
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(TesseraqlRuntime target, String path) {
        return URI.create("http://localhost:" + target.port() + path);
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    /**
     * A browser-session application with one file-export route over a tickets table; the
     * second boot serves under {@code basePath} on the same database (the table is shared, the
     * transfers are recorded per application name).
     */
    private static Path prepareAppHome(String appName, String basePath) throws IOException {
        Path home = Files.createTempDirectory("tesseraql-exports-page-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: %s
                  http:
                    basePath: "%s"
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
                """.formatted(appName, basePath == null ? "" : basePath, POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__tables.sql"), """
                create table if not exists tickets (id varchar(32) primary key,
                  subject varchar(200));
                """);
        Path route = home.resolve("web/tickets/export");
        Files.createDirectories(route);
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: tickets.export
                kind: route
                recipe: file-export
                export:
                  format: csv
                  filename: tickets.csv
                sources:
                  main:
                    sql:
                      file: tickets.sql
                """);
        Files.writeString(route.resolve("tickets.sql"),
                "select id, subject from tickets order by id\n;\n");
        // The grid page that starts the export (docs/job-inbox.md decision 8): its job region
        // is filled at render with the caller's pending exports of the routes it names.
        Path list = home.resolve("web/tickets");
        Files.writeString(list.resolve("get.yml"), """
                version: tesseraql/v1
                id: tickets.page
                kind: route
                recipe: query-html
                pagination: { size: 20, count: true }
                sources:
                  main:
                    sql:
                      file: list.sql
                      mode: query
                response:
                  html:
                    view: tickets
                """);
        Files.writeString(list.resolve("list.sql"),
                "select id, subject from tickets order by id\n;\n");
        Files.writeString(list.resolve("list.view.yml"), """
                version: tesseraql/v1
                id: tickets
                kind: view
                recipe: list
                key: id
                title: Tickets
                exports: [/tickets/export]
                columns:
                  - { name: id, label: "#" }
                  - { name: subject }
                """);
        return home;
    }
}
