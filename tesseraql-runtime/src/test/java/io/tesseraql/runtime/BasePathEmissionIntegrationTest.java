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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What an application emits when it is served under a prefix (docs/base-path-emission.md).
 *
 * <p>One application, booted as a stack member at {@code /shop} — the shape every deployment
 * has since stack-architecture decision 12, because an address is derived from the name and
 * injected by the host. The test crawls the URLs its pages emit and holds them to three
 * properties: prefixed exactly once, no form encoding in a path segment, and every emitted
 * link resolves to something mounted.
 *
 * <p>The fixture declares the surfaces the audit found broken, because
 * {@code examples/user-admin-app} declares none of them and that is precisely why
 * {@code StackModeIntegrationTest} misses every defect in this campaign.
 */
@Testcontainers
class BasePathEmissionIntegrationTest {

    private static final String PREFIX = "/shop";

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
        // A HostContext means a hosted member, and a hosted member validates the framework
        // security schema rather than migrating it — so the host's duty is discharged first.
        try (com.zaxxer.hikari.HikariDataSource migration = DataSources.create(
                "tesseraql-base-path-emission-migration",
                new DataSources.MainDatasourceOverride(POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(), POSTGRES.getPassword()))) {
            FrameworkMigrations.migrateSecurity(migration);
        }
        runtime = TesseraqlRuntime.start(appHome, 0,
                HostContext.stack().forApplication(PREFIX));
        SessionStore sessions = runtime.context().lookup(
                TesseraqlProperties.SESSION_STORE_BEAN, SessionStore.class);
        // A hosted member fences every route behind tql.app.use.<member> (AuthStep.fence) — the
        // step that is a no-op on an unhosted boot, and the reason a fixture copied from a
        // plain-boot test answers 403 on every page until the principal carries the grant.
        String sid = sessions.create(new Principal("user-1", "user-1", "User", null,
                List.of(), List.of("USER", "IMPORTER"),
                List.of("tql.app.use.base-path-emission-app"),
                Map.of()), SessionStore.ClientInfo.NONE);
        cookie = sessions.cookieName() + "=" + sid;
        csrf = sessions.session(sid).csrfToken();
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

    /**
     * Wire URLs by construction (docs/base-path-emission.md decision 2): values that were
     * already wire URLs when they were produced and acquire nothing. On a hosted member the
     * account surface and the {@code _system} links are the stack's, served once at the origin
     * scope, so they are origin-absolute on purpose — a sweep that wrapped every emission in a
     * link expression would break exactly these.
     */
    private static final List<String> WIRE_BY_CONSTRUCTION = List.of(
            "/_tesseraql/account",
            "/_tesseraql/account/pins/toggle",
            "/_tesseraql/ops/console",
            "/_tesseraql/studio",
            "/_tesseraql/admin/users");

    /**
     * Emissions that do not carry the prefix yet, each owned by the slice that deletes it.
     * The ledger only shrinks: {@link #theUnprefixedLedgerOnlyShrinks()} fails when an entry
     * stops being emitted unprefixed, so a fix cannot land without clearing its line here.
     */
    private static final List<String> KNOWN_UNPREFIXED = List.of();

    /**
     * Pinned to HTTP/1.1 on purpose. This crawl fetches what the pages link, which includes
     * multi-megabyte WebJar assets, and the JDK client loses HTTP/2 frame sync on those — it
     * reads the body as a header and reports a frame type that does not exist. The protocol is
     * not what this test is about, so it does not negotiate one.
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).build();

    /** The fixture's pages, each declaring one of the surfaces the audit found broken. */
    private static final List<String> PAGES = List.of(
            "/orders/new", "/docs/D-1", "/things?page=2",
            "/things/3/edit?_return=%2Fshop%2Fthings%3Fpage%3D2");

    /**
     * A guard over a fixture that silently renders nothing would pass while checking nothing,
     * and three of these four surfaces fail silently by design — a workflow region with no row,
     * a lookup companion with no matching form action, a list with no {@code key:}. So the
     * fixture proves it rendered before anything else asserts on what it rendered.
     */
    @Test
    void theFixtureRendersEverySurfaceItClaims() throws Exception {
        assertThat(body("/orders/new")).contains("data-hc-lookup").contains("_lookup/customer_id");
        assertThat(body("/docs/D-1")).contains("data-hc-workflow").contains("hc-stepper");
        assertThat(body("/things?page=2")).contains("/things/3/edit").contains("_return=");
        assertThat(body("/things/3/edit?_return=%2Fshop%2Fthings%3Fpage%3D2"))
                .contains("name=\"_return\"");
    }

    /**
     * The property the campaign exists for. Every root-relative URL an application page emits
     * is served under the prefix, so it carries it once — never zero times (the link builder was
     * bypassed) and never twice (a wire URL was prefixed again).
     */
    @Test
    void everyEmittedUrlCarriesThePrefixExactlyOnce() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (String page : PAGES) {
            for (String emission : emittedUrls(page)) {
                if (!checkable(emission) || KNOWN_UNPREFIXED.contains(pathOf(emission))) {
                    continue;
                }
                String url = valueOf(emission);
                if (url.startsWith(PREFIX + PREFIX + "/")) {
                    wrong.add(page + " emits " + emission + " (prefixed twice)");
                } else if (!url.equals(PREFIX) && !url.startsWith(PREFIX + "/")) {
                    wrong.add(page + " emits " + emission + " (not prefixed)");
                }
            }
        }
        assertThat(wrong).as("every emitted URL carries %s exactly once", PREFIX).isEmpty();
    }

    /**
     * A path segment is not a form field. {@code URLEncoder} writes a space as {@code +}, which
     * a path parameter does not decode back — so a {@code +} before the query string addresses
     * a different resource rather than the same one.
     */
    @Test
    void noPathSegmentCarriesFormEncoding() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (String page : PAGES) {
            for (String url : emittedUrls(page)) {
                if (checkable(url) && pathOf(url).indexOf('+') >= 0) {
                    wrong.add(page + " emits " + url);
                }
            }
        }
        assertThat(wrong).as("a '+' in a path segment is form encoding, not a path").isEmpty();
    }

    /**
     * The property that generalises, because it enumerates nothing: whatever a page links to,
     * asking for it finds something. A URL that is prefixed correctly but points at an address
     * nothing mounts fails here rather than in a browser.
     */
    @Test
    void everyEmittedLinkResolvesToAMountedRoute() throws Exception {
        List<String> missing = new ArrayList<>();
        for (String page : PAGES) {
            for (String emission : emittedUrls(page)) {
                if (!emission.startsWith("href=") && !emission.startsWith("src=")) {
                    continue;
                }
                // The origin fence is the stack's, not this member's: a single-runtime test
                // serves no /_tesseraql/account, and that 404 is the topology, not a defect.
                String target = valueOf(emission);
                if (!target.startsWith("/") || target.startsWith("//")
                        || !checkable(emission)
                        || KNOWN_UNPREFIXED.contains(pathOf(emission))) {
                    continue;
                }
                int fragment = target.indexOf('#');
                String fetch = fragment < 0 ? target : target.substring(0, fragment);
                int status = CLIENT.send(
                        HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + runtime.port() + fetch))
                                .header("Cookie", cookie).build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status == 404) {
                    missing.add(page + " links " + target + " -> 404");
                }
            }
        }
        assertThat(missing).as("a page links only what the runtime serves").isEmpty();
    }

    /**
     * The other half of "exactly once", on the way back. A {@code _return} is handed out as a
     * wire URL and posted back as one, so whatever reads it off the request must return it to
     * base-relative form before the redirect helper — the one place the prefix goes — adds the
     * prefix again. Asserting the emitted value alone would not catch this: the round trip is
     * where the second prefix appears.
     */
    @Test
    void aReturnTargetIsPrefixedOnceOnTheWayBack() throws Exception {
        HttpResponse<String> post = postForm("/things/3/update",
                "name=Gamma&_csrf=" + csrf + "&_return=%2Fshop%2Fthings%3Fpage%3D2");

        assertThat(post.statusCode()).isEqualTo(303);
        assertThat(post.headers().firstValue("Location")).hasValue(PREFIX + "/things?page=2");
    }

    /** The same rule through the workflow transition's own redirect. */
    @Test
    void aWorkflowTransitionReturnsToThePageThatSentIt() throws Exception {
        HttpResponse<String> post = postForm("/api/docs/D-1/submit",
                "_csrf=" + csrf + "&_return=%2Fshop%2Fdocs%2FD-1");

        assertThat(post.statusCode()).isEqualTo(303);
        assertThat(post.headers().firstValue("Location")).hasValue(PREFIX + "/docs/D-1");
    }

    /**
     * A custom error page is a page: it composes the same links every other page does, so it
     * needs the same {@code base} to resolve them against. Without it the page arrives unstyled
     * and every link on it points outside the application — in a stack, at another member.
     */
    @Test
    void aCustomErrorPageResolvesItsLinksAgainstTheApplication() throws Exception {
        // A mounted route that refuses its input: an unmatched path never reaches the
        // application's renderer at all, so it would prove nothing about a custom page.
        HttpResponse<String> refused = CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port()
                        + PREFIX + "/things/not-a-number/edit"))
                        .header("Cookie", cookie)
                        .header("Accept", "text/html")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("data-error-page")
                .contains(PREFIX + "/assets/_tesseraql/tesseraql.css")
                .doesNotContain("\"/assets/_tesseraql/tesseraql.css\"");
    }

    /**
     * The reviewed import's two pages. Neither is reachable without an upload, which is why they
     * arrive with the slice that fixes them rather than with the crawl: the review page is what a
     * multipart POST answers, and the job page is where its no-JS confirm redirects.
     */
    @Test
    void aReviewedImportConfirmsAndReportsUnderTheApplicationsPrefix() throws Exception {
        HttpResponse<String> review = upload("/items/import", "name,qty\nalpha,1\n");
        assertThat(review.statusCode()).as(review.body()).isEqualTo(200);

        // The confirm target is built as a wire URL and rendered through the link builder, so
        // the prefix must not appear twice.
        Matcher action = Pattern.compile("action=\"([^\"]*/commit)\"").matcher(review.body());
        assertThat(action.find()).as("the review page offers a confirm form").isTrue();
        assertThat(action.group(1)).startsWith(PREFIX + "/items/import/")
                .doesNotStartWith(PREFIX + PREFIX);

        // The no-JS confirm redirects to the job page — a full page, so it composes the shell
        // and needs the same base every other page publishes.
        Matcher token = Pattern.compile("name=\"token\"[^>]*value=\"([^\"]+)\"")
                .matcher(review.body());
        assertThat(token.find()).as("the review page carries its single-shot token").isTrue();
        // Accept: text/html is what makes this the browser's no-JS leg rather than the API's —
        // without it the confirm answers 202 with a JSON job handle and never redirects.
        HttpResponse<String> confirmed = CLIENT.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + PREFIX
                        + "/items/import/" + token.group(1) + "/commit"))
                .header("Cookie", cookie)
                .header("Accept", "text/html")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "_csrf=" + csrf + "&token=" + token.group(1)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(confirmed.statusCode()).isEqualTo(303);
        String jobPage = confirmed.headers().firstValue("Location").orElseThrow();
        assertThat(jobPage).startsWith(PREFIX + "/items/import/")
                .doesNotStartWith(PREFIX + PREFIX);

        HttpResponse<String> page = CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + jobPage))
                        .header("Cookie", cookie).header("Accept", "text/html").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains(PREFIX + "/assets/_tesseraql/tesseraql.css")
                .doesNotContain("href=\"/assets/_tesseraql/tesseraql.css\"");
    }

    /** A browser's multipart upload of {@code content} as the page's file field. */
    private static HttpResponse<String> upload(String path, String content) throws Exception {
        String boundary = "----tqlBasePathBoundary";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"_csrf\"\r\n\r\n" + csrf + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"items.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n" + content + "\r\n"
                + "--" + boundary + "--\r\n";
        return CLIENT.send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + runtime.port() + PREFIX + path))
                .header("Cookie", cookie)
                .header("X-CSRF-Token", csrf)
                .header("Accept", "text/html")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The ledger shrinks and never grows. An entry that has stopped being emitted unprefixed is
     * a fix that landed without deleting its line, which would leave the guard permanently
     * excusing a URL that is now correct.
     */
    @Test
    void theUnprefixedLedgerOnlyShrinks() throws Exception {
        List<String> stale = new ArrayList<>(KNOWN_UNPREFIXED);
        for (String page : PAGES) {
            for (String url : emittedUrls(page)) {
                stale.remove(pathOf(url));
            }
        }
        assertThat(stale).as("KNOWN_UNPREFIXED entries no longer emitted unprefixed — delete them")
                .isEmpty();
    }

    /** Whether a URL is this application's to prefix: root-relative, not a fragment, not wire. */
    private static boolean checkable(String emission) {
        String url = valueOf(emission);
        return url.startsWith("/") && !url.startsWith("//")
                && !WIRE_BY_CONSTRUCTION.contains(pathOf(emission));
    }

    /** The URL of an {@code attribute=url} pair. */
    private static String valueOf(String emission) {
        return emission.substring(emission.indexOf('=') + 1);
    }

    /** The path portion of an {@code attribute=url} pair, without query or fragment. */
    private static String pathOf(String emission) {
        String url = valueOf(emission);
        int cut = url.length();
        for (char stop : new char[]{'?', '#'}) {
            int at = url.indexOf(stop);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        return url.substring(0, cut);
    }

    /** Every URL-bearing attribute of a page, fetched once. */
    private static List<String> emittedUrls(String page) throws Exception {
        return urlsIn(body(page));
    }

    private static String body(String page) throws Exception {
        HttpResponse<String> response = get(page);
        assertThat(response.statusCode()).as("GET %s", page).isEqualTo(200);
        return response.body();
    }

    /** Every URL-bearing attribute value in a page, as {@code attribute=value}. */
    private static List<String> urlsIn(String html) {
        Pattern attribute = Pattern.compile(
                "\\b(href|src|action|formaction|hx-get|hx-post|sse-connect)=\"([^\"]*)\"");
        List<String> found = new ArrayList<>();
        Matcher matcher = attribute.matcher(html);
        while (matcher.find()) {
            found.add(matcher.group(1) + "=" + matcher.group(2));
        }
        return found;
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + PREFIX + path))
                        .header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postForm(String path, String body) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + PREFIX + path))
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
                    + " ('cus-1', 'C-1041', 'Acme Trading'), ('cus-2', 'C-2000', 'Beta Ltd')");
            statement.execute("create table orders (order_id serial primary key,"
                    + " customer_id varchar(32) not null, note varchar(200))");
            statement.execute("create table docs (id varchar(64) primary key,"
                    + " status varchar(32) not null)");
            statement.execute("insert into docs (id, status) values ('D-1', 'draft')");
            statement.execute("create table items (name varchar(100) primary key,"
                    + " qty integer not null)");
            statement.execute("create table things (id int primary key,"
                    + " name varchar(100) not null)");
            statement.execute("insert into things (id, name) values"
                    + " (1, 'Alpha'), (2, 'Beta'), (3, 'Gamma')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-base-path-emission-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: base-path-emission-app
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  workflow:
                    mode: app
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
                      order.write:
                        anyOf:
                          - role: USER
                      wf.act:
                        anyOf:
                          - role: USER
                      items.write:
                        anyOf:
                          - role: IMPORTER
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        // Surface 1: a reference lookup field. The referenced master route, then the form whose
        // POST declares lookup:, which is what mounts the three synthesized companions.
        write(home, "web/api/customers/search/get.yml", """
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
        write(home, "web/api/customers/search/search.sql", """
                select customer_id, customer_code, name
                from customers
                where 1 = 1
                /*%if q != null && q != "" */
                  and name like '%' || /* q */'a' || '%'
                /*%end*/
                order by name
                """);
        write(home, "web/orders/new/get.yml", """
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
        write(home, "web/orders/new/none.sql", "select 1 as ok\n");
        write(home, "web/orders/new/new.view.yml", """
                version: tesseraql/v1
                id: orders.new.form
                kind: view
                recipe: form
                title: New order
                action: /orders/new
                """);
        write(home, "web/orders/new/post.yml", """
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
        write(home, "web/orders/new/create.sql", """
                insert into orders (customer_id, note)
                values (/* customer_id */'cus-x', /* note */'a note')
                """);

        // Surface 4: a reviewed CSV import. Its review page and its job page are the two
        // remaining emission defects, and neither is reachable without an upload.
        write(home, "web/items/import/items-import.view.yml", """
                version: tesseraql/v1
                kind: view
                recipe: import
                title: Import items
                action: /items/import
                """);
        write(home, "web/items/import/get.yml", """
                version: tesseraql/v1
                id: items.importPage
                kind: route
                recipe: page
                security:
                  auth: browser
                  policy: items.write
                response:
                  html:
                    view: items-import
                """);
        write(home, "web/items/import/post.yml", """
                version: tesseraql/v1
                id: items.import
                kind: route
                recipe: file-import
                security:
                  auth: browser
                  policy: items.write
                import:
                  format: csv
                  columns:
                    - name
                    - { name: qty, type: number }
                  onError: skip
                  review: required
                response:
                  html:
                    view: items-import
                steps:
                  - id: row
                    sql:
                      file: upsert-item.sql
                """);
        write(home, "web/items/import/upsert-item.sql", """
                insert into items (name, qty)
                values ( /* name */ 'sample', cast( /* qty */ '1' as integer) )
                on conflict (name) do update set qty = excluded.qty
                ;
                """);

        // A custom error page, which composes links like any other page.
        write(home, "templates/errors/error.html", """
                <!DOCTYPE html>
                <html><head>
                  <link rel="stylesheet" th:href="@{/assets/_tesseraql/tesseraql.css}">
                </head>
                <body data-error-page><h1 th:text="${status}">Error</h1></body></html>
                """);

        // Surface 2: a workflow detail region. Its _return is the wire page path.
        write(home, "workflow/doc.yml", """
                version: tesseraql/v1
                id: doc
                kind: workflow
                mode: app
                document: { type: doc, table: docs, key: id, stateColumn: status }
                basePath: /api/docs
                security: { auth: browser, policy: wf.act }
                initial: draft
                states:
                  - { id: draft, type: initial }
                  - { id: done, type: terminal }
                transitions:
                  - id: submit
                    from: draft
                    to: done
                    command: { file: touch.sql }
                """);
        write(home, "workflow/touch.sql", """
                update docs set status = status where id = /* key */'D-1'
                ;
                """);
        write(home, "web/docs/{id}/get.yml", """
                version: tesseraql/v1
                id: docs.detail
                kind: route
                recipe: query-html
                security: { policy: wf.act }
                sources:
                  main:
                    sql:
                      file: doc.sql
                      mode: query
                      params:
                        id: path.id
                response:
                  html:
                    view: doc
                """);
        write(home, "web/docs/{id}/doc.sql", """
                select d.id, d.status
                from docs d
                where d.id = /* id */'D-1'
                ;
                """);
        write(home, "web/docs/{id}/detail.view.yml", """
                version: tesseraql/v1
                id: doc
                kind: view
                recipe: detail
                workflow: doc
                title: Document
                """);

        // Surface 3: a list whose row link carries _return, and the command that returns to it.
        write(home, "web/things/get.yml", """
                version: tesseraql/v1
                id: things.page
                kind: route
                recipe: query-html
                pagination: { size: 2, count: true }
                sources:
                  main:
                    sql:
                      file: list.sql
                      mode: query
                response:
                  html:
                    view: things
                """);
        write(home, "web/things/list.sql", "select id, name from things order by id\n;\n");
        write(home, "web/things/list.view.yml", """
                version: tesseraql/v1
                id: things
                kind: view
                recipe: list
                key: id
                title: Things
                columns:
                  - { name: id, label: "#" }
                  - { name: name, link: "/things/{id}/edit" }
                """);
        write(home, "web/things/{id}/edit/get.yml", """
                version: tesseraql/v1
                id: things.edit
                kind: route
                recipe: query-html
                input:
                  id: { type: integer, required: true }
                sources:
                  main:
                    sql:
                      file: thing.sql
                      mode: query
                      params:
                        id: params.id
                response:
                  html:
                    view: things.edit
                """);
        write(home, "web/things/{id}/edit/thing.sql",
                "select id, name from things where id = /* id */ 1\n;\n");
        write(home, "web/things/{id}/edit/edit.view.yml", """
                version: tesseraql/v1
                id: things.edit
                kind: view
                recipe: form
                action: /things/{id}/update
                title: Edit thing
                fields:
                  - name: name
                """);
        write(home, "web/things/{id}/update/post.yml", """
                version: tesseraql/v1
                id: things.update
                kind: route
                recipe: command-json
                input:
                  id: { type: integer, required: true }
                  name: { type: string, required: true, maxLength: 100 }
                steps:
                  - id: main
                    sql:
                      file: update.sql
                      mode: update
                      params:
                        name: params.name
                        id: params.id
                response:
                  redirect:
                    location: back
                """);
        write(home, "web/things/{id}/update/update.sql",
                "update things set name = /* name */ 'x' where id = /* id */ 1\n;\n");
        return home;
    }

    private static void write(Path home, String relative, String body) throws IOException {
        Path target = home.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, body);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            });
        }
    }
}
