package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import io.tesseraql.yaml.scaffold.CrudScaffolder;
import io.tesseraql.yaml.scaffold.ScaffoldedFile;
import io.tesseraql.yaml.scaffold.TableIntrospector;
import io.tesseraql.yaml.scaffold.TableSchema;
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
import java.util.List;
import java.util.Map;
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
 * A composite-key table scaffolds and serves the full CRUD round trip over HTTP
 * (docs/list-surface.md decision 4): introspection reads the two-column key, the generated
 * routes nest one path segment per key column, the grid-page list carries composite row
 * anchors, and update/delete address the row by the whole key.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CompositeCrudIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String cookie;
    static String csrf;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
        SessionStore sessions = runtime.context().lookup(TesseraqlProperties.SESSION_STORE_BEAN,
                SessionStore.class);
        String sid = sessions.create(new Principal("u001", "sato", "Sato", null,
                List.of(), List.of("APP_READ", "APP_WRITE"), List.of(), Map.of()),
                SessionStore.ClientInfo.NONE);
        cookie = sessions.cookieName() + "=" + sid;
        csrf = sessions.csrfToken(sid);
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
    void theListCarriesCompositeRowAnchorsAndNestedOpenLinks() throws Exception {
        HttpResponse<String> list = get("/order_lines");

        assertThat(list.statusCode()).isEqualTo(200);
        // base64url("1") = MQ, base64url("2") = Mg — the composite token joins with a dot.
        assertThat(list.body()).contains("id=\"row-MQ.Mg\"");
        assertThat(list.body()).contains("/order_lines/1/2");
        // The grid page's row links carry the return target (docs/list-surface.md decision 11).
        assertThat(list.body()).contains("_return=");
    }

    @Test
    @Order(2)
    void theDetailAddressesTheRowByTheWholeKey() throws Exception {
        HttpResponse<String> detail = get("/order_lines/1/2");

        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("action=\"/order_lines/1/2/update\"")
                .contains("value=\"Beta\"");
    }

    @Test
    @Order(3)
    void anUpdateBindsEveryKeyColumnAndRedirectsToTheDetail() throws Exception {
        HttpResponse<String> updated = postForm("/order_lines/1/2/update", Map.of(
                "order_id", "1", "line_no", "2", "label", "Renamed"));

        assertThat(updated.statusCode()).as(updated::body).isEqualTo(303);
        assertThat(updated.headers().firstValue("Location")).contains("/order_lines/1/2");
        assertThat(get("/order_lines/1/2").body()).contains("value=\"Renamed\"");
    }

    @Test
    @Order(4)
    void aDeleteRemovesExactlyTheAddressedRow() throws Exception {
        HttpResponse<String> deleted = postForm("/order_lines/1/2/delete", Map.of());

        assertThat(deleted.statusCode()).as(deleted::body).isEqualTo(303);
        String list = get("/order_lines").body();
        assertThat(list).doesNotContain("id=\"row-MQ.Mg\"").contains("id=\"row-MQ.MQ\"");
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + path))
                        .header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postForm(String path, Map<String, String> fields)
            throws Exception {
        StringBuilder body = new StringBuilder("_csrf=")
                .append(URLEncoder.encode(csrf, StandardCharsets.UTF_8));
        fields.forEach((name, value) -> body.append('&').append(name).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table order_lines (
                      order_id integer not null,
                      line_no integer not null,
                      label varchar(100) not null,
                      primary key (order_id, line_no))""");
            statement.execute("insert into order_lines (order_id, line_no, label) values"
                    + " (1, 1, 'Alpha'), (1, 2, 'Beta')");
        }
    }

    private static Path prepareAppHome() throws Exception {
        Path home = Files.createTempDirectory("tesseraql-composite-crud-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: composite-crud-app
                  security:
                    policies:
                      app.read:
                        anyOf:
                          - role: APP_READ
                      app.write:
                        anyOf:
                          - role: APP_WRITE
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        TableSchema schema;
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            schema = new TableIntrospector().introspect(connection, "order_lines");
        }
        for (ScaffoldedFile file : new CrudScaffolder().scaffold(schema)) {
            Path target = home.resolve(file.path());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content());
        }
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
