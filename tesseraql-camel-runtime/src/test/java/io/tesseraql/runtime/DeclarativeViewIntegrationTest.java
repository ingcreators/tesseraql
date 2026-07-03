package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Declarative views end to end (roadmap Phase 39, docs/declarative-views.md): a served
 * {@code response.html.view} route renders live rows through the {@code tql/view/list} pattern,
 * a form view derives its fields from the action route's {@code input:} block, and the example
 * gallery's view-backed board page compiles and mounts under its browser security.
 */
@Testcontainers
class DeclarativeViewIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
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
    void aListViewRendersLiveRowsAsADatagrid() throws Exception {
        HttpResponse<String> response = get("/board");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("hc-datagrid__table");
        // The seeded user renders, its column linked per row from the view's link template.
        assertThat(response.body()).contains("href=\"/users?sel=sato\"").contains(">sato</a>");
        assertThat(response.body()).contains(">Status</span>");
    }

    @Test
    void aFormViewDerivesItsFieldsFromTheActionRoute() throws Exception {
        HttpResponse<String> response = get("/board/new");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("hx-post=\"/board/create\"");
        // The rendered constraints are the action route's input: declarations.
        assertThat(response.body()).contains("name=\"name\"").contains("required")
                .contains("maxlength=\"200\"");
        assertThat(response.body()).contains(">Save</button>");
    }

    @Test
    void aDetailViewComposesParentChildrenAndSlot() throws Exception {
        HttpResponse<String> response = get("/board/sato");
        assertThat(response.statusCode()).isEqualTo(200);
        // Labelled values over the parent row.
        assertThat(response.body()).contains(">Name</span>").contains(">sato</span>");
        assertThat(response.body()).contains(">Status</span>").contains(">ACTIVE</span>");
        // The named `groups` query composes as a child table.
        assertThat(response.body()).contains(">Groups</h3>").contains(">engineers</span>");
        // The header slot pulled the app fragment in.
        assertThat(response.body()).contains("href=\"/users\"");
    }

    @Test
    void aDashboardRendersItsPanelsOverLiveData() throws Exception {
        HttpResponse<String> response = get("/board/stats");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("class=\"hc-grid\"");
        // The stat counts the seeded user; the chart is server-rendered SVG in the kit skin.
        assertThat(response.body()).contains(">3</strong>");
        assertThat(response.body()).contains("<figure class=\"hc-chart\">")
                .contains("hc-chart__plot").contains("var(--hc-chart-series-1)");
        assertThat(response.body()).contains("class=\"hc-sparkline\"")
                .contains("data-values=\"1\"");
        assertThat(response.body()).contains(">engineers</span>");
    }

    @Test
    void theGalleryStatsDashboardMountsUnderItsBrowserSecurity() throws Exception {
        assertThat(get("/users/board/stats").statusCode()).isEqualTo(401);
    }

    @Test
    void theGalleryBoardDetailMountsUnderItsBrowserSecurity() throws Exception {
        HttpResponse<String> response = get("/users/board/sato");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void theGalleryBoardPageMountsUnderItsBrowserSecurity() throws Exception {
        // The example's view-backed page (web/users/board) compiled and mounted, and stays
        // gated: an unauthenticated call is refused rather than rendered.
        HttpResponse<String> response = get("/users/board");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void theInputVocabularyGatesRequestsEndToEnd() throws Exception {
        // A malformed pattern value is a field-scoped rejection before any SQL runs.
        assertThat(postForm("/board/probe", "code=ab-1").statusCode()).isEqualTo(400);
        // kind=noted makes note required (requiredWhen); absent -> rejected, present -> 200.
        assertThat(postForm("/board/probe", "code=AB-1&kind=noted").statusCode())
                .isEqualTo(400);
        assertThat(postForm("/board/probe", "code=AB-1&kind=noted&note=x").statusCode())
                .isEqualTo(200);
        assertThat(postForm("/board/probe", "code=AB-1").statusCode()).isEqualTo(200);
    }

    private static HttpResponse<String> postForm(String path, String form) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-view-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        // A public list view over the seeded users table.
        Path board = target.resolve("web/board");
        Files.createDirectories(board);
        Files.writeString(board.resolve("get.yml"), """
                version: tesseraql/v1
                id: board.page
                kind: route
                recipe: query-html
                security:
                  auth: public
                sql:
                  file: board.sql
                response:
                  html:
                    view: board.view.yml
                """);
        Files.writeString(board.resolve("board.sql"),
                "select u.name, u.status from users u order by u.id\n");
        Files.writeString(board.resolve("board.view.yml"), """
                version: tesseraql/v1
                kind: view
                view: list
                title: Board
                columns:
                  - name: name
                    label: Name
                    link: /users?sel={name}
                  - name: status
                    label: Status
                """);

        // A public detail view composing the parent row, a named-query child, and a slot.
        Path boardDetail = target.resolve("web/board/{name}");
        Files.createDirectories(boardDetail);
        Files.writeString(boardDetail.resolve("get.yml"), """
                version: tesseraql/v1
                id: board.detail
                kind: route
                recipe: query-html
                security:
                  auth: public
                sql:
                  file: detail.sql
                  params:
                    name: path.name
                queries:
                  groups:
                    file: groups.sql
                response:
                  html:
                    view: detail.view.yml
                """);
        Files.writeString(boardDetail.resolve("detail.sql"),
                "select u.name, u.status from users u where u.name = /* name */ 'sato'\n");
        Files.writeString(boardDetail.resolve("groups.sql"),
                "select g.display_name from app_groups g order by g.display_name\n");
        Files.writeString(boardDetail.resolve("detail.view.yml"), """
                version: tesseraql/v1
                kind: view
                view: detail
                title: User
                fields:
                  - name: name
                    label: Name
                  - name: status
                    label: Status
                children:
                  - source: groups
                    title: Groups
                    columns:
                      - name: display_name
                        label: Group
                slots:
                  header: board-frags.html::backLink
                """);

        // A public probe for the Phase 40 input vocabulary: pattern + conditional requiredness.
        Path validate = target.resolve("web/board/probe");
        Files.createDirectories(validate);
        Files.writeString(validate.resolve("post.yml"), """
                version: tesseraql/v1
                id: board.probe
                kind: route
                recipe: command-json
                security:
                  auth: public
                input:
                  code:
                    type: string
                    required: true
                    pattern: "[A-Z]{2}-[0-9]+"
                  kind:
                    type: string
                    required: false
                    enum: [plain, noted]
                  note:
                    type: string
                    required: false
                    requiredWhen: params.kind == 'noted'
                sql:
                  file: probe.sql
                  mode: query
                  params:
                    code: params.code
                response:
                  json:
                    status: 200
                    body:
                      echo: sql.rows
                """);
        Files.writeString(validate.resolve("probe.sql"),
                "select /* code */ 'AB-1' as code\n");

        // A public dashboard view: stat + chart + sparkline + table panels over the seed data.
        Path boardStats = target.resolve("web/board/stats");
        Files.createDirectories(boardStats);
        Files.writeString(boardStats.resolve("get.yml"), """
                version: tesseraql/v1
                id: board.stats
                kind: route
                recipe: query-html
                security:
                  auth: public
                sql:
                  file: totals.sql
                queries:
                  groups:
                    file: group-names.sql
                response:
                  html:
                    view: stats.view.yml
                """);
        Files.writeString(boardStats.resolve("totals.sql"),
                "select count(*) as user_count from users\n");
        Files.writeString(boardStats.resolve("group-names.sql"),
                "select g.display_name, 1 as n from app_groups g order by g.display_name\n");
        Files.writeString(boardStats.resolve("stats.view.yml"), """
                version: tesseraql/v1
                kind: view
                view: dashboard
                title: Stats
                panels:
                  - title: Users
                    type: stat
                    column: user_count
                  - title: Groups
                    type: chart
                    source: groups
                    x: display_name
                    y: n
                  - title: Trend
                    type: sparkline
                    source: groups
                    column: n
                  - title: Names
                    type: table
                    source: groups
                """);

        // A public form view deriving its fields from the POST action route's input: block.
        Path boardNew = target.resolve("web/board/new");
        Files.createDirectories(boardNew);
        Files.writeString(boardNew.resolve("get.yml"), """
                version: tesseraql/v1
                id: board.new
                kind: route
                recipe: page
                security:
                  auth: public
                response:
                  html:
                    view: new.view.yml
                """);
        Files.writeString(boardNew.resolve("new.view.yml"), """
                version: tesseraql/v1
                kind: view
                view: form
                title: New entry
                action: /board/create
                """);
        Path boardCreate = target.resolve("web/board/create");
        Files.createDirectories(boardCreate);
        Files.writeString(boardCreate.resolve("post.yml"), """
                version: tesseraql/v1
                id: board.create
                kind: route
                recipe: command-json
                security:
                  auth: public
                input:
                  name:
                    type: string
                    required: true
                    maxLength: 200
                sql:
                  file: touch.sql
                  mode: update
                  params:
                    name: params.name
                response:
                  json:
                    status: 200
                    body:
                      updated: sql.rowCount
                """);
        Files.writeString(boardCreate.resolve("touch.sql"),
                "update users set status = status where name = /* name */ 'x'\n");
        return target;
    }

    private static void copy(Path source, Path target, Path path) {
        try {
            Path destination = target.resolve(source.relativize(path).toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
            } else {
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
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
