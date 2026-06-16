package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * The Phase 21 acceptance flow, end to end: with tesseraql-pdf on the classpath, a
 * {@code query-export} route with {@code format: pdf} streams a printable document - the
 * colocated XHTML template renders with page-oriented CSS and the app's embedded CJK font, and
 * the same data downloads as byte-identical PDFs (design ch. 48).
 */
@Testcontainers
class PdfExportIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String FONT = "TesseraQLSampleGothic-Regular.ttf";

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
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
    void printableUserListRendersTheCjkTemplateWithPageNumbers() throws Exception {
        HttpResponse<byte[]> response = download();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("application/pdf");
        assertThat(response.headers().firstValue("content-disposition").orElse(""))
                .contains("users.pdf");
        assertThat(response.body()).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
        try (PDDocument document = Loader.loadPDF(response.body())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("利用者一覧", "氏名", "状態", "佐藤花子", "有効",
                    "田中太郎", "Page 1 / 1");
            assertThat(document.getDocumentInformation().getProducer()).isEqualTo("TesseraQL");
        }
    }

    @Test
    void theSameDataDownloadsAsByteIdenticalPdfs() throws Exception {
        assertThat(download().body()).isEqualTo(download().body());
    }

    private static HttpResponse<byte[]> download() throws Exception {
        return HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/api/users/print")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-pdf-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: pdf-demo
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__users.sql"), """
                create table users (name varchar(100) primary key, status varchar(20));
                insert into users (name, status) values ('佐藤花子', '有効');
                insert into users (name, status) values ('田中太郎', '無効');
                """);

        Files.createDirectories(home.resolve("fonts"));
        try (InputStream font = PdfExportIntegrationTest.class
                .getResourceAsStream("/fonts/" + FONT)) {
            Files.copy(font, home.resolve("fonts").resolve(FONT));
        }

        Path printRoute = home.resolve("web/api/users/print");
        Files.createDirectories(printRoute);
        Files.writeString(printRoute.resolve("get.yml"), """
                version: tesseraql/v1
                id: users.print
                kind: route
                recipe: query-export
                sql:
                  file: print.sql
                export:
                  format: pdf
                  filename: users.pdf
                  template: print.html
                  columns:
                    - { name: name,   header: 氏名 }
                    - { name: status, header: 状態 }
                """);
        Files.writeString(printRoute.resolve("print.sql"),
                "select name, status from users order by name\n;\n");
        Files.writeString(printRoute.resolve("print.html"), """
                <html xmlns:th="http://www.thymeleaf.org">
                <head>
                  <title>利用者一覧</title>
                  <style>
                    @page {
                      size: A4;
                      margin: 20mm 15mm;
                      @bottom-center {
                        content: "Page " counter(page) " / " counter(pages);
                        font-size: 8pt;
                      }
                    }
                    body { font-family: 'TesseraQL Sample Gothic'; font-size: 10pt; }
                    table { width: 100%; border-collapse: collapse; }
                    th, td { border: 0.5pt solid #444444; padding: 3pt 5pt; }
                  </style>
                </head>
                <body>
                  <h1>利用者一覧</h1>
                  <table>
                    <thead>
                      <tr><th th:each="column : ${columns}" th:text="${column.header}">h</th></tr>
                    </thead>
                    <tbody>
                      <tr th:each="row : ${rows}">
                        <td th:text="${row.name}">name</td>
                        <td th:text="${row.status}">status</td>
                      </tr>
                    </tbody>
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

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
        }
    }
}
