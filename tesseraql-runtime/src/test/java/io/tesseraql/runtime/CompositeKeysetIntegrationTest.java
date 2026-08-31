package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Composite keyset pagination end to end (docs/list-surface.md decision 5): {@code by:} as an
 * ordered list mints one opaque row token as the next cursor, the framework decodes an
 * incoming {@code ?after=} into {@code params.after.<column>} parts, the authored tuple
 * predicate pages on them — and the walk stays stable while rows are inserted before the
 * cursor.
 */
@Testcontainers
class CompositeKeysetIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    void aCompositeCursorWalksThePagesAndSurvivesInserts() throws Exception {
        JsonNode first = page(null);
        assertThat(labels(first)).containsExactly("A", "B");
        String cursor = first.path("page").path("next").asText();
        // base64url("1") = MQ, base64url("2") = Mg — the token joins parts with a dot.
        assertThat(cursor).isEqualTo("MQ.Mg");

        JsonNode second = page(cursor);
        assertThat(labels(second)).containsExactly("C");

        // Insert a row BEFORE the cursor: the same cursor still yields the same page —
        // keyset membership is insert-stable where offset paging would shift.
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("insert into lines (order_id, line_no, label)"
                    + " values (1, 0, 'A0')");
        }
        assertThat(labels(page(cursor))).containsExactly("C");
    }

    @Test
    void aMalformedCursorIsRefusedAsAnInputError() throws Exception {
        HttpResponse<String> response = get("/api/lines?after=" + URLEncoder.encode(
                "not|a|token", StandardCharsets.UTF_8));
        // The same input-error shape a bad ?page= answers (TQL-FIELD-2001).
        assertThat(response.statusCode()).isEqualTo(400);
    }

    private static JsonNode page(String after) throws Exception {
        HttpResponse<String> response = get("/api/lines"
                + (after == null
                        ? ""
                        : "?after=" + URLEncoder.encode(after,
                                StandardCharsets.UTF_8)));
        assertThat(response.statusCode()).as(response::body).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private static java.util.List<String> labels(JsonNode body) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        body.path("data").forEach(row -> labels.add(row.path("label").asText()));
        return labels;
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table lines (
                      order_id integer not null,
                      line_no integer not null,
                      label varchar(100) not null,
                      primary key (order_id, line_no))""");
            statement.execute("insert into lines (order_id, line_no, label) values"
                    + " (1, 1, 'A'), (1, 2, 'B'), (2, 1, 'C')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-composite-keyset-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: composite-keyset-app
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path route = home.resolve("web/api/lines");
        Files.createDirectories(route);
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: lines.page
                kind: route
                recipe: query-json
                pagination: { strategy: keyset, by: [order_id, line_no], size: 2 }
                sources:
                  main:
                    sql:
                      file: lines.sql
                      mode: query
                      params:
                        after_order_id: params.after.order_id
                        after_line_no: params.after.line_no
                response:
                  json:
                    body:
                      data: main.rows
                      page: page
                """);
        Files.writeString(route.resolve("lines.sql"), """
                select t.order_id, t.line_no, t.label
                from lines t
                where 1 = 1
                /*%if after_order_id != null */
                  and (t.order_id, t.line_no) > (/* after_order_id */ 0, /* after_line_no */ 0)
                /*%end*/
                order by t.order_id, t.line_no
                ;
                """);
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
