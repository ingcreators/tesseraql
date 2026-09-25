package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobStatus;
import java.io.IOException;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One codec set per application (docs/codec-discovery.md decision 1): a codec that arrives
 * through the module channel — a jar under {@code work/modules} — serves the synchronous
 * {@code query-export}, the asynchronous {@code file-export}, the job's export step and the
 * import page's file picker alike, and keeps serving after a hot reload.
 *
 * <p>Before this, the compiler discovered codecs on the thread context loader while the
 * transfer service discovered on the application's module loader, so the same
 * {@code format:} served one recipe and refused the whole application on the other: the
 * README's second quick start could not boot from the distribution archive. Red on that
 * runtime: the boot in {@link #start()} fails with {@code TQL-LD-2801} for {@code marker}.
 */
@Testcontainers
class ModuleCodecIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();
    private static final List<String> WATCH_LINES = new CopyOnWriteArrayList<>();

    static TesseraqlRuntime runtime;
    static Path appHome;
    static int port;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
        port = runtime.port();
        runtime.watchRoutes(WATCH_LINES::add);
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            try (Stream<Path> files = Files.walk(appHome)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /** The synchronous route: the arm that discovered on the wrong loader. */
    @Test
    void aQueryExportWritesThroughTheModuleCodec() throws Exception {
        HttpResponse<byte[]> download = get("/api/items/export");

        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("Content-Type").orElse(""))
                .isEqualTo("text/x-marker; charset=utf-8");
        assertThat(download.headers().firstValue("Content-Disposition").orElse(""))
                .contains(".marker");
        assertThat(body(download)).startsWith(MarkerFileCodec.MARKER + "\nname|qty\n")
                .contains("alpha|1\n").contains("beta|2\n");
    }

    /** The asynchronous route, the arm that always worked: the same set, the same bytes. */
    @Test
    void aFileExportWritesThroughTheSameCodec() throws Exception {
        HttpResponse<byte[]> started = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/items/file"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(started.statusCode()).as(body(started)).isEqualTo(202);
        String id = MAPPER.readTree(started.body()).get("transferId").asString();
        JsonNode status = awaitTerminal("/api/items/file/" + id);
        assertThat(status.get("status").asString()).as(status.toString()).isEqualTo("COMPLETED");
        HttpResponse<byte[]> file = get("/api/items/file/" + id + "/file");
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(body(file)).startsWith(MarkerFileCodec.MARKER + "\nname|qty\n");
    }

    /** The job arm: the step completes, and it was this codec that wrote. */
    @Test
    void aJobExportStepWritesThroughTheSameCodec() {
        int before = MarkerFileCodec.WRITES.get();

        JobExecution execution = runtime.runJob("items.report", Map.of());

        assertThat(execution.status()).as(execution.exitMessage()).isEqualTo(JobStatus.COMPLETED);
        assertThat(MarkerFileCodec.WRITES.get()).isGreaterThan(before);
    }

    /**
     * The import page's file picker filters on the module codec's extension and media type
     * (docs/csv-import.md decision 8) — the binding's discovery rendered no {@code accept} at all
     * for a module format.
     */
    @Test
    void anImportPageAcceptsTheModuleCodecsFiles() throws Exception {
        HttpResponse<byte[]> page = get("/items/import");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(body(page)).contains("accept=\".marker,text/x-marker\"");
    }

    /** A hot reload compiles with the boot's set: the route it rebuilds keeps its codec. */
    @Test
    void aReloadedQueryExportStillWritesThroughTheModuleCodec() throws Exception {
        Path route = appHome.resolve("web/api/items/export/get.yml");
        Files.writeString(route, Files.readString(route)
                .replace("filename: items.marker", "filename: items-again.marker"));
        awaitReload();

        HttpResponse<byte[]> download = get("/api/items/export");

        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("Content-Disposition").orElse(""))
                .as("the reload took effect").contains("items-again.marker");
        assertThat(body(download)).startsWith(MarkerFileCodec.MARKER + "\n");
    }

    private static void awaitReload() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (WATCH_LINES.stream().anyMatch(line -> line.contains("reloaded routes"))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the watcher never reported a reload: " + WATCH_LINES);
    }

    private static HttpResponse<byte[]> get(String path) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String body(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static JsonNode awaitTerminal(String statusPath) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (true) {
            JsonNode status = MAPPER.readTree(body(get(statusPath)));
            String value = status.get("status").asString();
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
        Path home = Files.createTempDirectory("module-codec-app");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/tesseraql.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: module-codec-app
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path migrations = home.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__items.sql"), """
                create table items (name varchar(100) primary key, qty integer not null);
                insert into items (name, qty) values ('alpha', 1), ('beta', 2);
                """);
        writeModuleJar(home);

        Path export = Files.createDirectories(home.resolve("web/api/items/export"));
        Files.writeString(export.resolve("get.yml"), """
                version: tesseraql/v1
                id: api.items.export
                kind: route
                recipe: query-export
                sources:
                  main:
                    sql:
                      file: items.sql
                export:
                  format: marker
                  filename: items.marker
                """);
        Files.writeString(export.resolve("items.sql"),
                "select name, qty from items order by name\n");

        Path file = Files.createDirectories(home.resolve("web/api/items/file"));
        Files.writeString(file.resolve("post.yml"), """
                version: tesseraql/v1
                id: api.items.file
                kind: route
                recipe: file-export
                sources:
                  main:
                    sql:
                      file: items.sql
                export:
                  format: marker
                  filename: items.marker
                """);
        Files.writeString(file.resolve("items.sql"),
                "select name, qty from items order by name\n");

        Path imports = Files.createDirectories(home.resolve("web/items/import"));
        Files.writeString(imports.resolve("items-import.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: import
                title: Import items
                action: /items/import
                """);
        Files.writeString(imports.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.importPage
                kind: route
                recipe: page
                response:
                  html:
                    view: items-import
                """);
        Files.writeString(imports.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.import
                kind: route
                recipe: file-import
                import:
                  format: marker
                  columns:
                    - name
                    - { name: qty, type: number }
                steps:
                  - id: row
                    sql:
                      file: upsert-item.sql
                """);
        Files.writeString(imports.resolve("upsert-item.sql"), """
                insert into items (name, qty)
                values ( /* name */ 'sample', cast( /* qty */ '1' as integer) )
                on conflict (name) do update set qty = excluded.qty
                """);

        Path job = Files.createDirectories(home.resolve("batch/report"));
        Files.writeString(job.resolve("job.yml"), """
                version: tesseraql/v1
                id: items.report
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: report
                    sql:
                      file: items.sql
                      mode: query
                    export:
                      format: marker
                      filename: report.marker
                """);
        Files.writeString(job.resolve("items.sql"),
                "select name, qty from items order by name\n");
        return home;
    }

    /**
     * The module: a jar holding only the {@code META-INF/services} entry. The provider class is
     * on the test classpath (the parent loader), so the codec is a member of the application's
     * set exactly because this jar says so, and of no other loader's.
     */
    private static void writeModuleJar(Path home) throws IOException {
        Path modules = Files.createDirectories(home.resolve("work/modules"));
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(modules.resolve("marker-codec.jar")))) {
            zip.putNextEntry(new ZipEntry("META-INF/services/io.tesseraql.core.files.FileCodec"));
            zip.write((MarkerFileCodec.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
