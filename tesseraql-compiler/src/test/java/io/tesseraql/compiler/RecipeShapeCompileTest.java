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
 * The boot twin of the recipe-shape lint (docs/audit-low-leads.md slice 8): a piece the recipe
 * reads and the document does not declare is refused where the route compiles, with the code
 * and the sentence the linter reports, naming the route — before the builder that used to
 * dereference it. Every fixture here was measured as a {@code NullPointerException} out of the
 * compiler at boot, on a document that linted clean: the JSON renderer on a route with no
 * {@code response:} (the shape {@code docs/multi-datasource.md} showed as complete), the
 * template page on a redirect-only {@code page}, {@code Path.resolve(null)} on a step whose
 * {@code sql:} named no file, the import builder on a route with no {@code import:} block, no
 * step, or a step without a file, the export builders on a document with no {@code main}. A
 * missing SQL file is the one piece whose failure was not a crash: the source read lazily,
 * so the route booted green and answered every request with a raw exception.
 *
 * <p>Every refusal asserts the class, the code AND the route id: a message assertion alone is
 * green on a JDK exception that happens to mention the key.
 */
class RecipeShapeCompileTest {

    private static org.assertj.core.api.AbstractThrowableAssert<?, ?> refusal(Path dir,
            String recipe, String body) {
        return assertThatThrownBy(() -> compile(dir, recipe, body))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("route 'items.route'");
    }

    // ---- the response arm ----

    @Test
    void aQueryJsonRouteWithoutAResponseIsRefusedNotANullPointer(@TempDir Path dir)
            throws Exception {
        refusal(dir, "query-json", """
                sources:
                  main:
                    sql:
                      file: list.sql
                """)
                .hasMessageContaining("TQL-YAML-1066")
                .hasMessageContaining("response.json: or response.redirect:")
                .hasMessageContaining("there is no response: block");
    }

    @Test
    void aCommandWithOnlyASessionBlockIsRefusedNotANullPointer(@TempDir Path dir)
            throws Exception {
        // The transactional command's renderer, on the rotate-only declaration.
        refusal(dir, "command-json", """
                response:
                  session:
                    rotate: true
                steps:
                  - id: write
                    sql:
                      file: write.sql
                      mode: update
                """)
                .hasMessageContaining("TQL-YAML-1066")
                .hasMessageContaining("the response: block declares session:");
    }

    @Test
    void aPageWithOnlyARedirectIsRefusedNotANullPointer(@TempDir Path dir) throws Exception {
        refusal(dir, "page", """
                response:
                  redirect:
                    location: /items
                """)
                .hasMessageContaining("TQL-YAML-1066")
                .hasMessageContaining("a page route renders response.html:")
                .hasMessageContaining("declares redirect:");
        refusal(dir.resolve("bare"), "query-html", """
                sources:
                  main:
                    sql:
                      file: list.sql
                """)
                .hasMessageContaining("TQL-YAML-1066");
    }

    @Test
    void theDeclaredArmsCompile(@TempDir Path dir) throws Exception {
        assertThat(compile(dir.resolve("json"), "query-json", """
                response:
                  json:
                    body:
                      rows: main.rows
                sources:
                  main:
                    sql:
                      file: list.sql
                """).get("items.route")).contains("JsonResponseRenderer");
        assertThat(compile(dir.resolve("redirect"), "command-json", """
                response:
                  redirect:
                    location: /items
                steps:
                  - id: write
                    sql:
                      file: write.sql
                      mode: update
                """).get("items.route")).contains("RedirectRenderer");
    }

    // ---- the binding arm ----

    @Test
    void aSourceThatNamesNoArmIsRefusedNotANullPointer(@TempDir Path dir) throws Exception {
        refusal(dir, "query-json", """
                response:
                  json:
                    body:
                      rows: main.rows
                sources:
                  main:
                    sql:
                      mode: query
                """)
                .hasMessageContaining("TQL-YAML-1067")
                .hasMessageContaining("sources.main:")
                .hasMessageContaining("declares no arm to run");
        refusal(dir.resolve("http"), "query-json", """
                response:
                  json:
                    body:
                      rows: rates.rows
                sources:
                  rates:
                    http:
                      credential: fx
                """)
                .hasMessageContaining("TQL-YAML-1067")
                .hasMessageContaining("sources.rates:")
                .hasMessageContaining("names no url:");
    }

    @Test
    void aCommandStepThatNamesNoArmIsRefusedNotANullPointer(@TempDir Path dir)
            throws Exception {
        // A command with one file-less step is not transactional, so it took the read path
        // — pipelineThroughSql — and Path.resolve(null)'s message-less NullPointerException.
        refusal(dir, "command-json", """
                response:
                  json:
                    body:
                      ok: true
                steps:
                  - id: touch
                    sql:
                      mode: update
                """)
                .hasMessageContaining("TQL-YAML-1067")
                .hasMessageContaining("steps.touch:")
                .hasMessageContaining("http: or sequence:");
    }

    @Test
    void anMcpToolIsHeldToTheSameArmRule(@TempDir Path dir) throws Exception {
        writeConfig(dir);
        Files.createDirectories(dir.resolve("mcp"));
        Files.writeString(dir.resolve("mcp/lookup.yml"), """
                version: tesseraql/v1
                id: lookup
                kind: tool
                recipe: query-json
                description: looks things up
                sources:
                  main:
                    sql:
                      mode: query
                """);
        assertThatThrownBy(() -> compileApp(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1067")
                .hasMessageContaining("MCP tool 'lookup' sources.main:");
    }

    // ---- file-import's pieces ----

    @Test
    void aFileImportWithoutAnImportBlockIsRefusedNotANullPointer(@TempDir Path dir)
            throws Exception {
        assertThatThrownBy(() -> compileImport(dir, """
                steps:
                  - id: row
                    sql:
                      file: upsert.sql
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1041")
                .hasMessageContaining("route 'items.import' import:")
                .hasMessageContaining("needs an import: block");
    }

    @Test
    void aFileImportWithoutARowStepIsRefusedNotANullPointer(@TempDir Path dir)
            throws Exception {
        assertThatThrownBy(() -> compileImport(dir, """
                import:
                  format: csv
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1041")
                .hasMessageContaining("route 'items.import' steps:")
                .hasMessageContaining("what to write per row");
    }

    @Test
    void aFileImportWhoseRowStepNamesNoFileIsRefusedNotANullPointer(@TempDir Path dir)
            throws Exception {
        // Path.resolve(null): the one NPE with no message at all, so the trace was the only
        // place the site could be read from.
        assertThatThrownBy(() -> compileImport(dir, """
                import:
                  format: csv
                steps:
                  - id: row
                    sql:
                      mode: update
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1041")
                .hasMessageContaining("route 'items.import' steps:");
        assertThat(compileImport(dir.resolve("whole"), """
                import:
                  format: csv
                steps:
                  - id: row
                    sql:
                      file: upsert.sql
                """).get("items.import")).contains("FileImportProcessor");
    }

    // ---- an export's rows ----

    @Test
    void anExportWithoutAMainFileIsRefusedNotANullPointer(@TempDir Path dir) throws Exception {
        refusal(dir, "query-export", """
                export:
                  format: csv
                """)
                .hasMessageContaining("TQL-YAML-1041")
                .hasMessageContaining("sources.main:")
                .hasMessageContaining("no sources: main: sql: { file: ... }");
        refusal(dir.resolve("file"), "file-export", """
                export:
                  format: csv
                """)
                .hasMessageContaining("TQL-YAML-1041")
                .hasMessageContaining("sources.main:");
    }

    // ---- the statement that is not there ----

    @Test
    void aMissingSqlFileIsRefusedAtCompileNotAtTheFirstRequest(@TempDir Path dir)
            throws Exception {
        // The linter's own sentence, from the one resolver both altitudes read a statement
        // through (docs/audit-low-leads.md slice 14). The read path resolves at the source,
        // the transactional command before its processor reads.
        assertThatThrownBy(() -> compile(dir, "query-json", """
                response:
                  json:
                    body:
                      rows: main.rows
                sources:
                  main:
                    sql:
                      file: nowhere.sql
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2103")
                .hasMessageContaining("route 'items.route' sources.main.file:")
                .hasMessageContaining("referenced SQL file is missing: nowhere.sql");
        assertThatThrownBy(() -> compile(dir.resolve("step"), "command-json", """
                response:
                  json:
                    body:
                      ok: true
                steps:
                  - id: touch
                    sql:
                      file: nowhere.sql
                      mode: update
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2103")
                .hasMessageContaining("route 'items.route' steps.touch.file:")
                .hasMessageContaining("nowhere.sql");
    }

    @Test
    void aDialectVariantSatisfiesTheStatementCheck(@TempDir Path dir) throws Exception {
        // The resolve site looks for <name>.<dialect>.sql first; a fixture that ships only
        // the variant is a legal layout and must not be refused for its base file.
        writeConfig(dir);
        Path route = Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.route
                kind: route
                recipe: query-json
                security:
                  auth: public
                response:
                  json:
                    body:
                      rows: main.rows
                sources:
                  main:
                    sql:
                      file: list.sql
                """);
        Files.writeString(route.resolve("list.postgres.sql"), "select id from items\n");
        assertThat(compileApp(dir).get("items.route")).contains("SqlStep");
    }

    // ---- harness ----

    private static Map<String, List<String>> compile(Path dir, String recipe, String body)
            throws Exception {
        writeConfig(dir);
        Path route = Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.route
                kind: route
                recipe: %s
                security:
                  auth: public
                %s
                """.formatted(recipe, body));
        Files.writeString(route.resolve("list.sql"), "select id from items\n");
        Files.writeString(route.resolve("write.sql"), "insert into items (id) values (1)\n");
        return compileApp(dir);
    }

    private static Map<String, List<String>> compileImport(Path dir, String body)
            throws Exception {
        writeConfig(dir);
        Path route = Files.createDirectories(dir.resolve("web/items/import"));
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.import
                kind: route
                recipe: file-import
                security:
                  auth: bearer
                  policy: items.write
                %s
                """.formatted(body));
        Files.writeString(route.resolve("upsert.sql"),
                "insert into items (name) values (/* name */ 'x')\n");
        return compileApp(dir);
    }

    private static void writeConfig(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: shape-test
                  datasources:
                    main:
                      jdbcUrl: jdbc:postgresql://localhost/none
                """);
    }

    private static Map<String, List<String>> compileApp(Path dir) throws Exception {
        AppManifest manifest = new ManifestLoader().load(dir);
        try (RuntimeContext context = new RuntimeContext()) {
            new RouteCompiler().appName("shape-test")
                    .codecs(io.tesseraql.core.files.FileCodecs.of(
                            new io.tesseraql.operations.files.CsvFileCodec()))
                    .compile(context, manifest, false, null);
            return CompiledPipelines.stepsById(context);
        }
    }
}
