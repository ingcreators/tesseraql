package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.operations.batch.JobStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The job arm of the export-declaration refusal (docs/export-declarations.md decision 1): a
 * job step's {@code export:} literals are judged where the job is registered, inside
 * {@code TesseraqlRuntime.start}, so a typo refuses the boot with the same code and message
 * the linter reports — naming the app, the job and the step — instead of failing every firing
 * with {@code TQL-LD-2810}. Routes are the compiler's arm; jobs never reach it.
 *
 * <p>The valid twin boots AND runs the job to {@code COMPLETED}: a boot that succeeds for a
 * file the runtime never registered would satisfy a boot-only assertion (MEASUREMENT.md
 * hazard 19).
 */
@Testcontainers
class ExportDeclarationBootTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /** slf4j-simple writes each line to {@code System.err}; the boot warnings land here. */
    private static final ByteArrayOutputStream LOG = new ByteArrayOutputStream();

    private static PrintStream realErr;

    @BeforeAll
    static void captureTheLog() {
        realErr = System.err;
        System.setErr(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                LOG.write(b);
                realErr.write(b);
            }
        }, true));
    }

    @AfterAll
    static void releaseTheLog() {
        System.setErr(realErr);
    }

    @BeforeEach
    void freshLog() {
        LOG.reset();
    }

    @Test
    void aMistypedZoneOnAJobStepRefusesTheBootNamingTheStep(@TempDir Path dir) throws Exception {
        Path appHome = appHome(dir, "export-boot-a",
                "      format: csv\n      timezone: Asia/Tokio\n");

        assertThatThrownBy(() -> TesseraqlRuntime.start(appHome, 0))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("app 'export-boot-a'")
                .hasMessageContaining("job 'report.daily' step 'report'")
                .hasMessageContaining("export.timezone")
                .hasMessageContaining("'Asia/Tokio'");
    }

    @Test
    void aSourceExpressionOnAJobStepRefusesTheBoot(@TempDir Path dir) throws Exception {
        // docs/jobs.md: a step's locale:/timezone: are literals — enforced for the first time.
        Path appHome = appHome(dir, "export-boot-b",
                "      format: csv\n      timezone: query.tz\n");

        assertThatThrownBy(() -> TesseraqlRuntime.start(appHome, 0))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("job 'report.daily' step 'report'")
                .hasMessageContaining("a job has no request");
    }

    @Test
    void theValidTwinBootsAndRunsTheJobToCompletion(@TempDir Path dir) throws Exception {
        Path appHome = appHome(dir, "export-boot-c",
                "      format: csv\n      timezone: Asia/Tokyo\n      locale: ja-JP\n");

        try (TesseraqlRuntime runtime = TesseraqlRuntime.start(appHome, 0)) {
            assertThat(runtime.runJob("report.daily", Map.of()).status())
                    .isEqualTo(JobStatus.COMPLETED);
        }
        assertThat(LOG.toString(java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("job 'report.daily' step 'report' export.");
    }

    @Test
    void anInertKeyOnAJobStepWarnsAtBootAndTheJobStillRuns(@TempDir Path dir) throws Exception {
        // Decision 2: the linter refuses locale: on a workbook; the runtime writes the
        // workbook without it, so the boot says so on one line and continues.
        // The column carries an Excel-native cell format (Excel's vocabulary, not Java's):
        // the workbook writes the string verbatim as the cell's number format, so the JDK
        // parsers must never judge it here.
        Path appHome = appHome(dir, "export-boot-d", """
                      format: excel
                      locale: ja-JP
                      columns:
                        - { name: id, type: number, format: '0.00E+00' }
                        - { name: created, type: date, format: d-mmm-yy }
                """);

        try (TesseraqlRuntime runtime = TesseraqlRuntime.start(appHome, 0)) {
            assertThat(runtime.runJob("report.daily", Map.of()).status())
                    .isEqualTo(JobStatus.COMPLETED);
        }
        assertThat(LOG.toString(java.nio.charset.StandardCharsets.UTF_8).lines()
                .filter(line -> line.contains("WARN")).toList())
                .anySatisfy(line -> assertThat(line).contains("app 'export-boot-d'",
                        "job 'report.daily' step 'report'", "export.locale",
                        "drives nothing in a workbook"));
        assertThat(workbookStyles(appHome)).contains("d-mmm-yy").contains("0.00E+00");
    }

    @Test
    void aMistypedConfigKeyRefusesAHotReloadAsAWholeAndTheRoutesKeepServing(@TempDir Path dir)
            throws Exception {
        // A hot reload compiles one route at a time; judged inside every compile, a typo in an
        // app-wide key stubbed EVERY route of the app with TQL-ROUTE-3103 (pages included).
        // The reloader judges the key once, before its loop: the reload is refused as a whole
        // naming the key, and the last good routes keep serving.
        Path appHome = routeAppHome(dir, "export-boot-g");

        try (TesseraqlRuntime runtime = TesseraqlRuntime.start(appHome, 0)) {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest list = java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://localhost:" + runtime.port() + "/api/items/list"))
                    .GET().build();
            assertThat(client.send(list, java.net.http.HttpResponse.BodyHandlers.ofString())
                    .statusCode()).isEqualTo(200);

            Path config = appHome.resolve("config/application.yml");
            Files.writeString(config, Files.readString(config)
                    + "  files:\n    timezone: Asia/Tokio\n");
            RouteReloader reloader = runtime.context().lookup(
                    io.tesseraql.pipeline.TesseraqlProperties.RUNTIME_SEAMS_BEAN,
                    RuntimeSeams.class).reloader();

            assertThatThrownBy(reloader::reload)
                    .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                    .hasMessageContaining("TQL-YAML-1063")
                    .hasMessageContaining("tesseraql.files.timezone")
                    .hasMessageContaining("'Asia/Tokio'");
            assertThat(client.send(list, java.net.http.HttpResponse.BodyHandlers.ofString())
                    .statusCode()).as("the last good routes keep serving").isEqualTo(200);

            Files.writeString(config, Files.readString(config).replace("Asia/Tokio", "Asia/Tokyo"));
            assertThat(reloader.reload().failed()).isEmpty();
        }
    }

    private static Path routeAppHome(Path dir, String name) throws IOException {
        Path target = dir.resolve(name);
        writeConfig(target, name, "");
        Path route = Files.createDirectories(target.resolve("web/api/items/list"));
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                security:
                  auth: public
                response:
                  json:
                    body:
                      data: main.rows
                sources:
                  main:
                    sql:
                      file: list.sql
                """);
        Files.writeString(route.resolve("list.sql"), "select 1 as id\n");
        Path export = Files.createDirectories(target.resolve("web/api/items/dump"));
        Files.writeString(export.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.dump
                kind: route
                recipe: query-export
                security:
                  auth: public
                export:
                  format: csv
                  columns:
                    - { name: created, type: datetime }
                sources:
                  main:
                    sql:
                      file: dump.sql
                      mode: query-export
                """);
        Files.writeString(export.resolve("dump.sql"), "select now() as created\n");
        return target;
    }

    /** The {@code xl/styles.xml} of the one workbook the job wrote under the app home. */
    private static String workbookStyles(Path appHome) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.walk(appHome)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (Files.size(file) < 4 || !"PK".equals(new String(
                        java.util.Arrays.copyOf(Files.readAllBytes(file), 2),
                        java.nio.charset.StandardCharsets.US_ASCII))) {
                    continue;
                }
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
                    java.util.zip.ZipEntry styles = zip.getEntry("xl/styles.xml");
                    if (styles != null) {
                        return new String(zip.getInputStream(styles).readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
        }
        throw new AssertionError("no workbook written under " + appHome);
    }

    @Test
    void aMistypedImportLocaleOnAPollJobRefusesTheBootNamingTheJob(@TempDir Path dir)
            throws Exception {
        Path appHome = pollJobAppHome(dir, "export-boot-e", "de_DE");

        assertThatThrownBy(() -> TesseraqlRuntime.start(appHome, 0))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("job 'orders.intake'")
                .hasMessageContaining("import.locale")
                .hasMessageContaining("'de_DE'");
        try (TesseraqlRuntime runtime = TesseraqlRuntime.start(
                pollJobAppHome(dir, "export-boot-f", "de-DE"), 0)) {
            assertThat(runtime.port()).isPositive();
        }
    }

    private static Path appHome(Path dir, String name, String exportBody) throws IOException {
        Path target = dir.resolve(name);
        writeConfig(target, name, "");
        Files.createDirectories(target.resolve("batch/report"));
        Files.writeString(target.resolve("batch/report/job.yml"), """
                version: tesseraql/v1
                id: report.daily
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: report
                    sql:
                      file: report.sql
                      mode: query
                    export:
                %s""".formatted(exportBody));
        Files.writeString(target.resolve("batch/report/report.sql"),
                "select 1 as id, now() as created\n");
        return target;
    }

    private static Path pollJobAppHome(Path dir, String name, String locale) throws IOException {
        Path target = dir.resolve(name);
        writeConfig(target, name, """
                  connectors:
                    poll:
                      allowedPaths:
                        - inbound
                """);
        Files.createDirectories(target.resolve("inbound"));
        Files.createDirectories(target.resolve("batch/intake"));
        Files.writeString(target.resolve("batch/intake/upsert.sql"), "select 1\n");
        Files.writeString(target.resolve("batch/intake/job.yml"), """
                version: tesseraql/v1
                id: orders.intake
                kind: job
                recipe: file-import
                trigger:
                  poll:
                    transport: local
                    path: inbound
                    consumeOnce: true
                import:
                  format: csv
                  locale: %s
                pipeline:
                  - id: row
                    sql:
                      file: upsert.sql
                """.formatted(locale));
        return target;
    }

    private static void writeConfig(Path target, String name, String tail) throws IOException {
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: %s
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                %s""".formatted(name, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), tail));
    }
}
