package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A held source end to end (docs/caching.md decisions 1-7, 10): the rows a statement produced
 * are held per tenant and per bind, served without a statement until a command's
 * {@code invalidates:} names their table — on this node at once, on a second runtime within
 * the stamp interval — and reported and dropped through the operations surface. PostgreSQL
 * runs with {@code log_statement=all}, so every executed statement is a line in the container
 * log and "no statement ran" is a count of zero, not an inference.
 */
@Testcontainers
class ResultHoldIntegrationTest {

    @Container
    @SuppressWarnings("resource") // lifecycle is managed by the @Container extension
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withCommand("postgres", "-c", "log_statement=all");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JWT_SECRET = "dev-only-secret-change-me-in-production";

    static TesseraqlRuntime runtime;
    static TesseraqlRuntime peer;
    static Path appHome;
    static Path peerHome;

    @BeforeAll
    static void start() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("create table items (id serial primary key,"
                    + " tenant_id varchar(16) not null, note varchar(32) not null,"
                    + " stock integer not null, extracted boolean not null default false)");
            statement.execute("insert into items (tenant_id, note, stock) values"
                    + " ('alpha', 'alpha-one', 5), ('alpha', 'alpha-two', 7),"
                    + " ('beta', 'beta-one', 9)");
            statement.execute("create table tenants (tenant_id varchar(16) primary key)");
            statement.execute("insert into tenants values ('alpha'), ('beta')");
        }
        appHome = prepareAppHome("hold-it");
        runtime = TesseraqlRuntime.start(appHome, 0);
        peerHome = prepareAppHome("hold-it");
        peer = TesseraqlRuntime.start(peerHome, 0);
    }

    @AfterAll
    static void stop() throws IOException {
        for (TesseraqlRuntime r : new TesseraqlRuntime[]{runtime, peer}) {
            if (r != null) {
                r.close();
            }
        }
        for (Path home : new Path[]{appHome, peerHome}) {
            if (home != null) {
                try (var files = Files.walk(home)) {
                    files.sorted(java.util.Comparator.reverseOrder())
                            .forEach(path -> path.toFile().delete());
                }
            }
        }
    }

    /** One statement for N requests; a write naming the table drops the hold at once. */
    @Test
    void aHeldSourceRunsOnceUntilAWriteNamesItsTable() throws Exception {
        String marker = "hold-once";
        int before = statements(marker);
        assertThat(get(runtime, "/items?tag=" + marker, "alpha").body()).contains("alpha-one");
        assertThat(get(runtime, "/items?tag=" + marker, "alpha").body()).contains("alpha-one");
        assertThat(get(runtime, "/items?tag=" + marker, "alpha").body()).contains("alpha-two");
        assertThat(statements(marker) - before).as("three requests, one statement").isEqualTo(1);

        // The write names the table: the next read runs the statement and sees the row.
        HttpResponse<String> adjusted = post(runtime, "/items/adjust", "alpha",
                "{\"note\":\"alpha-one\",\"delta\":10}");
        assertThat(adjusted.statusCode()).isEqualTo(200);
        String after = get(runtime, "/items?tag=" + marker, "alpha").body();
        assertThat(MAPPER.readTree(after).get("rows").get(0).get("stock").asInt()).isEqualTo(15);
        assertThat(statements(marker) - before).isEqualTo(2);
    }

    /** The key carries the tenant: one tenant's hold is never another's rows. */
    @Test
    void oneTenantsHoldIsNeverAnothersRows() throws Exception {
        String marker = "hold-tenant";
        String alpha = get(runtime, "/items?tag=" + marker, "alpha").body();
        String beta = get(runtime, "/items?tag=" + marker, "beta").body();
        assertThat(alpha).contains("alpha-one").doesNotContain("beta-one");
        assertThat(beta).contains("beta-one").doesNotContain("alpha-one");
        assertThat(get(runtime, "/items?tag=" + marker, "beta").body())
                .contains("beta-one").doesNotContain("alpha-one");
        assertThat(statements(marker)).as("two tenants, two statements, one hit").isEqualTo(2);
    }

    /** The key carries the binds: each page is its own entry, and a page revisited is a hit. */
    @Test
    void eachPageIsItsOwnEntry() throws Exception {
        String marker = "hold-page";
        assertThat(get(runtime, "/paged?tag=" + marker + "&page=1", "alpha").body())
                .contains("alpha-one").doesNotContain("alpha-two");
        assertThat(get(runtime, "/paged?tag=" + marker + "&page=2", "alpha").body())
                .contains("alpha-two").doesNotContain("alpha-one");
        assertThat(get(runtime, "/paged?tag=" + marker + "&page=1", "alpha").body())
                .contains("alpha-one");
        // Two pages, each its own statement; the count is one entry for both pages, because
        // its text and binds do not carry the page; the revisit costs nothing.
        assertThat(statements(marker)).isEqualTo(3);
    }

    /** A result the row bound cut is held with its flag. */
    @Test
    void aTruncatedResultIsHeldWithItsFlag() throws Exception {
        String marker = "hold-capped";
        String first = get(runtime, "/capped?tag=" + marker, "alpha").body();
        String second = get(runtime, "/capped?tag=" + marker, "alpha").body();
        assertThat(first).contains("\"truncated\":true");
        assertThat(second).contains("\"truncated\":true");
        assertThat(statements(marker)).isEqualTo(1);
    }

    /** A second runtime over the same database follows the write within the stamp interval. */
    @Test
    void aPeerRuntimeFollowsTheWriteWithinTheStampInterval() throws Exception {
        String marker = "hold-peer";
        assertThat(get(peer, "/items?tag=" + marker, "beta").body()).contains("\"stock\":9");
        assertThat(get(peer, "/items?tag=" + marker, "beta").body()).contains("\"stock\":9");
        assertThat(statements(marker)).isEqualTo(1);
        // The write lands on the first runtime; the peer's hold is behind the stamp now.
        assertThat(post(runtime, "/items/adjust", "beta", "{\"note\":\"beta-one\",\"delta\":1}")
                .statusCode()).isEqualTo(200);
        long deadline = System.currentTimeMillis() + 8_000;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            body = get(peer, "/items?tag=" + marker, "beta").body();
            if (body.contains("\"stock\":10")) {
                break;
            }
            Thread.sleep(500);
        }
        assertThat(body).as("the peer re-read the stamp and reloaded").contains("\"stock\":10");
        assertThat(statements(marker)).isEqualTo(2);
    }

    /** The operations surface reports the hold, is gated, and drops on request. */
    @Test
    void theOperationsSurfaceReportsAndDropsTheHold() throws Exception {
        assertThat(get(runtime, "/_tesseraql/ops/cache", null).statusCode()).isEqualTo(401);
        String marker = "hold-ops";
        get(runtime, "/items?tag=" + marker, "alpha");
        get(runtime, "/items?tag=" + marker, "alpha");

        JsonNode status = MAPPER.readTree(ops(runtime, "GET", "/_tesseraql/ops/cache").body());
        assertThat(status.get("enabled").asBoolean()).isTrue();
        assertThat(status.get("maxEntries").asInt()).isEqualTo(1000);
        JsonNode items = null;
        for (JsonNode source : status.get("sources")) {
            if ("items.list".equals(source.get("route").asText())) {
                items = source;
            }
        }
        assertThat(items).isNotNull();
        assertThat(items.get("tables").get(0).asText()).isEqualTo("items");
        assertThat(items.get("hits").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(items.get("misses").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(status.get("stamps").toString()).contains("\"table\":\"items\"");

        // A manual drop over the table: the next request runs the statement again.
        HttpResponse<String> dropped = ops(runtime, "POST",
                "/_tesseraql/ops/cache/invalidate?tables=items");
        assertThat(dropped.statusCode()).isEqualTo(200);
        assertThat(dropped.body()).contains("\"invalidated\":[\"items\"]");
        int before = statements(marker);
        get(runtime, "/items?tag=" + marker, "alpha");
        assertThat(statements(marker) - before).isEqualTo(1);

        // A table no catalog and no held source reads is a 404, not a silent no-op.
        assertThat(ops(runtime, "POST", "/_tesseraql/ops/cache/invalidate?tables=nothing")
                .statusCode()).isEqualTo(404);
        assertThat(ops(runtime, "POST", "/_tesseraql/ops/cache/invalidate").statusCode())
                .isEqualTo(404);
    }

    /** A reload that rebuilds a route drops the hold; one that rebuilds nothing keeps it. */
    @Test
    void aReloadThatRebuildsARouteDropsTheHold() throws Exception {
        String marker = "hold-reload";
        get(runtime, "/items?tag=" + marker, "alpha");
        get(runtime, "/items?tag=" + marker, "alpha");
        assertThat(statements(marker)).isEqualTo(1);
        RouteReloader reloader = runtime.context().lookup(
                io.tesseraql.pipeline.TesseraqlProperties.RUNTIME_SEAMS_BEAN,
                RuntimeSeams.class).reloader();
        // Nothing changed on disk: the reload rebuilds nothing and the hold stands.
        assertThat(reloader.reload().failed()).isEmpty();
        get(runtime, "/items?tag=" + marker, "alpha");
        assertThat(statements(marker)).isEqualTo(1);
        // The route's document changed: it is rebuilt, and the hold goes with it.
        Path document = appHome.resolve("web/items/get.yml");
        Files.writeString(document, Files.readString(document) + "# touched by the reload row\n");
        assertThat(reloader.reload().failed()).isEmpty();
        get(runtime, "/items?tag=" + marker, "alpha");
        assertThat(statements(marker)).isEqualTo(2);
    }

    /**
     * The writers that are not commands (docs/caching.md): a file import names the table, and
     * the hold drops when the import's transaction commits — on the run, after the 202.
     */
    @Test
    void aFileImportDropsTheHoldWhenItsTransactionCommits() throws Exception {
        String marker = "hold-import";
        get(runtime, "/items?tag=" + marker, "alpha");
        get(runtime, "/items?tag=" + marker, "alpha");
        assertThat(statements(marker)).isEqualTo(1);

        HttpResponse<String> accepted = upload(runtime, "/items/import",
                "note,stock\nalpha-imported,3\n");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        JsonNode done = awaitTerminal(runtime, "/items/import/"
                + MAPPER.readTree(accepted.body()).get("transferId").asText());
        assertThat(done.get("status").asText()).as(done.toString()).isEqualTo("COMPLETED");
        assertThat(get(runtime, "/items?tag=" + marker, "alpha").body())
                .contains("alpha-imported");
        assertThat(statements(marker)).isEqualTo(2);
    }

    /**
     * A reviewed import's confirm carries the declaration through the parked batch: the
     * upload parks and writes nothing (the hold stands), the commit writes and drops it.
     */
    @Test
    void aReviewedImportsCommitDropsTheHoldToo() throws Exception {
        String marker = "hold-reviewed";
        get(runtime, "/items?tag=" + marker, "alpha");
        assertThat(statements(marker)).isEqualTo(1);

        HttpResponse<String> parked = upload(runtime, "/items/reviewed",
                "note,stock\nalpha-reviewed,4\n");
        assertThat(parked.statusCode()).as(parked.body()).isEqualTo(200);
        String token = MAPPER.readTree(parked.body()).get("token").asText();
        assertThat(get(runtime, "/items?tag=" + marker, "alpha").body())
                .doesNotContain("alpha-reviewed");
        assertThat(statements(marker)).as("parked: nothing written, nothing dropped")
                .isEqualTo(1);

        HttpResponse<String> committed = commit(runtime, "/items/reviewed", token);
        assertThat(committed.statusCode()).as(committed.body()).isEqualTo(202);
        JsonNode done = awaitTerminal(runtime, "/items/reviewed/"
                + MAPPER.readTree(committed.body()).get("transferId").asText());
        assertThat(done.get("status").asText()).as(done.toString()).isEqualTo("COMPLETED");
        assertThat(get(runtime, "/items?tag=" + marker, "alpha").body())
                .contains("alpha-reviewed");
        assertThat(statements(marker)).isEqualTo(2);
    }

    /**
     * The fifth writer (docs/list-export.md, the {@code after:} commit): a file export whose
     * follow-up marks the rows it extracted commits a write the hold must follow. The
     * extraction-timed statement commits with the run, after the 202 — the export used to
     * complete, mark every row, and leave the held read serving the unmarked ones.
     */
    @Test
    void anExportsExtractionTimedFollowUpDropsTheHoldWhenItsRunCommits() throws Exception {
        String marker = "hold-export";
        get(runtime, "/items?tag=" + marker, "alpha");
        get(runtime, "/items?tag=" + marker, "alpha");
        assertThat(statements(marker)).isEqualTo(1);

        JsonNode done = awaitTerminal(runtime, "/items/export/" + startExport(runtime,
                "/items/export"));
        assertThat(done.get("status").asText()).as(done.toString()).isEqualTo("COMPLETED");
        assertThat(get(runtime, "/items?tag=" + marker, "alpha").body())
                .as("the follow-up's mark reaches the next read").contains("\"extracted\":true");
        assertThat(statements(marker)).isEqualTo(2);
    }

    /**
     * A {@code download}-timed follow-up writes nothing until the file is fetched, so the hold
     * stands through the run's completion and drops with the first fetch — the fetch is where
     * the statement commits, announcing what the transfer recorded when it started.
     */
    @Test
    void anExportsDownloadTimedFollowUpDropsTheHoldOnTheFirstFetch() throws Exception {
        String marker = "hold-export-fetch";
        get(runtime, "/items?tag=" + marker, "alpha");
        assertThat(statements(marker)).isEqualTo(1);

        String transferId = startExport(runtime, "/items/export-on-download");
        JsonNode done = awaitTerminal(runtime, "/items/export-on-download/" + transferId);
        assertThat(done.get("status").asText()).as(done.toString()).isEqualTo("COMPLETED");
        get(runtime, "/items?tag=" + marker, "alpha");
        assertThat(statements(marker)).as("completed, nothing fetched: nothing written")
                .isEqualTo(1);

        HttpResponse<String> file = get(runtime,
                "/items/export-on-download/" + transferId + "/file", "alpha");
        assertThat(file.statusCode()).as(file.body()).isEqualTo(200);
        get(runtime, "/items?tag=" + marker, "alpha");
        assertThat(statements(marker)).isEqualTo(2);

        // A second fetch streams the file and runs nothing, so it drops nothing.
        assertThat(get(runtime, "/items/export-on-download/" + transferId + "/file", "alpha")
                .statusCode()).isEqualTo(200);
        get(runtime, "/items?tag=" + marker, "alpha");
        assertThat(statements(marker)).isEqualTo(2);
    }

    /**
     * The operations surface's fetch announces exactly as the route's file leg does: the
     * transfer recorded what its follow-up announces when it started (docs/list-export.md), so
     * a fetch with no route behind it drops the hold too. It used to claim, run the statement
     * and tell no list and no hold. The API face and the console face share one handler; the
     * API face takes the bearer ops token this test holds.
     */
    @Test
    void anOperationsFetchOfADownloadTimedExportDropsTheHoldToo() throws Exception {
        String marker = "hold-export-console";
        get(runtime, "/items?tag=" + marker, "alpha");
        assertThat(statements(marker)).isEqualTo(1);

        String transferId = startExport(runtime, "/items/export-on-download");
        JsonNode done = awaitTerminal(runtime, "/items/export-on-download/" + transferId);
        assertThat(done.get("status").asText()).as(done.toString()).isEqualTo("COMPLETED");
        get(runtime, "/items?tag=" + marker, "alpha");
        assertThat(statements(marker)).as("completed, nothing fetched: nothing written")
                .isEqualTo(1);

        HttpResponse<String> file = ops(runtime, "GET",
                "/_tesseraql/ops/batch/transfers/" + transferId + "/file");
        assertThat(file.statusCode()).as(file.body()).isEqualTo(200);
        assertThat(file.body()).startsWith("id,");
        get(runtime, "/items?tag=" + marker, "alpha");
        assertThat(statements(marker)).isEqualTo(2);

        // The route's own leg, second: the claim is spent, so it streams and drops nothing.
        assertThat(get(runtime, "/items/export-on-download/" + transferId + "/file", "alpha")
                .statusCode()).isEqualTo(200);
        get(runtime, "/items?tag=" + marker, "alpha");
        assertThat(statements(marker)).isEqualTo(2);
    }

    /** A source with no cache: runs its statement every time, hold or no hold. */
    @Test
    void aPlainSourceStillRunsEveryTime() throws Exception {
        String marker = "hold-plain";
        get(runtime, "/plain?tag=" + marker, "alpha");
        get(runtime, "/plain?tag=" + marker, "alpha");
        assertThat(statements(marker)).isEqualTo(2);
    }

    /**
     * How many times a statement bound to {@code marker} was executed, by PostgreSQL's own
     * account: the tag rides as a bind, and the server logs an execution's binds on its
     * {@code DETAIL: parameters:} line — one line per execution, the count statement's
     * included.
     */
    private static int statements(String marker) throws InterruptedException {
        // The log is written by the server after the statement; a short wait makes the count
        // the whole story rather than the part already flushed.
        Thread.sleep(400);
        Matcher matcher = Pattern.compile("parameters: [^\\n]*'" + Pattern.quote(marker) + "'")
                .matcher(POSTGRES.getLogs());
        int n = 0;
        while (matcher.find()) {
            n++;
        }
        return n;
    }

    private static HttpResponse<String> get(TesseraqlRuntime target, String path, String tenant)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + target.port() + path));
        if (tenant != null) {
            request.header("X-Tenant-Id", tenant);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(TesseraqlRuntime target, String path,
            String tenant, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + target.port() + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenant)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A raw CSV upload to a file-import route, as tenant alpha and the ops principal. */
    private static HttpResponse<String> upload(TesseraqlRuntime target, String path, String csv)
            throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + target.port() + path))
                .header("Content-Type", "text/csv")
                .header("Authorization", "Bearer " + opsToken())
                .header("X-Tenant-Id", "alpha")
                .POST(HttpRequest.BodyPublishers.ofString(csv)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Starts a file export as tenant alpha; the route binds nothing from the body. */
    private static String startExport(TesseraqlRuntime target, String path) throws Exception {
        HttpResponse<String> accepted = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + target.port() + path))
                .header("Content-Type", "text/csv")
                .header("X-Tenant-Id", "alpha")
                .POST(HttpRequest.BodyPublishers.ofString("")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        return MAPPER.readTree(accepted.body()).get("transferId").asText();
    }

    /** The confirm leg of a reviewed import: an empty POST to the batch's commit address. */
    private static HttpResponse<String> commit(TesseraqlRuntime target, String path, String token)
            throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + target.port() + path + "/" + token + "/commit"))
                .header("Authorization", "Bearer " + opsToken())
                .header("X-Tenant-Id", "alpha")
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Polls a transfer's status until it leaves RUNNING: the import runs on its own thread. */
    private static JsonNode awaitTerminal(TesseraqlRuntime target, String statusPath)
            throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (true) {
            HttpResponse<String> polled = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + target.port() + statusPath))
                    .header("Authorization", "Bearer " + opsToken())
                    .header("X-Tenant-Id", "alpha").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(polled.statusCode()).as(polled.body()).isEqualTo(200);
            JsonNode status = MAPPER.readTree(polled.body());
            String value = status.path("status").asText();
            if (!"RUNNING".equals(value) && !"STARTED".equals(value)) {
                return status;
            }
            assertThat(System.currentTimeMillis()).as("the import finishes: " + status)
                    .isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    /** An authenticated ops call: bearer principal holding the ops grants. */
    private static HttpResponse<String> ops(TesseraqlRuntime target, String method, String path)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + target.port() + path))
                .header("Authorization", "Bearer " + opsToken())
                .header("X-Tenant-Id", "alpha");
        request.method(method, HttpRequest.BodyPublishers.noBody());
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String opsToken() throws Exception {
        java.util.Base64.Encoder encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(
                "{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(
                ("{\"sub\":\"ops-user\",\"roles\":[\"ADMIN\"],"
                        + "\"permissions\":[\"tql.ops.view.*\",\"tql.ops.run.*\"],\"aud\":\""
                        + TestClaims.INLINE_FIXTURE + "\",\"exp\":"
                        + (System.currentTimeMillis() / 1000 + 3600) + "}")
                        .getBytes(StandardCharsets.UTF_8));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = encoder.encodeToString(mac.doFinal(
                (header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static Path prepareAppHome(String name) throws IOException {
        Path target = Files.createTempDirectory("tesseraql-result-hold-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tenancy:
                  enabled: true
                  mode: shared-schema
                  required: false
                  resolver:
                    type: header
                    source: X-Tenant-Id
                  registry:
                    sql: select tenant_id from tenants order by tenant_id

                tesseraql:
                  security:
                    jwt:
                      secret: %s
                      audience: %s
                      rolesClaim: roles
                      permissionsClaim: permissions
                  app:
                    name: %s
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(JWT_SECRET, TestClaims.INLINE_FIXTURE, name,
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        // Every statement carries the request's tag in a literal-free way (a bind), so the
        // container log tells the requests of one test from another's.
        String itemsSql = "select id, note, stock, extracted, /* tag */'t' as tag from items"
                + " where tenant_id = /* tenant_id */'alpha' order by id\n";
        writeRoute(target, "items", "items.list", """
                input:
                  tag: { type: string }
                sources:
                  main:
                    sql:
                      file: items.sql
                      params:
                        tenant_id: tenant.id
                        tag: params.tag
                    cache:
                      maxAge: 30s
                      tables: [items]
                response:
                  json:
                    body:
                      rows: main.rows
                """, itemsSql);
        writeRoute(target, "plain", "items.plain", """
                input:
                  tag: { type: string }
                sources:
                  main:
                    sql:
                      file: items.sql
                      params:
                        tenant_id: tenant.id
                        tag: params.tag
                response:
                  json:
                    body:
                      rows: main.rows
                """, itemsSql);
        writeRoute(target, "paged", "items.paged", """
                input:
                  tag: { type: string }
                pagination:
                  size: 1
                  count: true
                sources:
                  main:
                    sql:
                      file: items.sql
                      params:
                        tenant_id: tenant.id
                        tag: params.tag
                    cache:
                      maxAge: 30s
                      tables: [items]
                response:
                  json:
                    body:
                      rows: main.rows
                      page: page
                """, itemsSql);
        writeRoute(target, "capped", "items.capped", """
                input:
                  tag: { type: string }
                sources:
                  main:
                    sql:
                      file: items.sql
                      params:
                        tenant_id: tenant.id
                        tag: params.tag
                      materialize:
                        maxRows: 1
                        onOverflow: warn
                    cache:
                      maxAge: 30s
                      tables: [items]
                response:
                  json:
                    body:
                      rows: main.rows
                      truncated: main.truncated
                """, itemsSql);
        Path adjust = Files.createDirectories(target.resolve("web/items/adjust"));
        Files.writeString(adjust.resolve("adjust.sql"), "update items set stock = stock +"
                + " /* delta */1 where note = /* note */'x' and tenant_id = /* tenant_id */'a'\n");
        Files.writeString(adjust.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.adjust
                kind: route
                recipe: command-json
                security:
                  auth: public
                input:
                  note: { type: string, required: true }
                  delta: { type: integer, required: true }
                steps:
                  - id: main
                    sql:
                      file: adjust.sql
                      mode: update
                      params:
                        note: params.note
                        delta: params.delta
                        tenant_id: tenant.id
                invalidates: [items]
                response:
                  json:
                    status: 200
                    body:
                      adjusted: steps.main.affectedRows
                """);
        // The writers that are not commands (docs/caching.md): a direct file import and a
        // reviewed one, both naming the table the held read reads. A reviewed import belongs
        // to the principal who uploaded it (TQL-ROUTE-3118), so that route authenticates.
        String importRow = "insert into items (tenant_id, note, stock) values ('alpha',"
                + " /* note */'x', cast(/* stock */'1' as integer))\n";
        for (String shape : new String[]{"import", "reviewed"}) {
            Path dir = Files.createDirectories(target.resolve("web/items/" + shape));
            Files.writeString(dir.resolve("import-row.sql"), importRow);
            Files.writeString(dir.resolve("post.yml"), """
                    version: tesseraql/v1
                    id: items.%s
                    kind: route
                    recipe: file-import
                    security:
                      auth: %s
                    import:
                      format: csv
                      columns:
                        - note
                        - { name: stock, type: number }
                    %ssteps:
                      - id: row
                        sql:
                          file: import-row.sql
                    invalidates: [items]
                    """.formatted(shape, "reviewed".equals(shape) ? "bearer" : "public",
                    "reviewed".equals(shape) ? "  review: required\n" : ""));
        }
        // The fifth writer (docs/list-export.md): a file export whose after: statement marks the
        // rows it extracted, one route per timing — the extraction's commit and the first
        // fetch's — each naming the table the held read reads.
        String markSql = "update items set extracted = true where tenant_id ="
                + " /* tenant_id */'alpha' and not extracted\n";
        String exportSql = "select id, note, stock from items where tenant_id ="
                + " /* tenant_id */'alpha' order by id\n";
        for (String timing : new String[]{"extract", "download"}) {
            Path dir = Files.createDirectories(target.resolve("web/items/export"
                    + ("download".equals(timing) ? "-on-download" : "")));
            Files.writeString(dir.resolve("items-export.sql"), exportSql);
            Files.writeString(dir.resolve("mark-extracted.sql"), markSql);
            Files.writeString(dir.resolve("post.yml"), """
                    version: tesseraql/v1
                    id: items.export%s
                    kind: route
                    recipe: file-export
                    security:
                      auth: public
                    export:
                      format: csv
                      after:
                        timing: %s
                        sql:
                          file: mark-extracted.sql
                    sources:
                      main:
                        sql:
                          file: items-export.sql
                          params:
                            tenant_id: tenant.id
                    invalidates: [items]
                    """.formatted("download".equals(timing) ? "OnDownload" : "", timing));
        }
        return target;
    }

    private static void writeRoute(Path home, String dir, String id, String body, String sql)
            throws IOException {
        Path route = Files.createDirectories(home.resolve("web/" + dir));
        Files.writeString(route.resolve("items.sql"), sql);
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: query-json
                security:
                  auth: public
                %s
                """.formatted(id, body));
    }
}
