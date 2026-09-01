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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
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

/**
 * The reference-lookup field end to end (docs/reference-lookup.md slice 1): a {@code lookup:}
 * input renders as code entry plus hidden id, the synthesized resolve companion re-renders the
 * whole field — 200 resolved, 422 unresolved with the id emptied, 200 cleared — an ambiguous
 * code is not a resolution, resolution by id serves prefill and (later) the dialog's pick, the
 * companion answers under the <em>referenced</em> route's security, and the submit-time
 * existence check refuses an id the source does not carry.
 */
@Testcontainers
class LookupFieldIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static String actorCookie;
    static String actorCsrf;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
        SessionStore sessions = runtime.context().lookup(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        String sid = sessions.create(new Principal("actor-1", "actor-1", "Actor", null,
                List.of(), List.of("USER"), List.of(), Map.of()), SessionStore.ClientInfo.NONE);
        actorCookie = sessions.cookieName() + "=" + sid;
        actorCsrf = sessions.session(sid).csrfToken();
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
    void theFormRendersTheLookupField() throws Exception {
        HttpResponse<String> page = get("/orders/new", actorCookie);

        assertThat(page.statusCode()).isEqualTo(200);
        // The two-fields-one-truth markup: visible code input, hidden id, the contract marker,
        // and the resolve wiring against the synthesized companion.
        assertThat(page.body()).contains("data-hc-lookup")
                .contains("name=\"customer_code\"")
                .contains("name=\"customer_id\"")
                .contains("hx-get=\"/orders/new/_lookup/customer_id\"")
                .contains("hx-swap=\"outerHTML\"");
    }

    @Test
    void aKnownCodeResolvesTheWholeField() throws Exception {
        HttpResponse<String> field = get(
                "/orders/new/_lookup/customer_id?customer_code=C-1041", actorCookie);

        assertThat(field.statusCode()).isEqualTo(200);
        // Hint = the display name; hidden id = the key; the code echoed canonical.
        assertThat(field.body()).contains("Acme Trading K.K.")
                .contains("value=\"cus-1\"")
                .contains("value=\"C-1041\"")
                .doesNotContain("aria-invalid");
    }

    @Test
    void anUnknownCodeIsUnresolvedAndEmptiesTheId() throws Exception {
        HttpResponse<String> field = get(
                "/orders/new/_lookup/customer_id?customer_code=NOPE", actorCookie);

        assertThat(field.statusCode()).isEqualTo(422);
        // The rule's teeth: a stale id may never ride under a corrected code.
        assertThat(field.body()).contains("aria-invalid=\"true\"")
                .contains("No match for this code.")
                .contains("name=\"customer_id\" value=\"\"")
                .contains("value=\"NOPE\"");
    }

    @Test
    void anAmbiguousCodeIsNotAResolution() throws Exception {
        HttpResponse<String> field = get(
                "/orders/new/_lookup/customer_id?customer_code=C-DUP", actorCookie);

        assertThat(field.statusCode()).isEqualTo(422);
        assertThat(field.body()).contains("aria-invalid=\"true\"")
                .contains("name=\"customer_id\" value=\"\"");
    }

    @Test
    void anEmptyCodeClearsTheField() throws Exception {
        HttpResponse<String> field = get(
                "/orders/new/_lookup/customer_id?customer_code=", actorCookie);

        assertThat(field.statusCode()).isEqualTo(200);
        assertThat(field.body()).doesNotContain("aria-invalid")
                .contains("name=\"customer_id\" value=\"\"");
    }

    @Test
    void anIdResolvesThroughTheSameFragment() throws Exception {
        // The prefilled edit form and the dialog's pick both re-enter keyed by id.
        HttpResponse<String> field = get(
                "/orders/new/_lookup/customer_id?customer_id=cus-2", actorCookie);

        assertThat(field.statusCode()).isEqualTo(200);
        assertThat(field.body()).contains("Beta Industries")
                .contains("value=\"C-2000\"")
                .contains("value=\"cus-2\"");
    }

    @Test
    void theCompanionAnswersUnderTheReferencedRoutesSecurity() throws Exception {
        // vip_id's source route demands a policy this principal fails: the resolve leg must
        // never list masters the user may not reference.
        HttpResponse<String> refused = get(
                "/orders/new/_lookup/vip_id?customer_code=C-1041", actorCookie);

        assertThat(refused.statusCode()).isEqualTo(403);
    }

    @Test
    void aSubmitWithAResolvedIdSucceedsAndTheCodeInputPassesTheGuard() throws Exception {
        // The visible code rides the post beside the hidden id; the same declaration that
        // renders it lets it past the mass-assignment guard as presentation.
        HttpResponse<String> created = postForm("/orders/new",
                "customer_id=cus-1&customer_code=C-1041&note=hello&_csrf=" + actorCsrf,
                actorCookie);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(orderCount("cus-1")).isEqualTo(1);
    }

    @Test
    void aSubmitWithAnUnknownIdIsRefusedByTheExistenceCheck() throws Exception {
        HttpResponse<String> refused = postForm("/orders/new",
                "customer_id=ghost&note=hello&_csrf=" + actorCsrf, actorCookie);

        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("customer_id")
                .contains("invalid-reference");
        assertThat(orderCount("ghost")).isZero();
    }

    private static int orderCount(String customerId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                java.sql.PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from orders where customer_id = ?")) {
            statement.setString(1, customerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static HttpResponse<String> get(String path, String cookie) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + path))
                        .header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postForm(String path, String body, String cookie)
            throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table customers (customer_id varchar(32) primary key,"
                    + " customer_code varchar(32) not null, name varchar(100) not null)");
            statement.execute("insert into customers (customer_id, customer_code, name) values"
                    + " ('cus-1', 'C-1041', 'Acme Trading K.K.'),"
                    + " ('cus-2', 'C-2000', 'Beta Industries'),"
                    + " ('cus-3', 'C-DUP', 'Dup One'),"
                    + " ('cus-4', 'C-DUP', 'Dup Two')");
            statement.execute("create table orders (order_id serial primary key,"
                    + " customer_id varchar(32) not null, note varchar(200))");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-lookup-field-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: lookup-field-app
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
                    policies:
                      master.read:
                        anyOf:
                          - role: USER
                      vip.read:
                        anyOf:
                          - role: VIP
                      order.write:
                        anyOf:
                          - role: USER
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        Files.createDirectories(home.resolve("web/api/customers/search"));
        Files.writeString(home.resolve("web/api/customers/search/get.yml"), """
                version: tesseraql/v1
                id: customers.search
                kind: route
                recipe: query-json
                security:
                  policy: master.read
                input:
                  q:
                    type: string
                sources:
                  main:
                    sql:
                      file: search.sql
                      mode: query
                      params:
                        q: params.q
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.writeString(home.resolve("web/api/customers/search/search.sql"), """
                select customer_id, customer_code, name
                from customers
                where 1 = 1
                /*%if q != null && q != "" */
                  and (name like /* q */'%a%' or customer_code like /* q */'%a%')
                /*%end*/
                order by name
                """);

        Files.createDirectories(home.resolve("web/api/vip/search"));
        Files.writeString(home.resolve("web/api/vip/search/get.yml"), """
                version: tesseraql/v1
                id: vip.search
                kind: route
                recipe: query-json
                security:
                  policy: vip.read
                input:
                  q:
                    type: string
                sources:
                  main:
                    sql:
                      file: vip.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.writeString(home.resolve("web/api/vip/search/vip.sql"), """
                select customer_id, customer_code, name
                from customers
                order by name
                """);

        Files.createDirectories(home.resolve("web/orders/new"));
        Files.writeString(home.resolve("web/orders/new/get.yml"), """
                version: tesseraql/v1
                id: orders.new
                kind: route
                recipe: query-html
                security:
                  policy: order.write
                sources:
                  main:
                    sql:
                      file: none.sql
                      mode: query
                response:
                  html:
                    status: 200
                    view: orders.new.form
                """);
        Files.writeString(home.resolve("web/orders/new/none.sql"), "select 1 as ok\n");
        Files.writeString(home.resolve("web/orders/new/new.view.yml"), """
                version: tesseraql/v1
                id: orders.new.form
                kind: view
                recipe: form
                title: New order
                action: /orders/new
                """);
        Files.writeString(home.resolve("web/orders/new/post.yml"), """
                version: tesseraql/v1
                id: orders.create
                kind: route
                recipe: command-json
                security:
                  policy: order.write
                input:
                  customer_id:
                    type: string
                    required: true
                    lookup:
                      source: /api/customers/search
                      code: customer_code
                      label: name
                  vip_id:
                    type: string
                    lookup:
                      source: /api/vip/search
                      code: customer_code
                      label: name
                  note:
                    type: string
                steps:
                  - id: main
                    sql:
                      file: create.sql
                      mode: update
                      params:
                        customer_id: params.customer_id
                        note: params.note
                response:
                  json:
                    status: 201
                    body:
                      created: steps.main.affectedRows
                """);
        Files.writeString(home.resolve("web/orders/new/create.sql"), """
                insert into orders (customer_id, note)
                values (/* customer_id */'cus-x', /* note */'a note')
                """);
        return home;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(root)) {
            tree.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        }
    }
}
