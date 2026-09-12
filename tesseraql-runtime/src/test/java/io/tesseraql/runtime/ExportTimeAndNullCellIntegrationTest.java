package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobStatus;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The codec renders every value the driver hands it (docs/export-declarations.md, PR 5-0). A
 * PostgreSQL {@code time} column reaches the codecs as {@code java.sql.Time} - the shape five of
 * the six drivers hand, whose {@code toInstant()} throws - and a NULL reaches the Excel grid and
 * placement writers before their own null arm. Both answered 500 on every export surface, FAILED
 * a file-export transfer and FAILED the job step; the unit guards prove the arms, this proves
 * the wire: 200, the cell text, COMPLETED, and the same wall clock under a JVM whose zone is not
 * UTC. DuckDB's {@code LocalTime} and a NULL time are green on the old code (MEASUREMENT hazard
 * 24) - the fixture selects a value, from PostgreSQL.
 */
@Testcontainers
class ExportTimeAndNullCellIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** The day fraction of 22:30 - what a real Excel time cell holds. */
    private static final double TWENTY_TWO_THIRTY = 0.9375;

    static TesseraqlRuntime runtime;
    static Path appHome;
    static int port;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
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
    void aTimeColumnExportsAsWallClockTextOnCsv() throws Exception {
        HttpResponse<byte[]> csv = get("/api/shifts/csv");

        assertThat(csv.statusCode()).isEqualTo(200);
        // The value row, the NULL row (two empty cells, still a row), the row after it.
        assertThat(new String(csv.body(), StandardCharsets.UTF_8))
                .contains("late,22:30:00\r\n,\r\nearly,06:15:00\r\n");
    }

    /**
     * pgjdbc builds the Time in the JVM's default zone at read time (MEASUREMENT hazard 3: it
     * follows TimeZone.setDefault per call), so a codec that decodes the Time as UTC seconds is
     * right on the UTC host CI runs on and wrong everywhere else, and one that zones the Time
     * into a declared {@code timezone:} as an instant is wrong only where the two zones differ.
     * The flip to JST, against the undeclared csv and the grid declared {@code UTC}, makes this
     * class see both defects on any host; sequential JUnit, restored in finally.
     */
    @Test
    void aTimeColumnKeepsItsWallClockOnAJvmWhoseZoneIsNotUtc() throws Exception {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            HttpResponse<byte[]> csv = get("/api/shifts/csv");
            HttpResponse<byte[]> grid = get("/api/shifts/grid");

            assertThat(csv.statusCode()).isEqualTo(200);
            assertThat(new String(csv.body(), StandardCharsets.UTF_8))
                    .as("the wall clock under a JST JVM").contains("late,22:30:00\r\n");
            assertThat(grid.statusCode()).isEqualTo(200);
            try (XSSFWorkbook workbook = new XSSFWorkbook(
                    new ByteArrayInputStream(grid.body()))) {
                assertThat(workbook.getSheetAt(0).getRow(1).getCell(1).getNumericCellValue())
                        .as("the wall clock under a JST JVM and timezone: UTC")
                        .isEqualTo(TWENTY_TWO_THIRTY);
            }
        } finally {
            TimeZone.setDefault(before);
        }
    }

    @Test
    void aTimeColumnAndANullRowExportOnAnExcelGrid() throws Exception {
        HttpResponse<byte[]> grid = get("/api/shifts/grid");

        assertThat(grid.statusCode()).isEqualTo(200);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(grid.body()))) {
            Sheet sheet = workbook.getSheetAt(0);
            Cell starts = sheet.getRow(1).getCell(1);
            assertThat(starts.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(starts.getNumericCellValue()).isEqualTo(TWENTY_TWO_THIRTY);
            assertThat(DateUtil.isCellDateFormatted(starts)).isTrue();
            // The NULL row (text, typed number, typed datetime, time): every cell blank.
            Row nulls = sheet.getRow(2);
            for (int i = 0; i < 4; i++) {
                Cell cell = nulls == null ? null : nulls.getCell(i);
                assertThat(cell == null || cell.getCellType() == CellType.BLANK)
                        .as("NULL cell %d is blank", i).isTrue();
            }
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("early");
        }
    }

    @Test
    void aTimeColumnAndANullRowExportInPlacementMode() throws Exception {
        HttpResponse<byte[]> placed = get("/api/shifts/placement");

        assertThat(placed.statusCode()).isEqualTo(200);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(placed.body()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Shift Report");
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("late");
            assertThat(sheet.getRow(4).getCell(3).getNumericCellValue())
                    .isEqualTo(TWENTY_TWO_THIRTY);
            Row nulls = sheet.getRow(5);
            for (int col : new int[]{1, 3, 5}) {
                Cell cell = nulls.getCell(col);
                assertThat(cell == null || cell.getCellType() == CellType.BLANK)
                        .as("NULL cell at column %d is blank", col).isTrue();
            }
            assertThat(sheet.getRow(6).getCell(1).getStringCellValue()).isEqualTo("early");
        }
    }

    @Test
    void aTimeColumnExportsAsWallClockTextOnPdf() throws Exception {
        HttpResponse<byte[]> pdf = get("/api/shifts/pdf");

        assertThat(pdf.statusCode()).isEqualTo(200);
        try (PDDocument document = Loader.loadPDF(pdf.body())) {
            assertThat(new PDFTextStripper().getText(document)).contains("late", "22:30:00");
        }
    }

    /** The async arm: a file-export transfer over the same rows finishes, with the workbook. */
    @Test
    void aFileExportExcelGridWithANullRowAndATimeCompletes() throws Exception {
        HttpResponse<String> started = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/shifts/file-grid"))
                .header("Content-Type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        String id = MAPPER.readTree(started.body()).get("transferId").asText();
        JsonNode status = awaitTerminal("/api/shifts/file-grid/" + id);
        assertThat(status.get("status").asText()).as(status.toString()).isEqualTo("COMPLETED");
        HttpResponse<byte[]> file = get("/api/shifts/file-grid/" + id + "/file");
        assertThat(file.statusCode()).isEqualTo(200);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.body()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(1).getNumericCellValue())
                    .isEqualTo(TWENTY_TWO_THIRTY);
            Row nulls = sheet.getRow(2);
            for (int i = 0; i < 4; i++) {
                Cell cell = nulls == null ? null : nulls.getCell(i);
                assertThat(cell == null || cell.getCellType() == CellType.BLANK)
                        .as("NULL cell %d is blank", i).isTrue();
            }
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("early");
        }
    }

    /** The spool: a split grid carries the Time through SpooledRows and back into a time cell. */
    @Test
    void aSplitExcelGridCarriesTheTimeThroughTheSpool() throws Exception {
        HttpResponse<byte[]> zip = get("/api/shifts/split-grid");

        assertThat(zip.statusCode()).isEqualTo(200);
        int entries = 0;
        boolean lateSeen = false;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.body()))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries++;
                byte[] bytes = in.readAllBytes();
                if (!entry.getName().contains("late")) {
                    continue;
                }
                lateSeen = true;
                try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                    Cell starts = workbook.getSheetAt(0).getRow(1).getCell(1);
                    assertThat(starts.getCellType()).isEqualTo(CellType.NUMERIC);
                    assertThat(starts.getNumericCellValue()).isEqualTo(TWENTY_TWO_THIRTY);
                }
            }
        }
        assertThat(entries).isEqualTo(2);
        assertThat(lateSeen).as("the 'late' group's document").isTrue();
    }

    @Test
    void aJobExportStepOverATimeColumnCompletes() {
        JobExecution execution = runtime.runJob("shifts.export", Map.of());

        assertThat(execution.status()).as(execution.exitMessage()).isEqualTo(JobStatus.COMPLETED);
    }

    private static HttpResponse<byte[]> get(String path) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static JsonNode awaitTerminal(String statusPath) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (true) {
            JsonNode status = MAPPER.readTree(
                    new String(get(statusPath).body(), StandardCharsets.UTF_8));
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

    private static Path prepareAppHome() throws Exception {
        Path home = Files.createTempDirectory("export-time-null-app");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/tesseraql.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: shifts-demo
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__shifts.sql"), """
                create table shifts (id int primary key, label varchar(20), starts_at time,
                    fee numeric(10,2), held_on timestamp);
                insert into shifts values (1, 'late', '22:30:00', 12.50, '2026-01-15 22:30:00');
                insert into shifts values (2, null, null, null, null);
                insert into shifts values (3, 'early', '06:15:00', 3.00, '2026-01-16 06:15:00');
                """);
        String sql = "select label, starts_at, fee, held_on from shifts order by id";
        export(home, "csv", "api.shifts.csv", "query-export", sql, """
                  format: csv
                  filename: shifts.csv
                  columns:
                    - { name: label }
                    - { name: starts_at }
                """);
        // The typed NULLs of the grid: a number under a format and a datetime under a format,
        // beside the untyped text and the time - the four shapes toZoned met before its null arm.
        String gridBlock = """
                  format: excel
                  filename: shifts.xlsx
                  columns:
                    - { name: label }
                    - { name: starts_at }
                    - { name: fee, type: number, format: '#,##0.00' }
                    - { name: held_on, type: datetime, format: 'yyyy/mm/dd hh:mm' }
                """;
        // The grid declares a zone that differs from the flipped JVM zone below: a time of day
        // must not move with either.
        export(home, "grid", "api.shifts.grid", "query-export", sql,
                "  timezone: UTC\n" + gridBlock);
        export(home, "file-grid", "api.shifts.file-grid", "file-export", sql, gridBlock);
        export(home, "placement", "api.shifts.placement", "query-export", sql, """
                  format: excel
                  filename: shifts-report.xlsx
                  template: frame.xlsx
                  startCell: B5
                  columns:
                    - { name: label, column: B }
                    - { name: starts_at, column: D }
                    - { name: fee, column: F, type: number, format: '#,##0.00' }
                """);
        try (XSSFWorkbook frame = new XSSFWorkbook();
                OutputStream out = Files.newOutputStream(
                        home.resolve("web/api/shifts/placement/frame.xlsx"))) {
            Sheet sheet = frame.createSheet("report");
            sheet.createRow(0).createCell(1).setCellValue("Shift Report");
            Row header = sheet.createRow(3);
            header.createCell(1).setCellValue("Label");
            header.createCell(3).setCellValue("Starts");
            header.createCell(5).setCellValue("Fee");
            frame.write(out);
        }
        export(home, "pdf", "api.shifts.pdf", "query-export", sql, """
                  format: pdf
                  filename: shifts.pdf
                  columns:
                    - { name: label }
                    - { name: starts_at }
                """);
        // Two groups, so the split bundle holds two documents; the NULL-labelled row is left out
        // because a group needs a key.
        export(home, "split-grid", "api.shifts.split-grid", "query-export",
                "select label, starts_at from shifts where label is not null order by label", """
                          format: excel
                          filename: shifts-{key}.xlsx
                          splitBy: label
                          columns:
                            - { name: label }
                            - { name: starts_at }
                        """);
        Path job = home.resolve("batch/shifts");
        Files.createDirectories(job);
        Files.writeString(job.resolve("job.yml"), """
                version: tesseraql/v1
                id: shifts.export
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: extract
                    export:
                      format: csv
                      filename: shifts.csv
                      columns:
                        - { name: label }
                        - { name: starts_at }
                    sql:
                      file: shifts.sql
                      mode: query
                """);
        Files.writeString(job.resolve("shifts.sql"), sql + "\n");
        return home;
    }

    private static void export(Path home, String dir, String id, String recipe, String sql,
            String exportBlock) throws Exception {
        Path route = home.resolve("web/api/shifts/" + dir);
        Files.createDirectories(route);
        String verb = "file-export".equals(recipe) ? "post" : "get";
        Files.writeString(route.resolve(verb + ".yml"), """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: %s
                sources:
                  main:
                    sql:
                      file: shifts.sql
                export:
                %s""".formatted(id, recipe, exportBlock));
        Files.writeString(route.resolve("shifts.sql"), sql + "\n");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
        }
    }
}
