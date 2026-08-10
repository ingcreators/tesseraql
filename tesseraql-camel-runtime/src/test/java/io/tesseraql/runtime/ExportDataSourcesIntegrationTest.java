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
                insert into order_lines (order_no, item, qty) values ('SO-1001', 'widget', 3);
                insert into order_lines (order_no, item, qty) values ('SO-1001', 'gadget', 5);
                """);

        Path print = home.resolve("web/api/orders/print");
        Files.createDirectories(print);
        Files.writeString(print.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.print
                kind: route
                recipe: query-export
                sql:
                  file: lines.sql
                export:
                  format: pdf
                  filename: order.pdf
                  template: order.html
                  maxRows: 100
                  queries:
                    header:
                      file: header.sql
                  columns:
                    - { name: item, label: Item }
                    - { name: qty,  label: Qty }
                """);
        // The line query carries no customer column: that is the point of the header query.
        Files.writeString(print.resolve("lines.sql"),
                "select item, qty from order_lines order by item\n;\n");
        Files.writeString(print.resolve("header.sql"),
                "select order_no, customer from orders order by order_no\n;\n");
        Files.writeString(print.resolve("order.html"), """
                <html xmlns:th="http://www.thymeleaf.org">
                <head><title>Order</title></head>
                <body>
                  <h1 th:text="'Order ' + ${header.rows[0].order_no}">Order</h1>
                  <p th:text="${header.rows[0].customer}">Customer</p>
                  <table>
                    <tr th:each="row : ${rows}">
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
