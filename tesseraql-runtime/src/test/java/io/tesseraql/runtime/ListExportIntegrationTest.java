package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
 * The list surface's "export this filtered set" end to end (docs/list-export.md): a grid page
 * declaring {@code exports:} renders a link for its {@code query-export} and a kick-off button
 * for its {@code file-export}, each carrying the search, the filter and the sort the page shows
 * — never the page window — and the file either one answers holds every matching row in the
 * sorted order, not the twenty on screen. A browser's plain form post lands on the transfer's
 * page; a scripted caller keeps the JSON 202.
 */
@Testcontainers
class ListExportIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** The question the page shows: open tickets about the VPN, newest first. */
    private static final String QUESTION = "q=vpn&status=open&sort=-created_at";

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
    void theControlsCarryTheListsQuestionAndNotItsPageWindow() throws Exception {
        HttpResponse<String> page = get("/tickets?" + QUESTION + "&page=2&size=20", "text/html");

        assertThat(page.statusCode()).isEqualTo(200);
        String html = page.body();
        // Decision 2: the declared inputs the request bound, in declaration order; no page, no
        // size. Decision 3: the exact count on a counted page names itself.
        assertThat(html)
                .contains("href=\"/tickets/export?q=vpn&amp;status=open&amp;sort=-created_at\"")
                .contains(
                        "formaction=\"/tickets/export-async?q=vpn&amp;status=open&amp;sort=-created_at\"")
                .contains(">Export 30 rows</a>").contains(">Export 30 rows</button>")
                .doesNotContain("export?q=vpn&amp;status=open&amp;sort=-created_at&amp;")
                .contains("21–30 of 30");
        // The kick-off form precedes the grid form and carries the framework's fields only.
        assertThat(html).contains("<form id=\"tickets-export\" method=\"post\">")
                .contains("name=\"_idempotency\"").contains("form=\"tickets-export\"");
        assertThat(html.indexOf("id=\"tickets-export\""))
                .isLessThan(html.indexOf("class=\"tql-list-page__form\""));
    }

    /**
     * What an in-place search or sort swaps in refreshes the chrome it left behind
     * (docs/list-export.md, "Filed, not fixed" → closed): the htmx answer to the question
     * carries the dialog's carried state and the condition bar with the question's own
     * values, under the ids the section's {@code hx-select-oob} names, so Apply and the
     * chips' remove links agree with the address bar the swap replaced.
     */
    @Test
    void anInPlaceSwapCarriesTheDialogsStateAndTheChipsForTheQuestionItAnswers()
            throws Exception {
        HttpResponse<String> region = get("/tickets?q=printer&status=open&sort=-subject",
                "text/html", true);

        assertThat(region.statusCode()).as(region.body()).isEqualTo(200);
        String html = region.body();
        assertThat(html)
                .contains("hx-select-oob=\"#tickets-filters-state,#tickets-filterbar\"")
                .containsSubsequence("id=\"tickets-filters-state\"",
                        "name=\"sort\" value=\"-subject\"",
                        "name=\"q\" value=\"printer\"")
                .containsSubsequence("id=\"tickets-filterbar\"", "hc-filterbar__remove",
                        "href=\"/tickets?sort=-subject&amp;q=printer\"")
                .containsSubsequence("id=\"tickets-search\"", "hx-replace-url=\"true\"")
                .containsSubsequence("data-col=\"subject\"", "hx-push-url=\"true\"");
    }

    @Test
    void theLinkDownloadsEveryMatchingRowInTheSortedOrder() throws Exception {
        HttpResponse<String> csv = get("/tickets/export?" + QUESTION, "*/*");

        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("content-type").orElse("")).contains("text/csv");
        assertThat(csv.headers().firstValue("content-disposition").orElse(""))
                .contains("tickets.csv");
        assertRows(csv.body());
    }

    @Test
    void aBrowsersKickoffLandsOnTheTransferPageAndTheFileHoldsTheSameRows() throws Exception {
        // Decision 4: the plain form post — HTML wanted, no htmx — is post/redirect/get to the
        // transfer's own page; the file the transfer produces is the question's whole answer.
        HttpResponse<String> kickoff = postForm("/tickets/export-async?" + QUESTION,
                "_idempotency=k-1", "text/html,application/xhtml+xml,*/*;q=0.8");

        assertThat(kickoff.statusCode()).isEqualTo(303);
        String location = kickoff.headers().firstValue("location").orElse("");
        assertThat(location).startsWith("/tickets/export-async/");
        JsonNode status = awaitTerminal(location);
        assertThat(status.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(status.get("rowCount").asLong()).isEqualTo(30);

        HttpResponse<String> card = get(location, "text/html");
        assertThat(card.statusCode()).isEqualTo(200);
        assertThat(card.body()).contains("data-hc-job").contains("data-state=\"done\"")
                .contains("<html");

        HttpResponse<String> file = get(location + "/file", "*/*");
        assertThat(file.statusCode()).isEqualTo(200);
        assertRows(file.body());
    }

    @Test
    void anHtmxKickoffAnswersTheRunningCardWhichStopsWhenTheRunDoes() throws Exception {
        // Decision 4: 202 and the card the status poll will answer with; decision 5: a terminal
        // card carries no trigger, and an export's done state is the file.
        HttpResponse<String> kickoff = postHtmx("/tickets/export-async?" + QUESTION,
                "_idempotency=k-htmx");

        assertThat(kickoff.statusCode()).isEqualTo(202);
        assertThat(kickoff.headers().firstValue("content-type").orElse(""))
                .contains("text/html");
        String card = kickoff.body();
        assertThat(card).contains("data-hc-job").contains("hx-target=\"this\"")
                .contains("hx-swap=\"outerHTML\"").doesNotContain("<html");
        String statusPath = pollTarget(card);
        assertThat(statusPath).startsWith("/tickets/export-async/");
        String done = awaitTerminalCard(statusPath);
        assertThat(done).contains("data-state=\"done\"").doesNotContain("hx-trigger=")
                .contains(statusPath + "/file\"");
        assertRows(get(statusPath + "/file", "*/*").body());
    }

    @Test
    void theJobRegionSitsOutsideTheGridFormAndTheSwappedRegion() throws Exception {
        String html = get("/tickets?" + QUESTION, "text/html").body();

        assertThat(html).contains("<div id=\"tickets-export-job\"></div>")
                .contains(
                        "hx-post=\"/tickets/export-async?q=vpn&amp;status=open&amp;sort=-created_at\"")
                .contains("hx-params=\"_csrf,_idempotency\"")
                .contains("hx-target=\"#tickets-export-job\"");
        // Closed where it opens, before the kick-off form, the grid form and the table region:
        // nothing the pager swaps can contain it.
        int region = html.indexOf("id=\"tickets-export-job\"");
        assertThat(region).isLessThan(html.indexOf("id=\"tickets-export\" method"))
                .isLessThan(html.indexOf("class=\"tql-list-page__form\""))
                .isLessThan(html.indexOf("id=\"tickets-table\""));
    }

    @Test
    void aReclaimedExportIsExpiredToTheCardAndTheStatus() throws Exception {
        // Decision 5: after the retention sweep reclaims the file, the card says expired with no
        // trigger and no Download, the status says expired, and the file leg has no file to
        // answer with (the not-ready 409, as for a run that never produced one).
        HttpResponse<String> kickoff = postForm("/tickets/export-async?" + QUESTION, "",
                "application/json");
        String statusPath = MAPPER.readTree(kickoff.body()).get("statusUrl").asString();
        assertThat(awaitTerminal(statusPath).get("status").asString()).isEqualTo("COMPLETED");
        String transferId = statusPath.substring(statusPath.lastIndexOf('/') + 1);
        reclaimSpool(transferId);

        String card = get(statusPath, "text/html", true).body();
        assertThat(card).contains("data-state=\"expired\"").doesNotContain("hx-trigger=")
                .doesNotContain("/file\"").contains("The file is no longer available.");
        JsonNode status = MAPPER.readTree(get(statusPath, "application/json").body());
        assertThat(status.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(status.get("expired").asBoolean()).isTrue();
        assertThat(get(statusPath + "/file", "*/*").statusCode()).isEqualTo(409);
    }

    @Test
    void aReplayedKeyStartsOneTransferWhereTheRouteDeclaresIdempotency() throws Exception {
        // Decision 10: the same _idempotency on a route with idempotency: replays the 202 and the
        // same card; a route without it starts a transfer per post.
        String first = pollTarget(postHtmx("/tickets/export-idem?" + QUESTION,
                "_idempotency=k-replay").body());
        String second = pollTarget(postHtmx("/tickets/export-idem?" + QUESTION,
                "_idempotency=k-replay").body());
        assertThat(second).isEqualTo(first);

        String third = pollTarget(postHtmx("/tickets/export-async?" + QUESTION,
                "_idempotency=k-plain").body());
        String fourth = pollTarget(postHtmx("/tickets/export-async?" + QUESTION,
                "_idempotency=k-plain").body());
        assertThat(fourth).isNotEqualTo(third);
    }

    @Test
    void aScriptedKickoffKeepsTheJson202() throws Exception {
        HttpResponse<String> kickoff = postForm("/tickets/export-async?" + QUESTION, "",
                "application/json");

        assertThat(kickoff.statusCode()).isEqualTo(202);
        JsonNode body = MAPPER.readTree(kickoff.body());
        assertThat(body.get("statusUrl").asString())
                .isEqualTo("/tickets/export-async/" + body.get("transferId").asString());
        assertThat(awaitTerminal(body.get("statusUrl").asString()).get("rowCount").asLong())
                .isEqualTo(30);
    }

    /** Thirty open VPN tickets, newest first: ids 30 down to 1, and nothing else. */
    private static void assertRows(String csv) {
        String[] lines = csv.strip().split("\r?\n");
        assertThat(lines).hasSize(31);
        assertThat(lines[0]).startsWith("id,subject,status,created_at");
        assertThat(lines[1]).startsWith("30,");
        assertThat(lines[30]).startsWith("1,");
        assertThat(csv).doesNotContain("closed").doesNotContain("Printer");
    }

    private static HttpResponse<String> get(String path, String accept) throws Exception {
        return get(path, accept, false);
    }

    private static HttpResponse<String> get(String path, String accept, boolean htmx)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Accept", accept);
        if (htmx) {
            request.header("HX-Request", "true");
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** An htmx kick-off: the form's fields in the body, the question in the URL. */
    private static HttpResponse<String> postHtmx(String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("HX-Request", "true")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The status URL a running card polls: its {@code hx-get}. */
    private static String pollTarget(String card) {
        java.util.regex.Matcher poll = java.util.regex.Pattern
                .compile("hx-get=\"([^\"]+)\"").matcher(card);
        assertThat(poll.find()).as("a running card carries hx-get: %s", card).isTrue();
        return poll.group(1);
    }

    /** Polls the card until it carries no trigger, and returns that terminal card. */
    private static String awaitTerminalCard(String statusPath) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (true) {
            String card = get(statusPath, "text/html", true).body();
            if (!card.contains("hx-trigger=")) {
                return card;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Card did not settle: " + card);
            }
            Thread.sleep(100);
        }
    }

    /** What the retention sweep leaves: the row, without its spool. */
    private static void reclaimSpool(String transferId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("update tql_file_transfer set spool_uri = null where transfer_id = '"
                    + transferId + "'");
        }
    }

    private static HttpResponse<String> postForm(String path, String body, String accept)
            throws Exception {
        return HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode awaitTerminal(String statusPath) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (true) {
            JsonNode status = MAPPER.readTree(get(statusPath, "application/json").body());
            String value = status.get("status").asString();
            if (!"RUNNING".equals(value) && !"STARTED".equals(value)) {
                return status;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Transfer did not finish: " + status);
            }
            Thread.sleep(100);
        }
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table tickets (id int primary key,"
                    + " subject varchar(100) not null, status varchar(20) not null,"
                    + " created_at timestamp not null)");
            // Thirty open VPN tickets (the question's answer, more than one page), ten open
            // tickets about something else, five closed VPN tickets.
            for (int id = 1; id <= 30; id++) {
                statement.execute("insert into tickets values (" + id + ", 'VPN drops #" + id
                        + "', 'open', timestamp '2026-01-01' + interval '" + id + " hours')");
            }
            for (int id = 31; id <= 40; id++) {
                statement.execute("insert into tickets values (" + id + ", 'Printer jam #" + id
                        + "', 'open', timestamp '2026-01-01' + interval '" + id + " hours')");
            }
            for (int id = 41; id <= 45; id++) {
                statement.execute("insert into tickets values (" + id + ", 'VPN fixed #" + id
                        + "', 'closed', timestamp '2026-01-01' + interval '" + id + " hours')");
            }
        }
    }

    private static final String INPUTS = """
            input:
              q: { type: string, required: false, maxLength: 100 }
              status: { type: string, required: false, maxLength: 20 }
              sort:
                type: sort
                columns: [subject, status, created_at]
                default: "-created_at"
              dir: { type: string, required: false, enum: [asc, desc] }
            """;

    /** Indented for a {@code sources.main.sql} block whose {@code file:} sits at six spaces. */
    private static final String PARAMS = """
                  params:
                    q: query.q
                    status: query.status
                    sort: params.sortSql
            """;

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-list-export-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: list-export-app
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path list = home.resolve("web/tickets");
        Files.createDirectories(list);
        Files.writeString(list.resolve("get.yml"), """
                version: tesseraql/v1
                id: tickets.page
                kind: route
                recipe: query-html
                %s
                pagination: { size: 20, count: true }
                sources:
                  main:
                    sql:
                      file: tickets.sql
                      mode: query
                %s
                response:
                  html:
                    view: tickets
                """.formatted(INPUTS.stripTrailing(), PARAMS.stripTrailing()));
        Files.writeString(list.resolve("tickets.sql"), """
                select id, subject, status, created_at
                from tickets
                where 1 = 1
                /*%if q */
                  and lower(subject) like lower('%' || /* q */ 'vpn' || '%')
                /*%end*/
                /*%if status */
                  and status = /* status */ 'open'
                /*%end*/
                /*# order by {sort} */
                ;
                """);
        Files.writeString(list.resolve("list.view.yml"), """
                version: tesseraql/v1
                id: tickets
                kind: view
                recipe: list
                key: id
                title: Tickets
                search: q
                filters: [status]
                exports: [/tickets/export, /tickets/export-async]
                columns:
                  - { name: id, label: "#" }
                  - { name: subject, sortable: true }
                  - { name: status }
                  - { name: created_at, sortable: true }
                """);
        Path sync = home.resolve("web/tickets/export");
        Files.createDirectories(sync);
        Files.writeString(sync.resolve("get.yml"), """
                version: tesseraql/v1
                id: tickets.export
                kind: route
                recipe: query-export
                %s
                export:
                  format: csv
                  filename: tickets.csv
                sources:
                  main:
                    sql:
                      file: ../tickets.sql
                %s
                """.formatted(INPUTS.stripTrailing(), PARAMS.stripTrailing()));
        Path async = home.resolve("web/tickets/export-async");
        Files.createDirectories(async);
        Files.writeString(async.resolve("post.yml"), """
                version: tesseraql/v1
                id: tickets.exportAsync
                kind: route
                recipe: file-export
                %s
                export:
                  format: csv
                  filename: tickets.csv
                sources:
                  main:
                    sql:
                      file: ../tickets.sql
                %s
                """.formatted(INPUTS.stripTrailing(), PARAMS.stripTrailing()));
        // The same export with a declared idempotency: (docs/list-export.md decision 10).
        Path idempotent = home.resolve("web/tickets/export-idem");
        Files.createDirectories(idempotent);
        Files.writeString(idempotent.resolve("post.yml"), """
                version: tesseraql/v1
                id: tickets.exportIdempotent
                kind: route
                recipe: file-export
                idempotency:
                  required: false
                %s
                export:
                  format: csv
                  filename: tickets.csv
                sources:
                  main:
                    sql:
                      file: ../tickets.sql
                %s
                """.formatted(INPUTS.stripTrailing(), PARAMS.stripTrailing()));
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
