package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * Phase 18 acceptance (roadmap "transactional write depth"): an order header+lines form posts
 * once, writes atomically with a gapless document number, generated keys flow between steps,
 * audit binds resolve from the principal and clock, replays ride the idempotency machinery,
 * a concurrent edit yields a 409 with a usable conflict hint, and a constraint violation maps
 * to a field-level error payload (JSON and htmx) while rolling back every step.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransactionalCommandIntegrationTest {

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
    @Order(1)
    void orderHeaderAndLinesCommitAtomicallyWithSequenceKeysAndAudit() throws Exception {
        HttpResponse<String> response = post("/api/orders", """
                {"customerId": 1, "lines": [
                  {"productId": 10, "quantity": 2},
                  {"productId": 11, "quantity": 5}
                ]}""", Map.of("Idempotency-Key", "order-1"));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = MAPPER.readTree(response.body());
        long orderId = body.path("orderId").asLong();
        assertThat(orderId).isPositive(); // generated key captured from the header insert
        assertThat(body.path("orderNo").asLong()).isEqualTo(1); // first sequence value
        assertThat(body.path("lines").asInt()).isEqualTo(2);
        assertThat(body.path("eventId").asText()).isNotBlank();
        // A command's query-step shapes its rows the way a query route does: an ISO-8601
        // instant, not whatever java.sql.Timestamp.toString() a driver happens to produce
        // ("2026-01-01 00:00:00.0"). A response binding written against one path used to
        // break on the other.
        assertThat(body.path("placed").get(0).path("created_at").asText())
                .matches("\\d{4}-\\d{2}-\\d{2}T.*");

        Map<String, Object> header = queryOne(
                "select order_no, status, version, created_by, created_at from orders where id = "
                        + orderId);
        assertThat(header.get("order_no")).isEqualTo(1L);
        assertThat(header.get("created_by")).isEqualTo("admin"); // /* audit.user */
        assertThat(header.get("created_at")).isNotNull(); // /* audit.now */
        assertThat(count("order_lines", "order_id = " + orderId)).isEqualTo(2);
        assertThat(queryOne("select line_no, product_id from order_lines where order_id = "
                + orderId + " and line_no = 2").get("product_id")).isEqualTo(11);

        assertThat(runtime.outboxStore().listPending(50))
                .anyMatch(event -> "ORDER_PLACED".equals(event.eventType()));
    }

    @Test
    @Order(2)
    void replayWithSameIdempotencyKeyReturnsStoredResponseWithoutRewriting() throws Exception {
        long ordersBefore = count("orders", "1=1");
        HttpResponse<String> replay = post("/api/orders", """
                {"customerId": 1, "lines": [
                  {"productId": 10, "quantity": 2},
                  {"productId": 11, "quantity": 5}
                ]}""", Map.of("Idempotency-Key", "order-1"));

        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(MAPPER.readTree(replay.body()).path("orderNo").asLong()).isEqualTo(1);
        assertThat(count("orders", "1=1")).isEqualTo(ordersBefore); // nothing written again
    }

    @Test
    @Order(21)
    void aRefusalBeforeCommitLeavesTheKeyLiveForTheCorrectedResubmit() throws Exception {
        // quantity 0 violates min: 1 - refused before any write, with the key already claimed.
        HttpResponse<String> refused = post("/api/orders", """
                {"customerId": 1, "lines": [
                  {"productId": 10, "quantity": 0}
                ]}""", Map.of("Idempotency-Key", "order-fix"));
        assertThat(refused.statusCode()).isEqualTo(400);

        // The key is spent by the commit, not the attempt (docs/idempotency-key.md decision 1):
        // the runner released the stranded claim, so the corrected resubmit with the SAME key
        // commits instead of answering TQL-IDEM-4090 until TTL.
        HttpResponse<String> corrected = post("/api/orders", """
                {"customerId": 1, "lines": [
                  {"productId": 10, "quantity": 1}
                ]}""", Map.of("Idempotency-Key", "order-fix"));
        assertThat(corrected.statusCode()).as(corrected.body()).isEqualTo(201);
    }

    @Test
    @Order(22)
    void reusingACommittedKeyForADifferentPayloadAnswers422() throws Exception {
        long ordersBefore = count("orders", "1=1");
        HttpResponse<String> reused = post("/api/orders", """
                {"customerId": 1, "lines": [
                  {"productId": 10, "quantity": 9}
                ]}""", Map.of("Idempotency-Key", "order-1"));

        // Same intent token, different content: a stale tab or a bug, not a retry - 422, so it
        // renders where the request's own refusals do, and never as a punished 409.
        assertThat(reused.statusCode()).isEqualTo(422);
        assertThat(MAPPER.readTree(reused.body()).path("error").path("code").asText())
                .isEqualTo("TQL-IDEM-4221");
        assertThat(count("orders", "1=1")).isEqualTo(ordersBefore);
    }

    @Test
    @Order(23)
    void theStoreDistinguishesInFlightFromMismatchAndReleasesOnlyClaims() {
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        io.tesseraql.operations.idempotency.JdbcIdempotencyStore store = new io.tesseraql.operations.idempotency.JdbcIdempotencyStore(
                dataSource);

        assertThat(store.begin("s", "k1", "h1", 60_000))
                .isInstanceOf(io.tesseraql.core.idempotency.IdempotencyStore.Proceed.class);
        // Same request while the claim is open: the race with yourself, 409.
        var inFlight = store.begin("s", "k1", "h1", 60_000);
        assertThat(inFlight).isInstanceOfSatisfying(
                io.tesseraql.core.idempotency.IdempotencyStore.Conflict.class,
                conflict -> assertThat(conflict.inFlight()).isTrue());
        // A different request against the claim: the reuse, 422's branch.
        var mismatch = store.begin("s", "k1", "h2", 60_000);
        assertThat(mismatch).isInstanceOfSatisfying(
                io.tesseraql.core.idempotency.IdempotencyStore.Conflict.class,
                conflict -> assertThat(conflict.inFlight()).isFalse());

        // Release frees an open claim; a completed record is a stored response and stays.
        store.release("s", "k1");
        assertThat(store.begin("s", "k1", "h1", 60_000))
                .isInstanceOf(io.tesseraql.core.idempotency.IdempotencyStore.Proceed.class);
        store.complete("s", "k1", 201, "{}", "application/json");
        store.release("s", "k1");
        assertThat(store.begin("s", "k1", "h1", 60_000))
                .isInstanceOf(io.tesseraql.core.idempotency.IdempotencyStore.Replay.class);
    }

    @Test
    @Order(3)
    void constraintViolationMapsToFieldErrorAndRollsBackAllSteps() throws Exception {
        long ordersBefore = count("orders", "1=1");
        HttpResponse<String> response = post("/api/orders", """
                {"customerId": 1, "lines": [{"productId": 999, "quantity": 1}]}""", Map.of());

        assertThat(response.statusCode()).isEqualTo(409);
        JsonNode error = MAPPER.readTree(response.body()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("TQL-SQL-4091");
        JsonNode field = error.path("details").path("fields").get(0);
        assertThat(field.path("field").asText()).isEqualTo("lines");
        assertThat(field.path("code").asText()).isEqualTo("unknown-product");
        assertThat(field.path("constraint").asText()).isEqualTo("order_lines_product_fk");

        // The header insert (an earlier step) rolled back with the failing lines step.
        assertThat(count("orders", "1=1")).isEqualTo(ordersBefore);
    }

    @Test
    @Order(4)
    void failedAllocationDoesNotBurnTheGaplessSequence() throws Exception {
        // The rolled-back order in the previous test returned its number; the next
        // successful order continues the sequence without a gap.
        HttpResponse<String> response = post("/api/orders", """
                {"customerId": 1, "lines": [{"productId": 10, "quantity": 1}]}""", Map.of());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(MAPPER.readTree(response.body()).path("orderNo").asLong()).isEqualTo(2);
    }

    /**
     * The line-item input contract (docs/declarative-validation.md, "Line items"): the
     * deny-by-default posture {@code input:} holds at the top level reaches inside the array,
     * and the violation says which line and which field — over HTTP, through the real binder.
     */
    @Test
    @Order(41)
    void aLineViolatingItsContractIsRejectedByIndexAndField() throws Exception {
        long ordersBefore = count("orders", "1=1");
        HttpResponse<String> response = post("/api/orders", """
                {"customerId": 1, "lines": [
                  {"productId": 10, "quantity": 1},
                  {"productId": 11, "quantity": 0}
                ]}""", Map.of());

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode error = MAPPER.readTree(response.body()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("TQL-FIELD-2001");
        JsonNode field = error.path("details").path("fields").get(0);
        assertThat(field.path("field").asText()).isEqualTo("lines[1].quantity");
        assertThat(field.path("code").asText()).isEqualTo("min");
        assertThat(count("orders", "1=1")).isEqualTo(ordersBefore);
    }

    /** An element field nothing declared is the mass-assignment guard, one level down. */
    @Test
    @Order(42)
    void anUndeclaredLineFieldIsRefused() throws Exception {
        HttpResponse<String> response = post("/api/orders", """
                {"customerId": 1, "lines": [
                  {"productId": 10, "quantity": 1, "unitPrice": "9.99"}
                ]}""", Map.of());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(MAPPER.readTree(response.body()).path("error").path("code").asText())
                .isEqualTo("TQL-FIELD-2002");
    }

    @Test
    @Order(5)
    void concurrentEditYieldsConflictWithUsableHint() throws Exception {
        long orderId = ((Number) queryOne("select min(id) as id from orders").get("id"))
                .longValue();

        HttpResponse<String> ok = post("/api/orders/update-status",
                "{\"id\": " + orderId + ", \"status\": \"APPROVED\", \"version\": 1}", Map.of());
        assertThat(ok.statusCode()).isEqualTo(200);
        Map<String, Object> updated = queryOne(
                "select version, updated_by from orders where id = " + orderId);
        assertThat(updated.get("version")).isEqualTo(2);
        assertThat(updated.get("updated_by")).isEqualTo("admin");

        // A second editor still holding version 1: the row-count expectation turns the silent
        // lost update into a 409 with a conflict hint.
        HttpResponse<String> stale = post("/api/orders/update-status",
                "{\"id\": " + orderId + ", \"status\": \"SHIPPED\", \"version\": 1}", Map.of());
        assertThat(stale.statusCode()).isEqualTo(409);
        JsonNode error = MAPPER.readTree(stale.body()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("TQL-SQL-4092");
        JsonNode conflict = error.path("details").path("conflict");
        assertThat(conflict.path("expectedRows").asInt()).isEqualTo(1);
        assertThat(conflict.path("actualRows").asInt()).isEqualTo(0);
        assertThat(conflict.path("hint").asText()).contains("another user");
        assertThat(queryOne("select status from orders where id = " + orderId).get("status"))
                .isEqualTo("APPROVED"); // the stale write did not stick
    }

    @Test
    @Order(6)
    void htmxRequestReceivesInlineErrorFragment() throws Exception {
        long orderId = ((Number) queryOne("select min(id) as id from orders").get("id"))
                .longValue();
        HttpResponse<String> stale = post("/api/orders/update-status",
                "{\"id\": " + orderId + ", \"status\": \"SHIPPED\", \"version\": 99}",
                Map.of("HX-Request", "true"));

        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        assertThat(stale.body()).contains("class=\"hc-alert\" data-variant=\"error\"")
                .contains("data-error-code=\"TQL-SQL-4092\"")
                .contains("hc-alert__body");
    }

    @Test
    @Order(7)
    void storedCallBindsInsAndPublishesDeclaredOuts() throws Exception {
        HttpResponse<String> response = post("/api/orders/reprice", "{\"factor\": 21}",
                Map.of());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        // The OUT parameter came back by its declared name: registered by the rendered
        // out.o_doubled bind site's position, published as steps.<id>.out.<name>.
        assertThat(MAPPER.readTree(response.body()).path("doubled").asInt()).isEqualTo(42);
    }

    private static HttpResponse<String> post(String path, String json,
            Map<String, String> extraHeaders) throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        extraHeaders.forEach(request::header);
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(TestClaims.addressed(
                Map.of("sub", "u1", "preferred_username", "admin", "roles",
                        List.of("USER_WRITE")))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static Map<String, Object> queryOne(String sql) {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            for (int col = 1; col <= rs.getMetaData().getColumnCount(); col++) {
                row.put(rs.getMetaData().getColumnLabel(col).toLowerCase(java.util.Locale.ROOT),
                        rs.getObject(col));
            }
            return row;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static long count(String table, String where) {
        Object value = queryOne("select count(*) as c from " + table + " where " + where).get("c");
        return ((Number) value).longValue();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("create table customers (id int primary key, name varchar(100))");
            statement.execute("create table products (id int primary key, name varchar(100))");
            statement.execute("""
                    create table orders (
                      id serial primary key,
                      order_no bigint not null,
                      customer_id int not null,
                      status varchar(20) not null,
                      version int not null,
                      created_by varchar(100),
                      created_at timestamp,
                      updated_by varchar(100),
                      updated_at timestamp,
                      constraint orders_order_no_uq unique (order_no),
                      constraint orders_customer_fk foreign key (customer_id)
                          references customers (id))""");
            statement.execute("""
                    create table order_lines (
                      id serial primary key,
                      order_id int not null references orders (id),
                      line_no int not null,
                      product_id int not null,
                      quantity int not null,
                      constraint order_lines_product_fk foreign key (product_id)
                          references products (id))""");
            statement.execute("insert into customers (id, name) values (1, 'ACME')");
            // The stored call the mode: call step invokes (docs/sql-execution-shapes.md
            // structural decision 7): a function with OUT parameters, the shape PostgreSQL's
            // driver serves through the call escape on its default escapeSyntaxCallMode.
            statement.execute("""
                    create function reprice(p_factor int, out o_doubled int)
                    language plpgsql as $$
                    begin
                      o_doubled := p_factor * 2;
                    end $$""");
            statement.execute(
                    "insert into products (id, name) values (10, 'Widget'), (11, 'Gadget')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-command-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        UserAdminAppJobs.parkDailyMaintenanceSchedule(target);
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
        writeOrderRoutes(target);
        return target;
    }

    /** The order-entry slice: header+lines creation and an optimistic-locking status update. */
    private static void writeOrderRoutes(Path appHome) throws IOException {
        Path create = appHome.resolve("web/api/orders");
        Files.createDirectories(create);
        Files.writeString(create.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.create
                kind: route
                recipe: command-json
                input:
                  customerId:
                    type: integer
                    required: true
                  lines:
                    type: array
                    items:
                      fields:
                        productId:
                          type: integer
                          required: true
                        quantity:
                          type: integer
                          required: true
                          min: 1
                          max: 100
                idempotency:
                  required: false
                security:
                  auth: bearer
                  policy: users.write
                outbox:
                  eventType: ORDER_PLACED
                  aggregateType: Order
                  aggregateId: steps.header.keys.id
                  payload:
                    orderNo: steps.orderNo.value
                    customerId: body.customerId
                steps:
                  - id: orderNo
                    sequence: order-number
                  - id: header
                    sql:
                      file: insert-order.sql
                      keys: [id]
                      params:
                        orderNo: steps.orderNo.value
                        customerId: body.customerId
                  - id: lines
                    sql:
                      file: insert-lines.sql
                      params:
                        orderId: steps.header.keys.id
                        lines: params.lines
                  - id: placed
                    sql:
                      file: select-placed.sql
                      mode: query
                      params:
                        orderId: steps.header.keys.id
                errors:
                  constraints:
                    order_lines_product_fk:
                      field: lines
                      code: unknown-product
                    orders_customer_fk:
                      field: customerId
                      code: unknown-customer
                response:
                  json:
                    status: 201
                    body:
                      orderId: steps.header.keys.id
                      orderNo: steps.orderNo.value
                      lines: steps.lines.affectedRows
                      placed: steps.placed.rows
                      eventId: outbox.eventId
                """);
        Files.writeString(create.resolve("insert-order.sql"), """
                insert into orders (order_no, customer_id, status, version,
                                    created_by, created_at)
                values (/* orderNo */1, /* customerId */1, 'PLACED', 1,
                        /* audit.user */'someone', /* audit.now */'2026-01-01 00:00:00')
                """);
        Files.writeString(create.resolve("insert-lines.sql"), """
                insert into order_lines (order_id, line_no, product_id, quantity)
                values
                /*%for line : lines separator ', ' */
                (/* orderId */1, /* line_index */0 + 1, /* line.productId */10,
                 /* line.quantity */1)
                /*%end*/
                """);
        Files.writeString(create.resolve("select-placed.sql"), """
                select created_at from orders where id = /* orderId */1
                """);

        Path reprice = appHome.resolve("web/api/orders/reprice");
        Files.createDirectories(reprice);
        Files.writeString(reprice.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.reprice
                kind: route
                recipe: command-json
                input:
                  factor:
                    type: integer
                    required: true
                security:
                  auth: bearer
                  policy: users.write
                steps:
                  - id: reprice
                    sql:
                      file: call-reprice.sql
                      mode: call
                      params:
                        factor: body.factor
                      out:
                        o_doubled: integer
                response:
                  json:
                    status: 200
                    body:
                      doubled: steps.reprice.out.o_doubled
                """);
        Files.writeString(reprice.resolve("call-reprice.sql"), """
                {call reprice(/* factor */1, /* out.o_doubled */null)}
                """);

        Path update = appHome.resolve("web/api/orders/update-status");
        Files.createDirectories(update);
        Files.writeString(update.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.updateStatus
                kind: route
                recipe: command-json
                input:
                  id:
                    type: integer
                    required: true
                  status:
                    type: string
                    required: true
                  version:
                    type: integer
                    required: true
                security:
                  auth: bearer
                  policy: users.write
                steps:
                  - id: main
                    sql:
                      file: update-status.sql
                      mode: update
                      expect:
                        rowCount: 1
                        onMismatch: conflict
                      params:
                        id: body.id
                        status: body.status
                        version: body.version
                response:
                  json:
                    status: 200
                    body:
                      affected: steps.main.affectedRows
                """);
        Files.writeString(update.resolve("update-status.sql"), """
                update orders
                set status = /* status */'APPROVED',
                    version = version + 1,
                    updated_by = /* audit.user */'someone',
                    updated_at = /* audit.now */'2026-01-01 00:00:00'
                where id = /* id */1
                  and version = /* version */1
                """);
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
