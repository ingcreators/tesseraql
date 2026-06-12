package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
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
    @Order(3)
    void constraintViolationMapsToFieldErrorAndRollsBackAllSteps() throws Exception {
        long ordersBefore = count("orders", "1=1");
        HttpResponse<String> response = post("/api/orders", """
                {"customerId": 1, "lines": [{"productId": 999, "quantity": 1}]}""", Map.of());

        assertThat(response.statusCode()).isEqualTo(409);
        JsonNode error = MAPPER.readTree(response.body()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("TQL-SQL-4091");
        JsonNode field = error.path("fields").get(0);
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
        assertThat(error.path("conflict").path("expectedRows").asInt()).isEqualTo(1);
        assertThat(error.path("conflict").path("actualRows").asInt()).isEqualTo(0);
        assertThat(error.path("conflict").path("hint").asText()).contains("another user");
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
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(
                Map.of("sub", "u1", "preferred_username", "admin", "roles",
                        List.of("USER_WRITE"))));
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
                  orderNo:
                    sequence: order-number
                  header:
                    file: insert-order.sql
                    keys: [id]
                    params:
                      orderNo: steps.orderNo.value
                      customerId: body.customerId
                  lines:
                    file: insert-lines.sql
                    params:
                      orderId: steps.header.keys.id
                      lines: body.lines
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
                sql:
                  file: update-status.sql
                  mode: update
                  expect:
                    rows: 1
                    onMismatch: conflict
                  params:
                    id: body.id
                    status: body.status
                    version: body.version
                response:
                  json:
                    status: 200
                    body:
                      affected: sql.affectedRows
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

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
