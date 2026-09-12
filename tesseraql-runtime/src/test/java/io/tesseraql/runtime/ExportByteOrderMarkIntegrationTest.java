package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * An {@code export:} CSV opens with a byte-order mark when the route declares {@code bom: true},
 * and only then. The declaration travels {@code ExportSpec.toWriteSpec} ->
 * {@code FileWriteSpec.withFormatting} -> the codec, and the middle hop rebuilds the record field
 * by field on every route export: a hop that drops the flag is green on every codec unit test and
 * red only here, on the wire. The three export arms (route, async file-export, job step) share
 * both hops. The table is wider than the writer's encoder buffer so a mark that lands late lands
 * inside the body.
 */
@Testcontainers
class ExportByteOrderMarkIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final byte[] MARK = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] HEADER = "Name\r\n".getBytes(StandardCharsets.UTF_8);

    static TesseraqlRuntime runtime;
    static Path appHome;
    static int port;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        // Port 0: the runtime binds an ephemeral port and reports it.
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
    void aDeclaredMarkOpensTheDownloadAndChangesNothingElse() throws Exception {
        HttpResponse<byte[]> marked = get("/api/items/marked");
        HttpResponse<byte[]> plain = get("/api/items/plain");

        assertThat(marked.statusCode()).isEqualTo(200);
        assertThat(plain.body().length).as("the body outruns the encoder buffer")
                .isGreaterThan(16384);
        assertThat(marked.body()).startsWith(MARK);
        // The mark is the whole difference: after it, the marked download is the plain one byte
        // for byte - one mark, at byte 0, and nothing else moved.
        assertThat(Arrays.copyOfRange(marked.body(), 3, marked.body().length))
                .isEqualTo(plain.body());
        assertThat(marked.headers().firstValue("Content-Type"))
                .contains("text/csv; charset=utf-8");
        assertThat(marked.headers().firstValue("Content-Disposition").orElse(""))
                .contains("items.csv");
    }

    @Test
    void anUndeclaredExportStaysUnmarked() throws Exception {
        HttpResponse<byte[]> plain = get("/api/items/plain");

        assertThat(plain.statusCode()).isEqualTo(200);
        // Byte 0 is the first header cell ("Name"), not a mark; one column, so the row ends there.
        assertThat(plain.body()).startsWith(HEADER);
    }

    @Test
    void aDeclinedMarkStaysOff() throws Exception {
        HttpResponse<byte[]> declined = get("/api/items/declined");

        assertThat(declined.statusCode()).isEqualTo(200);
        assertThat(declined.body()).startsWith(HEADER);
    }

    @Test
    void aLocaleDoesNotImplyAMark() throws Exception {
        HttpResponse<byte[]> japanese = get("/api/items/ja");

        assertThat(japanese.statusCode()).isEqualTo(200);
        assertThat(japanese.body()).startsWith(HEADER);
    }

    /** Decision D1: an empty export with {@code bom: true} is exactly three bytes on the wire. */
    @Test
    void anEmptyMarkedExportIsExactlyTheMark() throws Exception {
        HttpResponse<byte[]> empty = get("/api/items/empty");

        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(empty.body()).containsExactly(MARK);
    }

    private static HttpResponse<byte[]> get(String path) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static Path prepareAppHome() throws Exception {
        Path home = Files.createTempDirectory("export-bom-app");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/tesseraql.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: bom-demo
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
                create table items (name varchar(100) primary key);
                insert into items (name) select '受注' || i from generate_series(1, 2000) as g(i);
                """);
        export(home, "marked", "api.items.marked", "select name from items order by name",
                "  bom: true\n");
        export(home, "plain", "api.items.plain", "select name from items order by name", "");
        export(home, "declined", "api.items.declined", "select name from items order by name",
                "  bom: false\n");
        export(home, "ja", "api.items.ja", "select name from items order by name",
                "  locale: ja\n  timezone: Asia/Tokyo\n");
        export(home, "empty", "api.items.empty", "select name from items where name = 'none'",
                "  bom: true\n");
        return home;
    }

    private static void export(Path home, String dir, String id, String sql, String extra)
            throws Exception {
        Path route = home.resolve("web/api/items/" + dir);
        Files.createDirectories(route);
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: query-export
                sources:
                  main:
                    sql:
                      file: items.sql
                export:
                  format: csv
                  filename: items.csv
                  columns:
                    - { name: name, label: Name }
                %s""".formatted(id, extra));
        Files.writeString(route.resolve("items.sql"), sql + "\n");
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
