package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Code catalogs end to end (docs/lookups.md, decisions 8-11): twenty kinds of code sharing one
 * table, resolved from memory in a template with no query per request — the case an
 * {@code enrich:} block would answer twenty times over.
 */
@Testcontainers
class CodeCatalogIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            // The general code master: many kinds in one table, keyed by a type column.
            statement.execute("create table 区分マスタ (区分種別 varchar(2) not null,"
                    + " 区分コード varchar(2) not null, 区分名称 varchar(32) not null,"
                    + " 表示順 integer not null, 有効フラグ varchar(1) not null,"
                    + " primary key (区分種別, 区分コード))");
            statement.execute("insert into 区分マスタ values"
                    + " ('01', '1', '現金', 2, '1'), ('01', '2', '振込', 1, '1'),"
                    + " ('01', '9', '手形', 3, '0'), ('02', '1', '国内', 1, '1')");
            statement.execute("create table 受注 (受注番号 varchar(8) primary key,"
                    + " 取引区分 varchar(2) not null)");
            // '8' is in no catalog: the master is incomplete, which a screen has to survive.
            statement.execute("insert into 受注 values ('J-1001', '1'), ('J-1002', '9'),"
                    + " ('J-1003', '8')");
        }
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            try (var files = Files.walk(appHome)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            }
        }
    }

    /** A template resolves the name behind a code with no query of its own. */
    @Test
    void aTemplateResolvesTheNameBehindACode() throws Exception {
        HttpResponse<String> response = get("/受注");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("J-1001").contains("現金");
    }

    /** A retired code still renders: labels resolve over every row, not the active subset. */
    @Test
    void aRetiredCodeStillRendersOnOldData() throws Exception {
        assertThat(get("/受注").body()).contains("J-1002").contains("手形");
    }

    /** The type column is fixed by the catalog, so one table serves many single-keyed catalogs. */
    @Test
    void oneTableServesSeveralCatalogsThroughItsTypeColumn() throws Exception {
        String body = get("/受注").body();
        // '1' means 現金 in 取引区分 and 国内 in 取引形態; the catalogs do not collide.
        assertThat(body).contains("国内");
    }

    /** A domain's codes: reference accepts an active code and refuses anything else. */
    @Test
    void aFieldDeclaringCodesAcceptsOnlyActiveCodes() throws Exception {
        assertThat(post("/api/受注", "{\"取引区分\":\"1\"}").statusCode()).isEqualTo(201);
        // '9' exists but is retired: it renders on old rows and is refused on new ones. The
        // refusal is an input-contract violation (400), the same shape an enum violation takes.
        HttpResponse<String> retired = post("/api/受注", "{\"取引区分\":\"9\"}");
        assertThat(retired.statusCode()).isEqualTo(400);
        assertThat(retired.body()).contains("取引区分");
        // '7' is not a code at all.
        assertThat(post("/api/受注", "{\"取引区分\":\"7\"}").statusCode()).isEqualTo(400);
    }

    /** A code added after the hold is accepted: a miss re-reads the source before refusing. */
    @Test
    void aCodeAddedAfterTheLoadIsNotRefused() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("insert into 区分マスタ values ('01', '5', '電子マネー', 4, '1')");
        }
        // The hold is an hour old by declaration; without the recheck this would be a 400.
        assertThat(post("/api/受注", "{\"取引区分\":\"5\"}").statusCode()).isEqualTo(201);
    }

    /** A form's select offers the catalog's active codes, in the catalog's order. */
    @Test
    void aFormOffersTheCatalogsActiveCodesInOrder() throws Exception {
        String html = get("/受注/新規").body();
        // 表示順 puts 振込 (2) before 現金 (1); 手形 (9) is retired and is not offered.
        assertThat(html).contains("<option value=\"2\"").contains("振込");
        assertThat(html.indexOf("振込")).isLessThan(html.indexOf("現金"));
        assertThat(html).doesNotContain("手形");
    }

    /** A list column's domain: resolves the name, without the page owning a template. */
    @Test
    void aListColumnResolvesItsCodeThroughTheDomainsCatalog() throws Exception {
        String html = get("/受注一覧").body();
        // The cell carries the name, not the code — the pattern renders it, not a template.
        assertThat(html).contains("<span>現金</span>").contains("<span>手形</span>");
    }

    /** A code the master does not name renders as the code — the row is not blanked. */
    @Test
    void anUnnamedCodeRendersAsItself() throws Exception {
        assertThat(get("/受注一覧").body()).contains("J-1003").contains("<span>8</span>");
    }

    /** The detail case: the head field and the history child resolve through the same object. */
    @Test
    void aDetailFieldAndItsHistoryChildBothResolve() throws Exception {
        String html = get("/受注明細").body();
        assertThat(html).contains("履歴");
        // The head field is J-1001's 現金; the child table carries every row, retired names too.
        assertThat(html).contains("<span>現金</span>").contains("<span>手形</span>");
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port()
                        + java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8)
                                .replace("%2F", "/")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port()
                        + java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8)
                                .replace("%2F", "/")))
                .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-catalog-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: catalog-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        Files.createDirectories(target.resolve("catalogs"));
        Files.writeString(target.resolve("catalogs/codes.yml"), """
                version: tesseraql/v1
                catalogs:
                  取引区分:
                    table: 区分マスタ
                    where: { 区分種別: '01' }
                    key: 区分コード
                    label: 区分名称
                    order: 表示順
                    active: 有効フラグ
                  取引形態:
                    table: 区分マスタ
                    where: { 区分種別: '02' }
                    key: 区分コード
                    label: 区分名称
                """);

        Path orders = target.resolve("web/受注");
        Files.createDirectories(orders);
        Files.writeString(orders.resolve("orders.sql"),
                "select 受注番号, 取引区分 from 受注 order by 受注番号\n");
        Files.writeString(orders.resolve("get.yml"), """
                version: tesseraql/v1
                id: 受注.list
                kind: route
                recipe: query-html
                security: { auth: public }
                sql:
                  file: orders.sql
                response:
                  html:
                    template: orders.html
                    model:
                      rows: sql.rows
                """);
        Files.createDirectories(target.resolve("domains"));
        Files.writeString(target.resolve("domains/codes.yml"), """
                version: tesseraql/v1
                domains:
                  取引区分:
                    type: string
                    maxLength: 2
                    codes: 取引区分
                """);
        Path create = target.resolve("web/api/受注");
        Files.createDirectories(create);
        Files.writeString(create.resolve("insert.sql"),
                "insert into 受注 (受注番号, 取引区分)"
                        + " values (/* 受注番号 */'X', /* 取引区分 */'1')\n");
        Files.writeString(create.resolve("post.yml"), """
                version: tesseraql/v1
                id: 受注.create
                kind: route
                recipe: command-json
                security: { auth: public }
                input:
                  取引区分: { domain: 取引区分, required: true }
                steps:
                  row:
                    file: insert.sql
                    params:
                      受注番号: params.取引区分
                      取引区分: params.取引区分
                response:
                  json:
                    status: 201
                    body:
                      ok: true
                """);

        // The declarative pair: a list whose column names a coded domain, and a detail whose
        // history child does the same — the two surfaces docs/lookups.md opens with.
        Path list = target.resolve("web/受注一覧");
        Files.createDirectories(list);
        Files.writeString(list.resolve("orders.sql"),
                "select 受注番号, 取引区分 from 受注 order by 受注番号\n");
        Files.writeString(list.resolve("get.yml"), """
                version: tesseraql/v1
                id: 受注.grid
                kind: route
                recipe: query-html
                security: { auth: public }
                sql:
                  file: orders.sql
                response:
                  html:
                    view: 受注.grid.view
                """);
        Files.writeString(list.resolve("page.view.yml"), """
                version: tesseraql/v1
                id: 受注.grid.view
                kind: view
                recipe: list
                title: 受注一覧
                columns:
                  - name: 受注番号
                  - name: 取引区分
                    domain: 取引区分
                """);

        Path detail = target.resolve("web/受注明細");
        Files.createDirectories(detail);
        Files.writeString(detail.resolve("head.sql"),
                "select 受注番号, 取引区分 from 受注 where 受注番号 = 'J-1001'\n");
        Files.writeString(detail.resolve("history.sql"),
                "select 受注番号, 取引区分 from 受注 order by 受注番号\n");
        Files.writeString(detail.resolve("get.yml"), """
                version: tesseraql/v1
                id: 受注.detail
                kind: route
                recipe: query-html
                security: { auth: public }
                sql:
                  file: head.sql
                queries:
                  履歴:
                    file: history.sql
                response:
                  html:
                    view: 受注.detail.view
                """);
        Files.writeString(detail.resolve("page.view.yml"), """
                version: tesseraql/v1
                id: 受注.detail.view
                kind: view
                recipe: detail
                title: 受注明細
                fields:
                  - name: 受注番号
                  - name: 取引区分
                    domain: 取引区分
                children:
                  - source: 履歴
                    title: 履歴
                    columns:
                      - name: 受注番号
                      - name: 取引区分
                        domain: 取引区分
                """);

        Path form = target.resolve("web/受注/新規");
        Files.createDirectories(form);
        Files.writeString(form.resolve("get.yml"), """
                version: tesseraql/v1
                id: 受注.form
                kind: route
                recipe: page
                security: { auth: public }
                input:
                  取引区分: { domain: 取引区分 }
                response:
                  html:
                    view: 受注.form.view
                """);
        Files.writeString(form.resolve("page.view.yml"), """
                version: tesseraql/v1
                id: 受注.form.view
                kind: view
                recipe: form
                title: 受注.form.title
                action: /api/受注
                """);

        Files.createDirectories(target.resolve("templates"));
        Files.writeString(target.resolve("templates/orders.html"), """
                <!doctype html>
                <html xmlns:th="http://www.thymeleaf.org">
                <body>
                <table>
                  <tr th:each="row : ${rows}">
                    <td th:text="${row.受注番号}">no</td>
                    <td th:text="${codes.取引区分.of(row.取引区分)}">name</td>
                  </tr>
                </table>
                <p th:text="${codes.取引形態.of('1')}">form</p>
                </body>
                </html>
                """);
        return target;
    }
}
