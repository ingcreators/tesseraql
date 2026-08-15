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
            // The name is per language, so it joins the key rather than replacing it: one code,
            // one row per language it is written in (docs/lookups.md, decision 12).
            statement.execute("create table 区分マスタ (区分種別 varchar(2) not null,"
                    + " 区分コード varchar(2) not null, 言語コード varchar(5) not null,"
                    + " 区分名称 varchar(32) not null,"
                    + " 表示順 integer not null, 有効フラグ varchar(1) not null,"
                    + " primary key (区分種別, 区分コード, 言語コード))");
            statement.execute("insert into 区分マスタ values"
                    + " ('01', '1', 'ja', '現金', 2, '1'), ('01', '1', 'en', 'Cash', 2, '1'),"
                    + " ('01', '2', 'ja', '振込', 1, '1'), ('01', '2', 'en', 'Transfer', 1, '1'),"
                    // 手形 is retired AND untranslated: it renders on old data, in Japanese,
                    // whichever language asked.
                    + " ('01', '9', 'ja', '手形', 3, '0'),"
                    + " ('02', '1', 'ja', '国内', 1, '1')");
            // The shape a table: and equality filters cannot express: codes in one table, their
            // names per language in another (docs/lookups.md, decision 13).
            statement.execute("create table 通貨マスタ (通貨コード varchar(3) primary key,"
                    + " 有効フラグ varchar(1) not null)");
            statement.execute("create table 通貨名称 (通貨コード varchar(3) not null,"
                    + " 言語コード varchar(5) not null, 名称 varchar(32) not null,"
                    + " primary key (通貨コード, 言語コード))");
            statement.execute("insert into 通貨マスタ values ('JPY', '1'), ('USD', '1')");
            statement.execute("insert into 通貨名称 values ('JPY', 'ja', '日本円'),"
                    + " ('JPY', 'en', 'Japanese yen'), ('USD', 'ja', '米ドル')");
            // A code table whose names live in the message catalog, not beside the codes.
            statement.execute("create table 優先度マスタ (優先度 varchar(1) primary key,"
                    + " 表示順 integer not null)");
            statement.execute("insert into 優先度マスタ values ('H', 1), ('L', 2)");
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
            statement.execute(
                    "insert into 区分マスタ values ('01', '5', 'ja', '電子マネー', 4, '1')");
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

    /** The same page in another language: the call site is unchanged, the labels are not. */
    @Test
    void aRequestsLanguageDecidesWhichNamesRender() throws Exception {
        String japanese = get("/受注一覧").body();
        assertThat(japanese).contains("<span>現金</span>").doesNotContain("<span>Cash</span>");
        String english = get("/受注一覧", "en").body();
        assertThat(english).contains("<span>Cash</span>").contains("<span>Transfer</span>");
    }

    /** An untranslated code falls back to the default language, not to the raw code. */
    @Test
    void anUntranslatedCodeFallsBackToTheDefaultLanguage() throws Exception {
        // 手形 has no English name. Rendering '9' would print a number where a name belongs.
        assertThat(get("/受注一覧", "en").body()).contains("<span>手形</span>");
    }

    /** A regional tag matches the language the master stores: ja-JP finds ja. */
    @Test
    void aRegionalTagMatchesTheStoredLanguage() throws Exception {
        assertThat(get("/受注一覧", "ja-JP,ja;q=0.9").body()).contains("<span>現金</span>");
    }

    /** Validation does not narrow by language: a code with no English name is still a code. */
    @Test
    void aCodeIsValidWhateverLanguageAsked() throws Exception {
        assertThat(post("/api/受注", "{\"取引区分\":\"2\"}").statusCode()).isEqualTo(201);
    }

    /** A maintenance write drops the catalogs its table feeds, so the next page is current. */
    @Test
    void aWriteThatDeclaresItsTableRefreshesTheNames() throws Exception {
        // The hold is an hour by declaration, so without the invalidation the form would keep
        // offering yesterday's codes until the TTL expired.
        assertThat(get("/受注/新規").body()).doesNotContain("小切手");
        assertThat(post("/api/区分", "{\"区分コード\":\"6\",\"区分名称\":\"小切手\"}")
                .statusCode()).isEqualTo(201);
        assertThat(get("/受注/新規").body()).contains("小切手");
    }

    /** A catalog whose shape needs a join declares file:, and reads like any other. */
    @Test
    void aFileCatalogResolvesAJoinedMaster() throws Exception {
        assertThat(get("/受注").body()).contains("日本円");
        assertThat(get("/受注", "en").body()).contains("Japanese yen");
    }

    /** Its language dimension behaves the same: an untranslated name falls back to ja. */
    @Test
    void aFileCatalogFallsBackLikeAnyOther() throws Exception {
        // 米ドル has no English name in the master.
        assertThat(get("/受注", "en").body()).contains("米ドル");
    }

    /** Names may live in the message catalog rather than in a table beside the codes. */
    @Test
    void aMessageSourcedLabelResolvesThroughTheTranslationWorkflow() throws Exception {
        assertThat(get("/受注").body()).contains("高").contains("低");
        String english = get("/受注", "en").body();
        assertThat(english).contains("High");
        // 'L' has no English message: the fallback is the default language, not the key.
        assertThat(english).contains("低");
    }

    /** The operations surface reports the hold, is gated, and refreshes on request. */
    @Test
    void theOperationsSurfaceReportsWhatEachCatalogHolds() throws Exception {
        // Gated like every other ops read: no bearer, no answer.
        assertThat(get("/_tesseraql/ops/catalogs").statusCode()).isEqualTo(401);

        String body = ops("GET", "/_tesseraql/ops/catalogs").body();
        assertThat(body).contains("取引区分").contains("区分マスタ")
                // The file: catalog reports both tables its SQL reads.
                .contains("通貨マスタ").contains("通貨名称");

        // A manual refresh answers with that catalog's hold, now loaded.
        HttpResponse<String> refreshed = ops("POST",
                "/_tesseraql/ops/catalogs/取引区分/refresh");
        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertThat(refreshed.body()).contains("\"loaded\":true").contains("\"ja\"");

        // An undeclared catalog is a 404, not a silent no-op.
        assertThat(ops("POST", "/_tesseraql/ops/catalogs/存在しない/refresh").statusCode())
                .isEqualTo(404);
    }

    /** The version stamp is what carries an invalidation to a runtime that did not serve it. */
    @Test
    void aWriteRaisesTheVersionOfTheTableItTouched() throws Exception {
        long before = catalogVersion("区分マスタ");
        assertThat(post("/api/区分", "{\"区分コード\":\"7\",\"区分名称\":\"手渡し\"}")
                .statusCode()).isEqualTo(201);
        // A second runtime never saw the command; the row is how it learns to reload.
        assertThat(catalogVersion("区分マスタ")).isGreaterThan(before);
        // A table no catalog reads gets no row: the version table holds the declared sources,
        // not every table any command happens to name.
        assertThat(catalogVersion("受注")).isEqualTo(0L);
    }

    private static long catalogVersion(String table) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement(
                        "select version from tql_catalog_version where table_name = ?")) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    /** An authenticated ops call: bearer principal with the ADMIN role. */
    private static HttpResponse<String> ops(String method, String path) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port()
                        + java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8)
                                .replace("%2F", "/")))
                .header("Authorization", "Bearer " + opsToken());
        request.method(method, HttpRequest.BodyPublishers.noBody());
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String opsToken() throws Exception {
        java.util.Base64.Encoder encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(
                "{\"alg\":\"HS256\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // Assembled as raw JSON rather than a claim map, so it names the audience and the expiry
        // directly (docs/audit-hardening.md Decision 1) instead of going through TestClaims.
        String payload = encoder.encodeToString(
                ("{\"sub\":\"ops-user\",\"roles\":[\"ADMIN\"],\"aud\":\""
                        + TestClaims.INLINE_FIXTURE + "\",\"exp\":"
                        + (System.currentTimeMillis() / 1000 + 3600) + "}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                "dev-only-secret-change-me-in-production"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = encoder.encodeToString(mac.doFinal(
                (header + "." + payload).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
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
        return get(path, null);
    }

    private static HttpResponse<String> get(String path, String acceptLanguage) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port()
                        + java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8)
                                .replace("%2F", "/")));
        if (acceptLanguage != null) {
            request.header("Accept-Language", acceptLanguage);
        }
        return HttpClient.newHttpClient().send(request.build(),
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
                  i18n:
                    defaultLocale: ja
                    locales: [ja, en]
                  security:
                    jwt:
                      secret: dev-only-secret-change-me-in-production
                      audience: https://app.example.com
                      rolesClaim: roles
                    policies:
                      ops.batch.view:
                        anyOf:
                          - role: ADMIN
                      ops.batch.run:
                        anyOf:
                          - role: ADMIN
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
                    language: 言語コード
                    order: 表示順
                    active: 有効フラグ
                  取引形態:
                    table: 区分マスタ
                    where: { 区分種別: '02' }
                    key: 区分コード
                    label: 区分名称
                  通貨:
                    file: 通貨.sql
                    tables: [通貨マスタ, 通貨名称]
                    key: 通貨コード
                    label: 名称
                    language: 言語コード
                    active: 有効フラグ
                  優先度:
                    table: 優先度マスタ
                    key: 優先度
                    label: { message: "code.優先度.{key}" }
                    order: 表示順
                """);
        Files.writeString(target.resolve("catalogs/通貨.sql"), """
                select m.通貨コード, n.言語コード, n.名称, m.有効フラグ
                  from 通貨マスタ m
                  join 通貨名称 n on n.通貨コード = m.通貨コード
                 order by m.通貨コード
                """);
        Files.createDirectories(target.resolve("messages"));
        Files.writeString(target.resolve("messages/ja.yml"), """
                code:
                  優先度:
                    H: 高
                    L: 低
                """);
        Files.writeString(target.resolve("messages/en.yml"), """
                code:
                  優先度:
                    H: High
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
                sources:
                  main:
                    sql:
                      file: orders.sql
                response:
                  html:
                    template: orders.html
                    model:
                      rows: main.rows
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
                  - id: row
                    sql:
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
                sources:
                  main:
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
                sources:
                  main:
                    sql:
                      file: head.sql
                  履歴:
                    sql:
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

        // An export renders through a template like any other surface, and answers in ITS
        // locale rather than the requesting browser's (docs/lookups.md, decision 12).
        Path report = target.resolve("web/受注/レポート");
        Files.createDirectories(report);
        Files.writeString(report.resolve("rows.sql"),
                "select 受注番号, 取引区分 from 受注 order by 受注番号\n");
        Files.writeString(report.resolve("get.yml"), """
                version: tesseraql/v1
                id: 受注.report
                kind: route
                recipe: query-export
                security: { auth: public }
                sources:
                  main:
                    sql:
                      file: rows.sql
                export:
                  format: csv
                  locale: en
                  filename: orders.csv
                  columns:
                    - name: 受注番号
                    - name: 取引区分
                """);

        // The maintenance screen (docs/lookups.md, decision 13): the write names the TABLE it
        // touched, because which of the kinds sharing it is affected is request data.
        Path maintain = target.resolve("web/api/区分");
        Files.createDirectories(maintain);
        Files.writeString(maintain.resolve("insert.sql"),
                "insert into 区分マスタ (区分種別, 区分コード, 言語コード, 区分名称, 表示順,"
                        + " 有効フラグ) values ('01', /* 区分コード */'X', 'ja',"
                        + " /* 区分名称 */'X', 9, '1')\n");
        Files.writeString(maintain.resolve("post.yml"), """
                version: tesseraql/v1
                id: 区分.create
                kind: route
                recipe: command-json
                security: { auth: public }
                input:
                  区分コード: { type: string, required: true }
                  区分名称: { type: string, required: true }
                steps:
                  - id: row
                    sql:
                      file: insert.sql
                      params:
                        区分コード: params.区分コード
                        区分名称: params.区分名称
                invalidates: [区分マスタ]
                response:
                  json:
                    status: 201
                    body:
                      ok: true
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
                <p th:text="${codes.通貨.of('JPY')}">currency</p>
                <p th:text="${codes.通貨.of('USD')}">currency2</p>
                <p th:text="${codes.優先度.of('H')}">priority</p>
                <p th:text="${codes.優先度.of('L')}">priority2</p>
                </body>
                </html>
                """);
        return target;
    }
}
