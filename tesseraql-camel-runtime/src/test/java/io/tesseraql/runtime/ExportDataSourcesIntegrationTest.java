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
        port = freePort();
        runtime = TesseraqlRuntime.start(appHome, port);
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
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
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
                export:
                  format: pdf
                  filename: order.pdf
                  template: order.html
                  maxRows: 100
                  columns:
                    - { name: item, label: Item }
                    - { name: qty,  label: Qty }
                  header:
                    sql:
                      file: header.sql
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
                export:
                  format: pdf
                  filename: invoice-{key}.pdf
                  template: invoice.html
                  maxRows: 100
                  splitBy: order_no
                  columns:
                    - { name: item, label: Item }
                    - { name: qty,  label: Qty }
                  customer:
                    sql:
                      file: customers.sql
                  company:
                    sql:
                      file: company.sql
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
                    <tr th:each="row : ${sql.rows}">
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
                    <tr th:each="row : ${sql.rows}">
                      <td th:text="${row.item}">item</td>
                      <td th:text="${row.qty}">qty</td>
                    </tr>
                  </table>
                </body>
                </html>
                """);
        return home;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
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
