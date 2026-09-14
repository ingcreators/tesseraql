package io.tesseraql.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The compile twin of the export-declaration predicate over the arms
 * {@link ExportDeclarationCompileTest} does not walk (docs/export-declarations.md decision 1):
 * the app-wide configuration key, a missing template on a route, a follow-up with a SQL arm but
 * no file (a refusal, not a null pointer), the import block's column type and request source,
 * an unresolved placeholder on an app with no export route, an absent {@code security:} block,
 * and an advisory that warns without refusing. Each case was first a built variant every other
 * guard was green on.
 */
class ExportDeclarationCompileArmsTest {

    // Red on a compile() config loop that lists only tesseraql.files.timezone: a bad
    // files.locale would boot (the other config guards use the timezone key).
    @Test
    void aMistypedConfigLocaleIsRefusedAtCompileNamingTheKey(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> compile(dir, "query-export", "export:\n  format: csv\n",
                "  files:\n    locale: ja_JP\n"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("app 'export-test'")
                .hasMessageContaining("tesseraql.files.locale")
                .hasMessageContaining("'ja_JP'");
        assertThat(compile(dir, "query-export", "export:\n  format: csv\n",
                "  files:\n    locale: ja-JP\n").get("items.dump"))
                .contains("QueryExportBinder");
    }

    // Red on a route arm that passes no directory: the boot twin of the missing-template
    // lint (decision 10) would be gone.
    @Test
    void aMissingTemplateOnARouteIsRefusedAtCompile(@TempDir Path dir) throws Exception {
        // A workbook reads its template, and the template is judged before the codec (excel
        // is no codec on the csv-only classpath); csv never reads one, and is warned about below.
        assertThatThrownBy(() -> compile(dir, "file-export",
                "export:\n  format: excel\n  template: nowhere.xlsx\n", ""))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1006")
                .hasMessageContaining("route 'items.dump'")
                .hasMessageContaining("nowhere.xlsx");
        assertThat(compile(dir, "file-export",
                "export:\n  format: csv\n  template: nowhere.xlsx\n", ""))
                .containsKey("items.dump");
    }

    // Red on an after: arm that checks only sql == null: after.sql without a file would fail
    // the compile with a null pointer.
    @Test
    void aFollowUpWithASqlArmButNoFileIsRefusedNotANullPointer(@TempDir Path dir)
            throws Exception {
        assertThatThrownBy(() -> compile(dir, "file-export", """
                export:
                  format: csv
                  after:
                    timing: extract
                    sql:
                      mode: update
                """, ""))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1041")
                .hasMessageContaining("route 'items.dump'")
                .hasMessageContaining("after.sql");
    }

    // Red on an import arm that never judges columns() at compile.
    @Test
    void anImportColumnTypeIsRefusedAtCompile(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> compileImport(dir, "", """
                import:
                  format: csv
                  columns:
                    - { name: qty, type: integer }
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("route 'items.import'")
                .hasMessageContaining("import.columns[qty].type")
                .hasMessageContaining("'integer'");
    }

    // buildFileImport wires no RequestBinder, so a query. source on import.locale resolves
    // nothing at run time: the predicate refuses it even with the input declared.
    @Test
    void aQuerySourceOnAnImportRouteIsRefusedAtCompile(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> compileImport(dir, "input:\n  lang:\n    type: string\n",
                "import:\n  format: csv\n  locale: query.lang\n"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("route 'items.import'")
                .hasMessageContaining("import.locale");
    }

    // An unresolved ${...} in files.timezone used to be read only by an export route's
    // formatDeclaration; the compile() loop now reads it for every app, and must skip it, or an
    // app with no export route would fail its boot with TQL-YAML-1101 where it booted before.
    @Test
    void anUnresolvedPlaceholderOnAnAppWithoutAnExportRouteStillCompiles(@TempDir Path dir)
            throws Exception {
        assertThat(compile(dir, "query-json",
                "response:\n  json:\n    body:\n      data: main.rows\n",
                "  files:\n    timezone: ${TQL_ATTACK_UNSET_ZONE}\n")
                .get("items.dump")).isNotNull();
    }

    // Compile side of the site rule: an absent security block is public - principal.* names
    // nothing.
    @Test
    void anAbsentSecurityBlockIsPublicForThePrincipalArmAtCompile(@TempDir Path dir)
            throws Exception {
        assertThatThrownBy(() -> compileNoSecurity(dir,
                "export:\n  format: csv\n  locale: principal.claim.locale\n"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("route 'items.dump'")
                .hasMessageContaining("the route is public");
    }

    // An ADVISORY (an untyped column list under a zone) warns at boot and the route compiles.
    @Test
    void anAdvisoryWarnsAtBootAndTheRouteStillCompiles(@TempDir Path dir) throws Exception {
        Map<String, List<String>> pipelines = compile(dir, "query-export",
                "export:\n  format: csv\n  timezone: Asia/Tokyo\n  columns: [id, name]\n", "");
        assertThat(pipelines.get("items.dump")).contains("QueryExportBinder");
    }

    /**
     * The codec arm (docs/codec-discovery.md decision 2): a format no codec in the
     * application's set serves refuses the compile on every file recipe, naming the app, the
     * route and the key — the asynchronous recipes used to compile and answer 500 at the first
     * request.
     */
    @Test
    void aFormatNoCodecServesIsRefusedAtCompileOnEveryArm(@TempDir Path dir) throws Exception {
        for (String recipe : List.of("query-export", "file-export")) {
            assertThatThrownBy(() -> compile(dir, recipe, "export:\n  format: fixedwidth\n", ""))
                    .as(recipe)
                    .isInstanceOf(TqlException.class)
                    .hasMessageContaining("TQL-LD-2801")
                    .hasMessageContaining("app 'export-test'")
                    .hasMessageContaining("route 'items.dump'")
                    .hasMessageContaining("export.format")
                    .hasMessageContaining("'fixedwidth'")
                    .hasMessageContaining("tesseraql.modules")
                    .hasMessageNotContaining("excel format needs");
        }
        assertThatThrownBy(() -> compileImport(dir.resolve("import"), "",
                "import:\n  format: fixedwidth\n  columns:\n    - name\n"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-LD-2801")
                .hasMessageContaining("route 'items.import'")
                .hasMessageContaining("import.format")
                .hasMessageContaining("'fixedwidth'");
    }

    /** The same declarations compile against a set that carries the format. */
    @Test
    void aFormatInTheApplicationsSetCompilesOnEveryArm(@TempDir Path dir) throws Exception {
        io.tesseraql.core.files.FileCodecs codecs = io.tesseraql.core.files.FileCodecs.of(
                new io.tesseraql.operations.files.CsvFileCodec(), NamedCodec.fixedWidth());
        for (String recipe : List.of("query-export", "file-export")) {
            writeConfig(dir, "");
            Path route = Files.createDirectories(dir.resolve("web/api/items/dump"));
            Files.writeString(route.resolve("get.yml"), """
                    version: tesseraql/v1
                    id: items.dump
                    kind: route
                    recipe: %s
                    security:
                      auth: public
                    export:
                      format: fixedwidth
                    sources:
                      main:
                        sql:
                          file: dump.sql
                    """.formatted(recipe));
            Files.writeString(route.resolve("dump.sql"), "select id from items\n");
            assertThat(compileApp(dir, codecs)).as(recipe).containsKey("items.dump");
        }
    }

    /** An import without {@code format:} is csv, as an export's is (docs/file-transfers.md). */
    @Test
    void anImportWithoutAFormatIsCsvAtCompile(@TempDir Path dir) throws Exception {
        assertThat(compileImport(dir, "", "import:\n  columns:\n    - name\n"))
                .containsKey("items.import");
    }

    private static Map<String, List<String>> compileNoSecurity(Path dir, String body)
            throws Exception {
        writeConfig(dir, "");
        Path route = Files.createDirectories(dir.resolve("web/api/items/dump"));
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.dump
                kind: route
                recipe: query-export

                %s
                sources:
                  main:
                    sql:
                      file: dump.sql
                """.formatted(body));
        Files.writeString(route.resolve("dump.sql"), "select id from items\n");
        return compileApp(dir);
    }

    // ---- harness (the design's own shape) ----

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

    private static Map<String, List<String>> compileImport(Path dir, String headerKeys,
            String body) throws Exception {
        writeConfig(dir, "");
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
                %s
                steps:
                  - id: row
                    sql:
                      file: upsert.sql
                """.formatted(headerKeys, body));
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
        return compileApp(dir, io.tesseraql.core.files.FileCodecs
                .discover(ExportDeclarationCompileArmsTest.class.getClassLoader()));
    }

    private static Map<String, List<String>> compileApp(Path dir,
            io.tesseraql.core.files.FileCodecs codecs) throws Exception {
        AppManifest manifest = new ManifestLoader().load(dir);
        try (RuntimeContext context = new RuntimeContext()) {
            new RouteCompiler().appName("export-test").codecs(codecs)
                    .compile(context, manifest, false, null);
            return CompiledPipelines.stepsById(context);
        }
    }
}
