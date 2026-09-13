package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Three edges of the export formatting chain, each on an app of its own
 * (docs/export-declarations.md decisions 27, 33 and 34): the negotiated locale is served even
 * when the app's i18n default folds to {@code und}; a spool cap hit inside the document write is
 * the document's failure, not the statement's; and an unresolvable placeholder in a
 * {@code tesseraql.files.*} key stops nothing that never reads it.
 */
@Testcontainers
class ExportFormatDefaultsEdgeIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /**
     * {@code tesseraql.i18n.defaultLocale: ja_JP} folds to {@code und} at boot, so
     * {@code request.locale} carries {@code und}. It is the framework's own value, never judged:
     * the export renders in the root locale as it always did, at 200 — red when the judge holds
     * every provenance to the literal rule, which turns every request into a 500.
     */
    @Test
    void theNegotiatedLocaleUnderAnUndDefaultIsServedAsToday() throws Exception {
        Path home = app("und", "ja_JP", "Asia/Kolkata", "");
        writeQueryExport(home, "reqloc", "x.reqloc", "  locale: request.locale\n", "", ROWS_SQL);
        writeQueryExport(home, "plain", "x.plain", "", "", ROWS_SQL);
        try (TesseraqlRuntime runtime = TesseraqlRuntime.start(home, 0)) {
            HttpResponse<String> negotiated = get(runtime, "/api/x/reqloc");
            HttpResponse<String> english = get(runtime, "/api/x/reqloc", "Accept-Language", "en");
            HttpResponse<String> plain = get(runtime, "/api/x/plain");

            assertThat(negotiated.statusCode()).isEqualTo(200);
            assertThat(negotiated.body()).contains("\"1,234.50\"");
            assertThat(english.statusCode()).isEqualTo(200);
            assertThat(plain.statusCode()).isEqualTo(200);
        } finally {
            deleteRecursively(home);
        }
    }

    /**
     * The spool under the codec refuses twenty rows at a hundred bytes: the extraction ran (the
     * sequence advanced), so the failure is the document's — its own code, the spool's reason
     * in the message, and never the SQL file that ran to completion.
     */
    @Test
    void aSpoolFailureDuringTheWriteIsFiledAsADocumentFailure() throws Exception {
        Path home = app("spool", "en", "Asia/Kolkata",
                "  temp:\n    store: db\n    maxBytes: 100\n");
        writeQueryExport(home, "cap", "x.cap", "  timezone: query.tz\n",
                "input:\n  tz: { type: string }\n",
                "select name, held_at, fee, nextval('probe_seq') as n"
                        + " from events, generate_series(1, 20) order by n\n");
        writeQueryExport(home, "one", "x.one", "  timezone: query.tz\n",
                "input:\n  tz: { type: string }\n", ROWS_SQL);
        try (TesseraqlRuntime runtime = TesseraqlRuntime.start(home, 0)) {
            assertThat(get(runtime, "/api/x/one?tz=Asia/Tokyo").statusCode())
                    .as("the control: a document under the cap").isEqualTo(200);
            String before = sequence();
            AtomicReference<HttpResponse<String>> capped = new AtomicReference<>();
            String log = captureStderr(() -> capped.set(get(runtime, "/api/x/cap?tz=Asia/Tokyo")));
            String after = sequence();

            assertThat(capped.get().statusCode()).isEqualTo(500);
            assertThat(MAPPER.readTree(capped.get().body()).at("/error/code").asText())
                    .isEqualTo("TQL-LD-2802");
            assertThat(after).as("the extraction ran").isNotEqualTo(before);
            List<String> lines = log.lines().toList();
            int at = lines.indexOf(lines.stream()
                    .filter(line -> line.contains("Route 'x.cap' failed with TQL-LD-2802"))
                    .findFirst().orElse(null));
            assertThat(at).as("the runner's ERROR line").isNotNegative();
            String head = String.join("\n", lines.subList(at, Math.min(at + 3, lines.size())));
            assertThat(head).contains("Writing the csv document failed after the query ran")
                    .contains("Spool exceeds tesseraql.temp.maxBytes")
                    .doesNotContain("export.sql").doesNotContain("SQL execution failed");
        } finally {
            deleteRecursively(home);
        }
    }

    /**
     * The two keys are read on use, never at construction: an app whose
     * {@code tesseraql.files.timezone} names an environment variable this host lacks, and that
     * has no file recipe, boots, serves and runs its job — red when the executor's wiring reads
     * the key eagerly and the boot fails on a placeholder nothing consults.
     */
    @Test
    void anUnresolvedPlaceholderInAFilesKeyDoesNotStopAnAppThatNeverReadsIt() throws Exception {
        Path home = app("placeholder", "en", "${NOPE_ENV}", "");
        Path rows = Files.createDirectories(home.resolve("web/api/x/rows"));
        Files.writeString(rows.resolve("get.yml"), """
                version: tesseraql/v1
                id: x.rows
                kind: route
                recipe: query-json
                sources:
                  main:
                    sql:
                      file: rows.sql
                response:
                  json:
                    status: 200
                    body:
                      rows: main.rows
                """);
        Files.writeString(rows.resolve("rows.sql"), "select name from events\n");
        Path job = Files.createDirectories(home.resolve("batch/plain"));
        Files.writeString(job.resolve("count.sql"), "select count(*) as c from events\n");
        Files.writeString(job.resolve("job.yml"), """
                version: tesseraql/v1
                id: plain
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: count
                    sql:
                      file: count.sql
                      mode: query
                """);
        try (TesseraqlRuntime runtime = TesseraqlRuntime.start(home, 0)) {
            HttpResponse<String> served = get(runtime, "/api/x/rows");
            JobExecution execution = runtime.runJob("plain", Map.of());

            assertThat(served.statusCode()).isEqualTo(200);
            assertThat(execution.status()).isEqualTo(JobStatus.COMPLETED);
        } finally {
            deleteRecursively(home);
        }
    }

    // --- helpers ---

    private static HttpResponse<String> get(TesseraqlRuntime runtime, String path,
            String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The pair a {@code last_value}-only read is blind to on a fresh sequence. */
    private static String sequence() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select last_value::text || '|' || is_called::text from probe_seq")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    /** slf4j-simple resolves {@code System.err} per line, so swapping the stream captures it. */
    private static String captureStderr(ThrowingRunnable body) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            body.run();
            Thread.sleep(300);
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // --- the fixtures ---

    private static final String ROWS_SQL = "select name, held_at, fee, nextval('probe_seq') as n"
            + " from events order by name\n";

    /**
     * One app: the shared table and the primed sequence, the given i18n default, files zone and
     * extra configuration. Each method boots its own so a boot refusal blames the right edge.
     */
    private static Path app(String tag, String defaultLocale, String filesTimezone, String extra)
            throws Exception {
        Path home = Files.createTempDirectory("export-defaults-edge-" + tag);
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: probe-%s
                  i18n:
                    defaultLocale: %s
                    locales: [%s, ja, de]
                  files:
                    timezone: %s
                    locale: de
                %s  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(tag, defaultLocale, defaultLocale, filesTimezone, extra,
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        Files.createDirectories(home.resolve("db/migration"));
        Files.writeString(home.resolve("db/migration/V1__tables.sql"), """
                create table if not exists events (name varchar(100) primary key,
                                                   held_at timestamptz not null,
                                                   fee numeric(12, 2) not null);
                insert into events (name, held_at, fee)
                values ('alpha', timestamptz '2026-01-15 22:30:00+00', 1234.5)
                on conflict (name) do nothing;
                create sequence if not exists probe_seq start 1;
                select setval('probe_seq', 1, true);
                """);
        return home;
    }

    private static void writeQueryExport(Path home, String dir, String id, String declarations,
            String input, String sql) throws Exception {
        Path route = Files.createDirectories(home.resolve("web/api/x/" + dir));
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: %s
                kind: route
                recipe: query-export
                %ssources:
                  main:
                    sql:
                      file: export.sql
                export:
                  format: csv
                  filename: %s.csv
                %s  columns:
                    - { name: name }
                    - { name: held_at, type: datetime }
                    - { name: fee, type: number, format: "#,##0.00" }
                    - { name: n }
                """.formatted(id, input, id, declarations));
        Files.writeString(route.resolve("export.sql"), sql);
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
