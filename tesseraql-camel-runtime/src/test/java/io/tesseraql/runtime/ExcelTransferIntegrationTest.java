package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end test for the optional Excel codec (design ch. 28, 47): with tesseraql-excel on the
 * classpath, {@code format: excel} routes import uploaded xlsx workbooks and export query results
 * as xlsx - no other configuration.
 */
@Testcontainers
class ExcelTransferIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

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
    void xlsxUploadImportsAndXlsxExportRoundTrips() throws Exception {
        // Import an uploaded workbook.
        HttpResponse<String> accepted = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + runtime.port() + "/api/people/import"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(workbook()))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).isEqualTo(202);
        String importId = MAPPER.readTree(accepted.body()).get("transferId").asText();
        assertThat(awaitTerminal("/api/people/import/" + importId).get("status").asText())
                .isEqualTo("COMPLETED");
        assertThat(personCount()).isEqualTo(2);

        // Export the table back as a workbook and parse it.
        HttpResponse<String> started = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + runtime.port() + "/api/people/export"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        String exportId = MAPPER.readTree(started.body()).get("transferId").asText();
        JsonNode status = awaitTerminal("/api/people/export/" + exportId);
        assertThat(status.get("status").asText()).isEqualTo("COMPLETED");

        HttpResponse<byte[]> file = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + runtime.port()
                                + "/api/people/export/" + exportId + "/file")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.headers().firstValue("content-type").orElse(""))
                .contains("spreadsheetml");
        try (XSSFWorkbook exported = new XSSFWorkbook(new ByteArrayInputStream(file.body()))) {
            Sheet sheet = exported.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("full_name");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Anne");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Ben");
        }
    }

    @Test
    void placementModeExportLandsColumnsAtYamlDeclaredPositions() throws Exception {
        // Reuses the imported people; runs after the round-trip test by method order is not
        // guaranteed, so seed independently.
        HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + runtime.port() + "/api/people/import"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(workbook()))
                .build(), HttpResponse.BodyHandlers.ofString());
        Thread.sleep(300);

        HttpResponse<String> started = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + runtime.port() + "/api/people/report"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        String transferId = MAPPER.readTree(started.body()).get("transferId").asText();
        assertThat(awaitTerminal("/api/people/report/" + transferId).get("status").asText())
                .isEqualTo("COMPLETED");

        HttpResponse<byte[]> file = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + runtime.port()
                                + "/api/people/report/" + transferId + "/file")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        try (XSSFWorkbook exported = new XSSFWorkbook(new ByteArrayInputStream(file.body()))) {
            Sheet sheet = exported.getSheetAt(0);
            // The template's frame survives; data landed at the YAML-declared B3/D3.
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue())
                    .isEqualTo("People Report");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("Anne");
            assertThat(sheet.getRow(2).getCell(3).getNumericCellValue()).isEqualTo(34.0);
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("Ben");
        }
    }

    private static byte[] workbook() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("people");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("fullName");
            header.createCell(1).setCellValue("age");
            Row anne = sheet.createRow(1);
            anne.createCell(0).setCellValue("Anne");
            anne.createCell(1).setCellValue(34);
            Row ben = sheet.createRow(2);
            ben.createCell(0).setCellValue("Ben");
            ben.createCell(1).setCellValue(41);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static JsonNode awaitTerminal(String statusPath) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (true) {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + runtime.port() + statusPath)).build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode status = MAPPER.readTree(response.body());
            String value = status.get("status").asText();
            if (!"RUNNING".equals(value) && !"STARTED".equals(value)) {
                return status;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Transfer did not finish: " + status);
            }
            Thread.sleep(100);
        }
    }

    private static int personCount() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select count(*) from people")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-excel-it");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: excel-demo
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__people.sql"),
                "create table people (full_name varchar(100) primary key, age integer);\n");

        Path importRoute = home.resolve("web/api/people/import");
        Files.createDirectories(importRoute);
        Files.writeString(importRoute.resolve("post.yml"), """
                version: tesseraql/v1
                id: people.import
                kind: route
                recipe: file-import
                import:
                  format: excel
                  columns: [fullName, age]
                  sql:
                    file: upsert-person.sql
                """);
        Files.writeString(importRoute.resolve("upsert-person.sql"), """
                insert into people (full_name, age)
                values ( /* fullName */ 'sample', cast( /* age */ '1' as integer) )
                on conflict (full_name) do update set age = excluded.age
                ;
                """);

        Path exportRoute = home.resolve("web/api/people/export");
        Files.createDirectories(exportRoute);
        Files.writeString(exportRoute.resolve("post.yml"), """
                version: tesseraql/v1
                id: people.export
                kind: route
                recipe: file-export
                export:
                  format: excel
                  filename: people.xlsx
                  sql:
                    file: select-people.sql
                """);
        Files.writeString(exportRoute.resolve("select-people.sql"),
                "select full_name, age from people order by full_name\n;\n");

        // Placement mode: the YAML declares where each query column lands in the styled
        // template, so the file-to-SQL correspondence stays reviewable in the route definition.
        Path reportRoute = home.resolve("web/api/people/report");
        Files.createDirectories(reportRoute);
        Files.writeString(reportRoute.resolve("post.yml"), """
                version: tesseraql/v1
                id: people.report
                kind: route
                recipe: file-export
                export:
                  format: excel
                  filename: people-report.xlsx
                  template: report-frame.xlsx
                  startCell: B3
                  columns:
                    - { name: full_name, column: B }
                    - { name: age,       column: D }
                  sql:
                    file: select-people.sql
                """);
        Files.writeString(reportRoute.resolve("select-people.sql"),
                "select full_name, age from people order by full_name\n;\n");
        try (XSSFWorkbook frame = new XSSFWorkbook();
                java.io.OutputStream out = Files.newOutputStream(
                        reportRoute.resolve("report-frame.xlsx"))) {
            Sheet sheet = frame.createSheet("report");
            sheet.createRow(0).createCell(1).setCellValue("People Report");
            Row header = sheet.createRow(1);
            header.createCell(1).setCellValue("Name");
            header.createCell(3).setCellValue("Age");
            frame.write(out);
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

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
