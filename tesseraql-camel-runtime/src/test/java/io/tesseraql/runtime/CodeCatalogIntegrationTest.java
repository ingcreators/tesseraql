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
            statement.execute("insert into 受注 values ('J-1001', '1'), ('J-1002', '9')");
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
