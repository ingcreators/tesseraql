package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A binding's {@code result:} declaration, end to end (docs/temporal-semantics.md T3): a
 * {@code jsonb} column declared {@code type: json} is a structure on JSON and navigable in a
 * template, its text is compact JSON where a surface prints it, a SQL NULL is {@code null};
 * a date a legacy column stores as {@code yyyy/MM/dd} text — the format only its domain
 * declares — is {@code 2026-01-15} on JSON; a number stored as {@code 1,234.50} is a number;
 * text that will not parse is the coded error naming the column and the row; a declared column
 * the query never produces is not a failure. The same on a command step's rows.
 *
 * <p>Before T3 every one of these read as the driver's text: the structure was the string
 * {@code "{\"sku\": \"A-1\", \"qty\": 2}"}, the date the string {@code "2026/01/15"}, the
 * number the string {@code "1,234.50"}, and a template's {@code row.payload.sku} was null.
 */
@Testcontainers
class ResultDeclarationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            try (Stream<Path> files = Files.walk(appHome)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /** The mapper writes the parsed value as a structure, and a SQL NULL as null. */
    @Test
    void aJsonbColumnDeclaredJsonIsAStructureOnJsonAndNullIsNull() throws Exception {
        JsonNode rows = data(get("/api/docs"));

        JsonNode payload = rows.get(0).get("payload");
        assertThat(payload.isObject()).as("a structure, not text: " + payload).isTrue();
        assertThat(payload.get("sku").asText()).isEqualTo("A-1");
        assertThat(payload.get("qty").asInt()).isEqualTo(2);
        assertThat(payload.get("price").decimalValue()).isEqualByComparingTo("1.10");
        // A text column holding JSON is the same case as a jsonb column.
        assertThat(rows.get(0).get("payload_text").get("sku").asText()).isEqualTo("B-2");
        assertThat(rows.get(1).get("payload").isNull()).isTrue();
        assertThat(rows.get(1).get("payload_text").isNull()).isTrue();
    }

    /**
     * The other half of the JSON guard (docs/temporal-semantics.md, the green-on-defect traps):
     * a plain map would satisfy the mapper and print {@code {sku=A-1}} on every surface that
     * prints a value. A template navigates the value and prints it.
     */
    @Test
    void aTemplateNavigatesTheJsonValueAndPrintsItAsJson() throws Exception {
        HttpResponse<String> response = get("/docs");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body())
                .contains("<td class=\"sku\">A-1</td>")
                // jsonb reorders keys (shortest first); the text column keeps the author's.
                .contains("<td class=\"text\">{&quot;sku&quot;:&quot;B-2&quot;}</td>")
                .contains("&quot;sku&quot;:&quot;A-1&quot;")
                .contains("&quot;price&quot;:1.10")
                .doesNotContain("sku=");
    }

    /**
     * The date's format lives on the domain alone — the route declares {@code domain:} and
     * nothing else — so a green here is compile-time resolution end to end; and the value is
     * the wire text, never a {@code java.time} object the mapper has no module for.
     */
    @Test
    void aTextDateDeclaredThroughItsDomainIsTheCanonicalDateOnJson() throws Exception {
        JsonNode rows = data(get("/api/docs"));

        JsonNode orderedOn = rows.get(0).get("ordered_on");
        assertThat(orderedOn.isTextual()).isTrue();
        assertThat(orderedOn.asText()).isEqualTo("2026-01-15");
        assertThat(rows.get(1).get("ordered_on").isNull()).isTrue();
    }

    @Test
    void aTextNumberInItsDeclaredPatternIsANumberOnJson() throws Exception {
        JsonNode amount = data(get("/api/docs")).get(0).get("amount");

        assertThat(amount.isNumber()).as("a number, not text: " + amount).isTrue();
        assertThat(amount.decimalValue()).isEqualByComparingTo("1234.50");
    }

    @Test
    void textThatWillNotParseIsTheCodedErrorNamingTheColumnAndTheRow() throws Exception {
        HttpResponse<String> response = get("/api/broken");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(500);
        // A 5xx body carries the code and the structured details, never the message (the
        // error renderer's confidentiality rule); the message is the log's.
        JsonNode error = MAPPER.readTree(response.body()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("TQL-SQL-2503");
        assertThat(error.get("details").get("source").asText()).isEqualTo("main");
        assertThat(error.get("details").get("column").asText()).isEqualTo("payload");
        assertThat(error.get("details").get("row").asInt()).isEqualTo(1);
        assertThat(error.get("details").get("kind").asText()).isEqualTo("json");
    }

    /** A declared column the query never produced is logged, never a 500. */
    @Test
    void aDeclaredColumnTheQueryNeverProducesIsNotAFailure() throws Exception {
        HttpResponse<String> response = get("/api/absent");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(data(response).get(0).get("id").asInt()).isEqualTo(1);
    }

    /** A command step's rows get the same declaration before they publish. */
    @Test
    void aCommandStepsRowsAreDeclaredTheSameWay() throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port()
                        + "/api/docs/mark"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode rows = MAPPER.readTree(response.body()).get("read");
        assertThat(rows.get(0).get("payload").get("sku").asText()).isEqualTo("A-1");
        assertThat(rows.get(0).get("ordered_on").asText()).isEqualTo("2026-01-15");
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body()).get("data");
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-result-declaration-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: result-declaration
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__docs.sql"), """
                create table docs (id integer primary key, payload jsonb, payload_text text,
                    ordered_on text, amount text);
                insert into docs values (1, '{"sku": "A-1", "qty": 2, "price": 1.10}',
                    '{"sku": "B-2"}', '2026/01/15', '1,234.50');
                insert into docs values (2, null, null, null, null);
                create table broken (id integer primary key, payload text);
                insert into broken values (1, '{"ok": true}');
                insert into broken values (2, '{not json');
                """);
        Files.createDirectories(home.resolve("domains"));
        Files.writeString(home.resolve("domains/fields.yml"), """
                version: tesseraql/v1
                domains:
                  order_date:
                    type: date
                    format: yyyy/MM/dd
                """);
        String declared = """
                    result:
                      payload: { type: json }
                      payload_text: { type: json }
                      ordered_on: { domain: order_date }
                      amount: { type: number, format: "#,##0.00" }
                """;
        query(home, "web/api/docs", "docs.json", "select * from docs order by id\n", declared,
                """
                        response:
                          json:
                            body:
                              data: main.rows
                        """);
        query(home, "web/api/broken", "docs.broken", "select * from broken order by id\n",
                "    result:\n      payload: { type: json }\n", """
                        response:
                          json:
                            body:
                              data: main.rows
                        """);
        query(home, "web/api/absent", "docs.absent", "select id from docs order by id\n",
                "    result:\n      nope: { type: json }\n", """
                        response:
                          json:
                            body:
                              data: main.rows
                        """);
        query(home, "web/docs", "docs.page", "select * from docs where id = 1\n", declared, """
                response:
                  html:
                    template: docs.html
                    model:
                      rows: main.rows
                """);
        Files.createDirectories(home.resolve("templates"));
        Files.writeString(home.resolve("templates/docs.html"), """
                <!doctype html>
                <html xmlns:th="http://www.thymeleaf.org">
                <body>
                <table>
                  <tr th:each="row : ${rows}">
                    <td class="sku" th:text="${row.payload.sku}">sku</td>
                    <td class="payload" th:text="${row.payload}">payload</td>
                    <td class="text" th:text="${row.payload_text}">text</td>
                  </tr>
                </table>
                </body>
                </html>
                """);
        Path mark = home.resolve("web/api/docs/mark");
        Files.createDirectories(mark);
        Files.writeString(mark.resolve("post.yml"), """
                version: tesseraql/v1
                id: docs.mark
                kind: route
                recipe: command-json
                security:
                  auth: public
                steps:
                  - id: read
                    sql:
                      file: read.sql
                      mode: query
                    result:
                      payload: { type: json }
                      ordered_on: { domain: order_date }
                  - id: touch
                    sql:
                      file: touch.sql
                response:
                  json:
                    body:
                      read: steps.read.rows
                """);
        Files.writeString(mark.resolve("read.sql"),
                "select payload, ordered_on from docs where id = 1\n");
        Files.writeString(mark.resolve("touch.sql"), "update docs set id = id where id = 1\n");
        return home;
    }

    private static void query(Path home, String dir, String id, String sql, String result,
            String response) throws IOException {
        Path route = home.resolve(dir);
        Files.createDirectories(route);
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: %s
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: query.sql
                      mode: query
                %s%s""".formatted(id, response.contains("html:") ? "query-html" : "query-json",
                result, response));
        Files.writeString(route.resolve("query.sql"), sql);
    }
}
