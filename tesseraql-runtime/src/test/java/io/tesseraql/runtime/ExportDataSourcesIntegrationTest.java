package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A document composes its other data around the rows (docs/export-pipeline.md, decision 2). A codec
 * used to receive the extraction's rows and nothing else, so an order with its line items had to
 * denormalize the header onto every line and pick it back out of {@code rows[0]} — while a read
 * route has carried named queries all along.
 *
 * <p>The header query runs on the extraction's own connection, inside its transaction and before
 * it, so a document reads exactly the state its rows came from.
 *
 * <p>And it binds exactly as a read route's named query does (docs/audit-low-leads.md slice 3b):
 * its own {@code params:} over the request's map, on both executing paths (G28), and its scope
 * directives through the caller's resolver (G30) — the two-argument render used to answer
 * TQL-SQL-2106 to a scoped named source.
 */
@Testcontainers
class ExportDataSourcesIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static TesseraqlRuntime runtime;
    static Path appHome;
    static int port;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        // Port 0: the runtime binds an ephemeral port and reports it, so no
        // pick-then-bind race with parallel suites (the freePort() TOCTOU flake).
        runtime = TesseraqlRuntime.start(appHome, 0);
        port = runtime.port();
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
    void theDocumentRendersItsHeaderFromANamedQueryAndItsLinesFromTheExtraction() throws Exception {
        HttpResponse<byte[]> response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders/print"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        try (PDDocument document = Loader.loadPDF(response.body())) {
            String text = new PDFTextStripper().getText(document);
            // The header comes from the named query, and never appears on a line.
            assertThat(text).contains("Order SO-1001", "Acme Corporation");
            assertThat(text).contains("widget", "gadget");
        }
    }

    @Test
    void eachSplitDocumentPrintsItsOwnCustomer() throws Exception {
        HttpResponse<byte[]> response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders/bill"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, String> invoices = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(response.body()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                try (PDDocument document = Loader.loadPDF(zip.readAllBytes())) {
                    invoices.put(entry.getName(), new PDFTextStripper().getText(document));
                }
            }
        }

        assertThat(invoices).containsOnlyKeys("invoice-SO-1001.pdf", "invoice-SO-1002.pdf");
        // Each invoice prints its own customer, from one query run for the whole export — the
        // case that printed the same customer on every document until now.
        assertThat(invoices.get("invoice-SO-1001.pdf"))
                .contains("Acme Corporation").doesNotContain("Globex");
        assertThat(invoices.get("invoice-SO-1002.pdf"))
                .contains("Globex").doesNotContain("Acme Corporation");
    }

    /** A named source's own {@code params:} reach its query on the query-export path. */
    @Test
    void aNamedSourceBindsItsOwnParamsOnTheInlinePath() throws Exception {
        HttpResponse<byte[]> response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/orders/print-one?order=SO-1002"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        try (PDDocument document = Loader.loadPDF(response.body())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Order SO-1002", "Globex").doesNotContain("Acme");
        }
    }

    /** The same, on the file-export path — the transfer service renders the named query. */
    @Test
    void aNamedSourceBindsItsOwnParamsOnTheTransferPath() throws Exception {
        HttpResponse<String> started = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/orders/file"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"order\": \"SO-1002\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(started.statusCode()).isEqualTo(202);
        String transferId = io.tesseraql.yaml.JsonMappers.constrained()
                .readTree(started.body()).get("transferId").asString();
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(20);
        String status;
        do {
            HttpResponse<String> polled = HTTP.send(HttpRequest.newBuilder(URI.create(
                    "http://localhost:" + port + "/api/orders/file/" + transferId)).build(),
                    HttpResponse.BodyHandlers.ofString());
            status = io.tesseraql.yaml.JsonMappers.constrained().readTree(polled.body())
                    .get("status").asString();
            assertThat(java.time.Instant.now()).isBefore(deadline);
            Thread.sleep(100);
        } while ("PENDING".equals(status) || "RUNNING".equals(status));
        assertThat(status).isEqualTo("COMPLETED");
        HttpResponse<byte[]> file = HTTP.send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/api/orders/file/" + transferId + "/file")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(file.statusCode()).isEqualTo(200);
        try (PDDocument document = Loader.loadPDF(file.body())) {
            assertThat(new PDFTextStripper().getText(document))
                    .contains("Order SO-1002", "Globex").doesNotContain("Acme");
        }
    }

    /**
     * A scope directive in a named source renders through the caller's resolver: a buyer whose
     * claim names Globex prints Globex's header. The resolver-less render answered 500 with
     * TQL-SQL-2106 to any scoped named source.
     */
    @Test
    void aScopedNamedSourceRendersUnderTheCallersScope() throws Exception {
        HttpResponse<byte[]> response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/orders/mine?order=SO-1002"))
                        .header("Authorization", "Bearer " + token("buyer-1", "Globex"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        try (PDDocument document = Loader.loadPDF(response.body())) {
            assertThat(new PDFTextStripper().getText(document)).contains("Order SO-1002", "Globex");
        }
    }

    private static String token(String sub, String customer) throws Exception {
        java.util.Base64.Encoder enc = java.util.Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String payload = enc.encodeToString(io.tesseraql.yaml.JsonMappers.constrained()
                .writeValueAsBytes(TestClaims.addressed(Map.of("sub", sub,
                        "roles", java.util.List.of("buyer"), "customer", customer))));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                JWT_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = enc.encodeToString(mac.doFinal(
                (header + "." + payload).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static final String JWT_SECRET = "dev-only-secret-change-me-in-production";

    private static Path prepareAppHome() throws Exception {
        Path home = Files.createTempDirectory("export-sources-app");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/tesseraql.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: sources-demo
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  security:
                    jwt:
                      secret: %s
                      audience: https://app.example.com
                      rolesClaim: roles
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), JWT_SECRET));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__orders.sql"), """
                create table orders (order_no varchar(20) primary key, customer varchar(100));
                create table order_lines (order_no varchar(20), item varchar(50), qty int);
                insert into orders (order_no, customer) values ('SO-1001', 'Acme Corporation');
                insert into orders (order_no, customer) values ('SO-1002', 'Globex');
                insert into order_lines (order_no, item, qty) values ('SO-1001', 'widget', 3);
                insert into order_lines (order_no, item, qty) values ('SO-1001', 'gadget', 5);
                insert into order_lines (order_no, item, qty) values ('SO-1002', 'sprocket', 7);
                """);

        Path print = home.resolve("web/api/orders/print");
        Files.createDirectories(print);
        Files.writeString(print.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.print
                kind: route
                recipe: query-export
                sources:
                  main:
                    sql:
                      file: lines.sql
                  header:
                    sql:
                      file: header.sql
                export:
                  format: pdf
                  filename: order.pdf
                  template: order.html
                  maxRows: 100
                  columns:
                    - { name: item, label: Item }
                    - { name: qty,  label: Qty }
                """);
        // The line query carries no customer column: that is the point of the header query.
        Files.writeString(print.resolve("lines.sql"),
                "select item, qty from order_lines where order_no = 'SO-1001' order by item\n;\n");

        Path bill = home.resolve("web/api/orders/bill");
        Files.createDirectories(bill);
        Files.writeString(bill.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.bill
                kind: route
                recipe: query-export
                sources:
                  main:
                    sql:
                      file: all-lines.sql
                  customer:
                    sql:
                      file: customers.sql
                  company:
                    sql:
                      file: company.sql
                export:
                  format: pdf
                  filename: invoice-{key}.pdf
                  template: invoice.html
                  maxRows: 100
                  splitBy: order_no
                  columns:
                    - { name: item, label: Item }
                    - { name: qty,  label: Qty }
                """);
        Files.writeString(bill.resolve("all-lines.sql"),
                "select order_no, item, qty from order_lines order by order_no, item\n;\n");
        // Selects the split column, so each invoice reads its own row.
        Files.writeString(bill.resolve("customers.sql"),
                "select order_no, customer from orders order by order_no\n;\n");
        // Does not, so every invoice reads the same one.
        Files.writeString(bill.resolve("company.sql"),
                "select 'TesseraQL KK' as issuer\n;\n");
        Files.writeString(bill.resolve("invoice.html"), """
                <html xmlns:th="http://www.thymeleaf.org">
                <head><title>Invoice</title></head>
                <body>
                  <h1 th:text="${customer.first.customer}">Customer</h1>
                  <p th:text="${company.first.issuer}">Issuer</p>
                  <table>
                    <tr th:each="row : ${main.rows}">
                      <td th:text="${row.item}">item</td>
                    </tr>
                  </table>
                </body>
                </html>
                """);
        Files.writeString(print.resolve("header.sql"),
                "select order_no, customer from orders order by order_no\n;\n");

        Files.writeString(print.resolve("order.html"), """
                <html xmlns:th="http://www.thymeleaf.org">
                <head><title>Order</title></head>
                <body>
                  <h1 th:text="'Order ' + ${header.first.order_no}">Order</h1>
                  <p th:text="${header.first.customer}">Customer</p>
                  <table>
                    <tr th:each="row : ${main.rows}">
                      <td th:text="${row.item}">item</td>
                      <td th:text="${row.qty}">qty</td>
                    </tr>
                  </table>
                </body>
                </html>
                """);

        // A header source with its own params: (docs/audit-low-leads.md G28), on both executing
        // paths — the inline query-export and the asynchronous file-export.
        Path printOne = home.resolve("web/api/orders/print-one");
        Files.createDirectories(printOne);
        Files.writeString(printOne.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.printOne
                kind: route
                recipe: query-export
                input:
                  order: { type: string, required: true }
                sources:
                  main:
                    sql:
                      file: lines.sql
                      params:
                        order_no: params.order
                  header:
                    sql:
                      file: header-one.sql
                      params:
                        no: params.order
                export:
                  format: pdf
                  filename: order.pdf
                  template: order.html
                  maxRows: 100
                  columns:
                    - { name: item, label: Item }
                    - { name: qty,  label: Qty }
                """);
        Files.writeString(printOne.resolve("lines.sql"),
                "select item, qty from order_lines where order_no = /* order_no */ 'x' order by item\n;\n");
        // The header binds a name main does not declare, so its own params: are what reach it.
        Files.writeString(printOne.resolve("header-one.sql"),
                "select order_no, customer from orders where order_no = /* no */ 'x'\n;\n");
        Files.copy(print.resolve("order.html"), printOne.resolve("order.html"));

        Path file = home.resolve("web/api/orders/file");
        Files.createDirectories(file);
        Files.writeString(file.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.file
                kind: route
                recipe: file-export
                input:
                  order: { type: string, required: true }
                sources:
                  main:
                    sql:
                      file: lines.sql
                      params:
                        order_no: params.order
                  header:
                    sql:
                      file: header-one.sql
                      params:
                        no: params.order
                export:
                  format: pdf
                  filename: order.pdf
                  template: order.html
                  maxRows: 100
                  columns:
                    - { name: item, label: Item }
                    - { name: qty,  label: Qty }
                """);
        Files.copy(printOne.resolve("lines.sql"), file.resolve("lines.sql"));
        Files.copy(printOne.resolve("header-one.sql"), file.resolve("header-one.sql"));
        Files.copy(print.resolve("order.html"), file.resolve("order.html"));

        // A scoped header source (G30): the buyer's claim confines the header to their customer.
        Files.createDirectories(home.resolve("scope"));
        Files.writeString(home.resolve("scope/orders_scope.yml"), """
                version: tesseraql/v1
                id: orders_scope
                kind: scope
                match:
                  - when: { role: buyer }
                    file: own_customer.sql
                    params:
                      customer: principal.claim.customer
                """);
        Files.writeString(home.resolve("scope/own_customer.sql"),
                "$.customer = /* customer */ 'x'\n");
        Path mine = home.resolve("web/api/orders/mine");
        Files.createDirectories(mine);
        Files.writeString(mine.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.mine
                kind: route
                recipe: query-export
                security:
                  auth: bearer
                input:
                  order: { type: string, required: true }
                sources:
                  main:
                    sql:
                      file: lines.sql
                      params:
                        order_no: params.order
                  header:
                    sql:
                      file: header-mine.sql
                      params:
                        order_no: params.order
                export:
                  format: pdf
                  filename: order.pdf
                  template: order.html
                  maxRows: 100
                  columns:
                    - { name: item, label: Item }
                    - { name: qty,  label: Qty }
                """);
        Files.copy(printOne.resolve("lines.sql"), mine.resolve("lines.sql"));
        Files.writeString(mine.resolve("header-mine.sql"),
                "select order_no, customer from orders where order_no = /* order_no */ 'x'"
                        + " and /*%scope orders_scope on orders */ (1=1)\n;\n");
        Files.copy(print.resolve("order.html"), mine.resolve("order.html"));
        return home;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
