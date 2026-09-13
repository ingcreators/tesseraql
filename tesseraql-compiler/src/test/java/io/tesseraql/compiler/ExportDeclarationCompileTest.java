package io.tesseraql.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The boot twin of the export-declaration lint (docs/export-declarations.md decision 1): a
 * literal the runtime cannot honour is refused where the route compiles, with the same code
 * and message the linter reports, before any codec is looked up — so this no-database harness
 * hosts every arm on {@code format: csv} fixtures (the compiler's test classpath carries the
 * csv codec only, and a {@code pdf}/{@code excel} fixture would be refused as
 * {@code TQL-LD-2801} for the wrong reason).
 *
 * <p>Every refusal asserts the class, the code AND the route id: today's raw
 * {@code IllegalArgumentException} on {@code startCell: 5B} keeps the message, so a message
 * assertion alone is green on the defect.
 */
class ExportDeclarationCompileTest {

    /** slf4j-simple writes to {@code System.err}; the boot warnings land here. */
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

    private static org.assertj.core.api.AbstractThrowableAssert<?, ?> refusal(Path dir,
            String recipe, String export) {
        return assertThatThrownBy(() -> compile(dir, recipe, export, ""))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("route 'items.dump'");
    }

    // ---- query-export ----

    @Test
    void aMistypedZoneOnAQueryExportIsRefusedAtCompile(@TempDir Path dir) throws Exception {
        refusal(dir, "query-export", "export:\n  format: csv\n  timezone: Asia/Tokio\n")
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("export.timezone")
                .hasMessageContaining("'Asia/Tokio'");
        assertThat(
                compile(dir, "query-export", "export:\n  format: csv\n  timezone: Asia/Tokyo\n", "")
                        .get("items.dump"))
                .contains("QueryExportBinder");
    }

    @Test
    void aMistypedLocaleOnAQueryExportIsRefusedAtCompile(@TempDir Path dir) throws Exception {
        refusal(dir, "query-export", "export:\n  format: csv\n  locale: ja_JP\n")
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("not a language tag the JDK can format");
    }

    @Test
    void aColumnPatternOnAQueryExportIsRefusedAtCompile(@TempDir Path dir) throws Exception {
        refusal(dir, "query-export", """
                export:
                  format: csv
                  columns:
                    - { name: amount, type: number, format: '#,##0.00.00' }
                """)
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("'#,##0.00.00'");
    }

    @Test
    void aSourceNamingNoDeclaredInputIsRefusedAtCompile(@TempDir Path dir) throws Exception {
        refusal(dir, "query-export", "export:\n  format: csv\n  timezone: query.tz\n")
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("names no declared input");
        assertThat(compile(dir, "query-export",
                "input:\n  tz:\n    type: string\nexport:\n  format: csv\n  timezone: query.tz\n",
                "")
                .get("items.dump")).contains("QueryExportBinder");
    }

    @Test
    void theValueIsJudgedBeforeTheCodecIsLookedUp(@TempDir Path dir) throws Exception {
        // The one pdf fixture, on purpose: this classpath carries no pdf codec, so a check
        // placed after require(format) answers the no-codec code and never reaches the zone
        // (MEASUREMENT.md hazard 14). The refusal must be the declaration's, not the codec's.
        refusal(dir, "query-export", "export:\n  format: pdf\n  timezone: Asia/Tokio\n")
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageNotContaining("TQL-LD-2801");
    }

    // ---- file-export ----

    @Test
    void aMistypedZoneOnAFileExportIsRefusedAtCompile(@TempDir Path dir) throws Exception {
        refusal(dir, "file-export", "export:\n  format: csv\n  timezone: Asia/Tokio\n")
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("'Asia/Tokio'");
        assertThat(
                compile(dir, "file-export", "export:\n  format: csv\n  timezone: Asia/Tokyo\n", "")
                        .get("items.dump"))
                .contains("FileExportStartProcessor");
    }

    @Test
    void aCellReferenceIsShapedIntoTheCodeNotARawException(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("web/api/items/dump"));
        Files.write(dir.resolve("web/api/items/dump/t.xlsx"), new byte[]{1});
        // Today: a raw IllegalArgumentException("Not a cell reference: 5B") out of
        // ExportSpec.toWriteSpec, which the runtime wraps as IllegalStateException.
        refusal(dir, "file-export", "export:\n  format: csv\n  template: t.xlsx\n  startCell: 5B\n")
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("'5B'");
        // Today: compiles, and POI refuses column 475253 at the first row. file-export looks
        // no codec up, so the workbook fixture compiles on the csv-only classpath.
        refusal(dir, "file-export",
                "export:\n  format: excel\n  template: t.xlsx\n  startCell: ZZZZ1\n")
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("outside a workbook");
        // csv reads no cell: the same reference is a warning there, and the route compiles.
        assertThat(compile(dir, "file-export",
                "export:\n  format: csv\n  startCell: ZZZZ1\n", ""))
                .containsKey("items.dump");
        assertThat(LOG.toString()).contains("WARN").contains("route 'items.dump'")
                .contains("export.sheet/startCell");
    }

    @Test
    void aFileExportWithoutAnExportBlockIsRefusedNotANullPointer(@TempDir Path dir)
            throws Exception {
        refusal(dir, "file-export", "")
                .hasMessageContaining("TQL-YAML-1041")
                .hasMessageContaining("export:");
    }

    @Test
    void aFollowUpWithoutItsStatementIsRefusedNotANullPointer(@TempDir Path dir)
            throws Exception {
        refusal(dir, "file-export", "export:\n  format: csv\n  after:\n    timing: extract\n")
                .hasMessageContaining("TQL-YAML-1041")
                .hasMessageContaining("after.sql");
    }

    @Test
    void aFileExportWithoutAFormatIsTheCsvDefault(@TempDir Path dir) throws Exception {
        // Today the processor is built with the literal format "null" and answers TQL-LD-2801
        // at the first POST; a route's unset format: is csv, as query-export already says.
        writeConfig(dir, "");
        Path route = Files.createDirectories(dir.resolve("web/api/items/dump"));
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.dump
                kind: route
                recipe: file-export
                security:
                  auth: public
                export:
                  filename: items.csv
                sources:
                  main:
                    sql:
                      file: dump.sql
                """);
        Files.writeString(route.resolve("dump.sql"), "select id from items\n");
        AppManifest manifest = new ManifestLoader().load(dir);
        try (RuntimeContext context = new RuntimeContext()) {
            new RouteCompiler().appName("export-test").compile(context, manifest, false, null);
            var processor = CompiledPipelines.steps(context, "items.dump",
                    io.tesseraql.compiler.binding.FileExportStartProcessor.class).get(0);
            // The processor keeps its format private; the test reads the field it was built
            // with rather than adding an accessor for one assertion.
            java.lang.reflect.Field field = processor.getClass().getDeclaredField("format");
            field.setAccessible(true);
            assertThat(field.get(processor)).isEqualTo("csv");
        }
    }

    @Test
    void anInertKeyWarnsAtBootAndTheRouteStillCompiles(@TempDir Path dir) throws Exception {
        // Decision 2: the linter refuses bom: on a workbook; the runtime serves without it, so
        // boot says so on one line naming the route and the key, and continues. file-export
        // looks no codec up, so the excel fixture compiles on the csv-only classpath.
        Map<String, List<String>> pipelines = compile(dir, "file-export",
                "export:\n  format: excel\n  bom: true\n", "");

        assertThat(pipelines.get("items.dump")).contains("FileExportStartProcessor");
        String log = LOG.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(log.lines().filter(line -> line.contains("WARN")).toList())
                .anySatisfy(line -> assertThat(line).contains("route 'items.dump'", "export.bom",
                        "bom: is a csv option"));

        LOG.reset();
        compile(dir, "file-export", "export:\n  format: excel\n", "");
        assertThat(LOG.toString(java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("export.bom");
    }

    // ---- import ----

    @Test
    void aMistypedImportLocaleIsRefusedAtCompile(@TempDir Path dir) throws Exception {
        assertThatThrownBy(
                () -> compileImport(dir, "import:\n  format: csv\n  locale: de_DE\n", ""))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("route 'items.import'")
                .hasMessageContaining("import.locale")
                .hasMessageContaining("'de_DE'");
        assertThat(compileImport(dir, "import:\n  format: csv\n  locale: de-DE\n", "")
                .get("items.import")).contains("FileImportProcessor");
    }

    // ---- the app-wide keys (a LIVE bad key: MEASUREMENT.md hazard 15) ----

    @Test
    void aMistypedConfigZoneIsRefusedAtCompileNamingTheKey(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> compile(dir, "query-export", "export:\n  format: csv\n",
                "  files:\n    timezone: Asia/Tokio\n"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("app 'export-test'")
                .hasMessageContaining("tesseraql.files.timezone")
                .hasMessageContaining("'Asia/Tokio'");
        assertThat(compile(dir, "query-export", "export:\n  format: csv\n",
                "  files:\n    timezone: Asia/Tokyo\n").get("items.dump"))
                .contains("QueryExportBinder");
    }

    @Test
    void aConfigKeyThatIsASourceExpressionIsRefusedAtCompile(@TempDir Path dir)
            throws Exception {
        assertThatThrownBy(() -> compile(dir, "query-export", "export:\n  format: csv\n",
                "  files:\n    timezone: query.tz\n"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("tesseraql.files.timezone")
                .hasMessageContaining("no request");
    }

    // ---- the synthesis' arms (the attacks' RUN findings) ----

    @Test
    void aBodySourceIsRefusedOnlyWhereTheBodyIsLimitedToDeclaredInputs(@TempDir Path dir)
            throws Exception {
        // HEAD resolves an undeclared body.tz under unknownFields: ignore (the raw body is
        // published under body.); under the default reject policy it never resolves.
        assertThat(compileWithHeader(dir, "file-export",
                "inputPolicy:\n  unknownFields: ignore",
                "export:\n  format: csv\n  timezone: body.tz\n"))
                .containsKey("items.dump");
        refusal(dir.resolve("strict"), "file-export",
                "export:\n  format: csv\n  timezone: body.tz\n")
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("'body.tz'")
                .hasMessageContaining("names no declared input");
    }

    @Test
    void aFollowUpOnAQueryExportKeepsTheCompilersOwnCode(@TempDir Path dir) throws Exception {
        // after: on a query-export is TQL-ROUTE-3101 ("use the file-export recipe"), never a
        // 1041 "add the statement" that leads the author straight into 3101.
        assertThatThrownBy(() -> compile(dir, "query-export",
                "export:\n  format: csv\n  after:\n    timing: extract\n", ""))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-ROUTE-3101")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("TQL-YAML-1041"));
    }

    @Test
    void anExcelCellFormatCompilesAndTheCsvTwinIsRefused(@TempDir Path dir) throws Exception {
        // A workbook writes format: verbatim as the cell's own number format; file-export
        // looks no codec up, so the excel fixture compiles on the csv-only classpath.
        String columns = "  columns:\n    - { name: created, type: date, format: d-mmm-yy }\n";
        assertThat(compile(dir, "file-export", "export:\n  format: excel\n" + columns, ""))
                .containsKey("items.dump");
        refusal(dir.resolve("csv"), "file-export", "export:\n  format: csv\n" + columns)
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("'d-mmm-yy'");
    }

    @Test
    void anUnknownColumnTypeOnAnExportWarnsAndTheRouteCompiles(@TempDir Path dir)
            throws Exception {
        // Rendered as an untyped column today: a boot warning, not a refusal.
        assertThat(compile(dir, "file-export",
                "export:\n  format: csv\n  columns:\n    - { name: when, type: timestamp }\n", ""))
                .containsKey("items.dump");
        assertThat(LOG.toString()).contains("WARN").contains("route 'items.dump'")
                .contains("export.columns[when].type");
    }

    @Test
    void aCldrAliasedLocaleCompilesAndAWordDoesNot(@TempDir Path dir) throws Exception {
        assertThat(compile(dir, "query-export", "export:\n  format: csv\n  locale: tl\n", ""))
                .containsKey("items.dump");
        refusal(dir.resolve("word"), "query-export",
                "export:\n  format: csv\n  locale: japanese\n")
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("'japanese'");
    }

    @Test
    void aTemplateOnCsvWarnsAndCompilesWhileAWorkbooksMissingOneIsRefused(@TempDir Path dir)
            throws Exception {
        assertThat(compile(dir, "file-export",
                "export:\n  format: csv\n  template: nowhere.xlsx\n", ""))
                .containsKey("items.dump");
        assertThat(LOG.toString()).contains("WARN").contains("export.template")
                .contains("reads no template");
        refusal(dir.resolve("xlsx"), "file-export",
                "export:\n  format: excel\n  template: nowhere.xlsx\n")
                .hasMessageContaining("TQL-YAML-1006")
                .hasMessageContaining("nowhere.xlsx");
        // The present twin, beside the route (never against the app home), compiles.
        Path route = Files.createDirectories(dir.resolve("present/web/api/items/dump"));
        Files.write(route.resolve("styled.xlsx"), new byte[]{1});
        assertThat(compile(dir.resolve("present"), "file-export",
                "export:\n  format: excel\n  template: styled.xlsx\n  startCell: B2\n", ""))
                .containsKey("items.dump");
    }

    @Test
    void aConfigKeyIsJudgedOncePerManifestNotPerReloadedRoute(@TempDir Path dir)
            throws Exception {
        // A hot reload compiles one route at a time; a bad app-wide key must not turn every
        // route of the app into a 500 stub — the reloader judges the key once, before its loop.
        writeConfig(dir, "  files:\n    timezone: Asia/Tokio\n");
        Path route = Files.createDirectories(dir.resolve("web/api/items/list"));
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
        Files.writeString(route.resolve("list.sql"), "select id from items\n");
        AppManifest manifest = new ManifestLoader().load(dir);
        try (RuntimeContext context = new RuntimeContext()) {
            new RouteCompiler().appName("export-test")
                    .compile(context, manifest, false, java.util.Set.of("items.list"));
            assertThat(CompiledPipelines.stepsById(context)).containsKey("items.list");
            assertThatThrownBy(() -> new RouteCompiler().appName("export-test")
                    .compile(new RuntimeContext(), manifest, false, null))
                    .isInstanceOf(TqlException.class)
                    .hasMessageContaining("TQL-YAML-1063")
                    .hasMessageContaining("tesseraql.files.timezone");
        }
    }

    @Test
    void aBootRefusalNamesTheValueBounded(@TempDir Path dir) throws Exception {
        String huge = "A".repeat(2000) + "\nFORGED LINE\n" + "B".repeat(3000);
        Files.createDirectories(dir.resolve("web/api/items/dump"));
        assertThatThrownBy(() -> compile(dir, "file-export",
                "export:\n  format: excel\n  template: \"" + huge.replace("\n", "\\n")
                        + ".xlsx\"\n",
                ""))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1006")
                .satisfies(ex -> assertThat(ex.getMessage()).hasSizeLessThan(400)
                        .doesNotContain("\n"));
    }

    private static Map<String, List<String>> compileWithHeader(Path dir, String recipe,
            String headerKeys, String body) throws Exception {
        writeConfig(dir, "");
        Path route = Files.createDirectories(dir.resolve("web/api/items/dump"));
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.dump
                kind: route
                recipe: %s
                method: POST
                security:
                  auth: public
                %s

                %s
                sources:
                  main:
                    sql:
                      file: dump.sql
                """.formatted(recipe, headerKeys, body));
        Files.writeString(route.resolve("dump.sql"), "select id from items\n");
        return compileApp(dir);
    }

    // ---- harness ----

    private static Map<String, List<String>> compile(Path dir, String recipe, String body,
            String configTail) throws Exception {
        writeConfig(dir, configTail);
        Path route = Files.createDirectories(dir.resolve("web/api/items/dump"));
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.dump
                kind: route
                recipe: %s
                security:
                  auth: public

                %s
                sources:
                  main:
                    sql:
                      file: dump.sql
                """.formatted(recipe, body));
        Files.writeString(route.resolve("dump.sql"), "select id from items\n");
        return compileApp(dir);
    }

    private static Map<String, List<String>> compileImport(Path dir, String body,
            String configTail) throws Exception {
        writeConfig(dir, configTail);
        Path route = Files.createDirectories(dir.resolve("web/api/items/import"));
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.import
                kind: route
                recipe: file-import
                security:
                  auth: bearer
                  policy: items.write

                %s
                steps:
                  - id: row
                    sql:
                      file: upsert.sql
                """.formatted(body));
        Files.writeString(route.resolve("upsert.sql"),
                "insert into items (name) values (/* name */ 'x')\n");
        return compileApp(dir);
    }

    private static void writeConfig(Path dir, String configTail) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: export-test
                """ + configTail);
    }

    private static Map<String, List<String>> compileApp(Path dir) throws Exception {
        AppManifest manifest = new ManifestLoader().load(dir);
        try (RuntimeContext context = new RuntimeContext()) {
            new RouteCompiler().appName("export-test")
                    .compile(context, manifest, false, null);
            return CompiledPipelines.stepsById(context);
        }
    }
}
