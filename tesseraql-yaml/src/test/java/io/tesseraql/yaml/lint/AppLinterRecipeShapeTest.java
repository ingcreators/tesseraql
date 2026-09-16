package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pieces a recipe reads, judged at lint from the predicate the compiler refuses from
 * (docs/audit-low-leads.md slice 8). Every fixture here used to lint clean — zero findings —
 * and take the boot down with a NullPointerException: the response arm, the binding arm, the
 * import block and row write, the export's main. Each case asserts the code AND the route in
 * the message, because the compile twin ({@code RecipeShapeCompileTest}) asserts the same
 * sentence and a message-only assertion would be green on a refusal that names no route.
 */
class AppLinterRecipeShapeTest {

    private static Path app(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        return dir;
    }

    private static Path route(Path dir, String recipe, String body) throws Exception {
        app(dir);
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/list.sql"), "select id from items\n");
        Files.writeString(dir.resolve("web/items/write.sql"),
                "insert into items (id) values (1)\n");
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.route
                kind: route
                recipe: %s
                security:
                  auth: public
                %s
                """.formatted(recipe, body));
        return dir;
    }

    private static List<LintFinding> of(List<LintFinding> findings, String code) {
        return findings.stream().filter(finding -> finding.code().equals(code)).toList();
    }

    // ---- the response arm ----

    @Test
    void aQueryJsonRouteWithoutAResponseIsRefusedNamingTheRoute(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(dir, "query-json", """
                sources:
                  main:
                    sql:
                      file: list.sql
                """));
        assertThat(of(findings, "TQL-YAML-1066")).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.source()).isEqualTo("web/items/get.yml");
            assertThat(finding.message()).contains("route 'items.route'")
                    .contains("response.json: or response.redirect:")
                    .contains("there is no response: block");
        });
    }

    @Test
    void aResponseWithoutTheArmTheRecipeRendersIsTheSameRefusal(@TempDir Path dir)
            throws Exception {
        // A session block alone (the rotate declaration) is what the JSON renderer NPE'd on.
        List<LintFinding> findings = new AppLinter().lint(route(dir, "command-json", """
                response:
                  session:
                    rotate: true
                steps:
                  - id: write
                    sql:
                      file: write.sql
                      mode: update
                """));
        assertThat(of(findings, "TQL-YAML-1066")).singleElement().satisfies(finding -> assertThat(
                finding.message()).contains("route 'items.route'")
                .contains("a command-json route")
                .contains("the response: block declares session:"));
        // The page arm on a JSON recipe: written, and never read by this recipe.
        List<LintFinding> html = new AppLinter().lint(route(dir.resolve("html"), "query-json", """
                response:
                  html:
                    template: list.html
                sources:
                  main:
                    sql:
                      file: list.sql
                """));
        assertThat(of(html, "TQL-YAML-1066")).singleElement().satisfies(finding -> assertThat(
                finding.message()).contains("the response: block declares html:"));
    }

    @Test
    void aPageWithoutItsHtmlOrFileArmIsRefusedAndARedirectIsNotOne(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(dir, "page", """
                response:
                  redirect:
                    location: /items
                """));
        assertThat(of(findings, "TQL-YAML-1066")).singleElement().satisfies(finding -> assertThat(
                finding.message()).contains("route 'items.route'")
                .contains("a page route renders response.html:")
                .contains("the response: block declares redirect:"));
        List<LintFinding> bare = new AppLinter().lint(route(dir.resolve("bare"), "query-html", """
                sources:
                  main:
                    sql:
                      file: list.sql
                """));
        assertThat(of(bare, "TQL-YAML-1066")).singleElement().satisfies(finding -> assertThat(
                finding.message()).contains("a query-html route"));
    }

    @Test
    void theDeclaredArmsLintClean(@TempDir Path dir) throws Exception {
        assertThat(of(new AppLinter().lint(route(dir.resolve("json"), "query-json", """
                response:
                  json:
                    body:
                      rows: main.rows
                sources:
                  main:
                    sql:
                      file: list.sql
                """)), "TQL-YAML-1066")).isEmpty();
        assertThat(of(new AppLinter().lint(route(dir.resolve("redirect"), "command-json", """
                response:
                  redirect:
                    location: /items
                steps:
                  - id: write
                    sql:
                      file: write.sql
                      mode: update
                """)), "TQL-YAML-1066")).isEmpty();
        Files.createDirectories(dir.resolve("file/web/items"));
        Files.writeString(dir.resolve("file/web/items/report.txt"), "[(${main.rows.size()})]\n");
        assertThat(of(new AppLinter().lint(route(dir.resolve("file"), "page", """
                response:
                  file:
                    template: report.txt
                """)), "TQL-YAML-1066")).isEmpty();
    }

    // ---- the binding arm ----

    @Test
    void aSourceOrStepThatNamesNoArmIsRefusedNamingIt(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(dir, "query-json", """
                response:
                  json:
                    body:
                      rows: main.rows
                sources:
                  main:
                    sql:
                      mode: query
                  rates:
                    http:
                      credential: fx
                """));
        assertThat(of(findings, "TQL-YAML-1067")).hasSize(2);
        assertThat(of(findings, "TQL-YAML-1067").get(0)).satisfies(finding -> {
            assertThat(finding.message()).contains("route 'items.route' sources.main:")
                    .contains("declares no arm to run")
                    .contains("sql: { file: ... }, contract:, service: or http:");
            assertThat(finding.line()).isNotNull();
        });
        assertThat(of(findings, "TQL-YAML-1067").get(1).message())
                .contains("sources.rates:").contains("the http: arm names no url:");
        List<LintFinding> step = new AppLinter().lint(route(dir.resolve("step"), "command-json", """
                response:
                  json:
                    body:
                      ok: true
                steps:
                  - id: touch
                    sql:
                      mode: update
                """));
        assertThat(of(step, "TQL-YAML-1067")).singleElement().satisfies(finding -> assertThat(
                finding.message()).contains("route 'items.route' steps.touch:")
                .contains("http: or sequence:"));
    }

    @Test
    void aToolAndAConsumerAreHeldToTheSameArmRule(@TempDir Path dir) throws Exception {
        app(dir);
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
        Files.createDirectories(dir.resolve("consume/orders"));
        Files.writeString(dir.resolve("consume/orders/get.yml"), """
                version: tesseraql/v1
                id: orders.consume
                kind: route
                recipe: queue-consume
                consume:
                  channel: events
                  topic: orders
                steps:
                  - id: record
                    sql:
                      mode: update
                """);
        List<LintFinding> findings = new AppLinter().lint(dir);
        assertThat(of(findings, "TQL-YAML-1067")).extracting(LintFinding::message)
                .anySatisfy(message -> assertThat(message)
                        .contains("MCP tool 'lookup' sources.main:"))
                .anySatisfy(message -> assertThat(message)
                        .contains("consumer 'orders.consume' steps.record:"));
    }

    // ---- file-import's pieces ----

    private static Path fileImport(Path dir, String body) throws Exception {
        app(dir);
        Files.createDirectories(dir.resolve("web/items/import"));
        Files.writeString(dir.resolve("web/items/import/upsert.sql"),
                "insert into items (name) values (/* name */ 'x')\n");
        Files.writeString(dir.resolve("web/items/import/post.yml"), """
                version: tesseraql/v1
                id: items.import
                kind: route
                recipe: file-import
                security:
                  auth: bearer
                  policy: items.write
                %s
                """.formatted(body));
        return dir;
    }

    @Test
    void aFileImportMissingItsBlockOrItsRowWriteIsRefusedNamingThePiece(@TempDir Path dir)
            throws Exception {
        List<LintFinding> noBlock = new AppLinter().lint(fileImport(dir.resolve("block"), """
                steps:
                  - id: row
                    sql:
                      file: upsert.sql
                """));
        assertThat(of(noBlock, "TQL-YAML-1041")).singleElement().satisfies(finding -> assertThat(
                finding.message()).contains("route 'items.import' import:")
                .contains("needs an import: block"));
        List<LintFinding> noStep = new AppLinter().lint(fileImport(dir.resolve("step"), """
                import:
                  format: csv
                """));
        assertThat(of(noStep, "TQL-YAML-1041")).singleElement().satisfies(finding -> assertThat(
                finding.message()).contains("route 'items.import' steps:")
                .contains("one steps: entry with sql: { file: ... }"));
        List<LintFinding> noFile = new AppLinter().lint(fileImport(dir.resolve("file"), """
                import:
                  format: csv
                steps:
                  - id: row
                    sql:
                      mode: update
                """));
        assertThat(of(noFile, "TQL-YAML-1041")).singleElement().satisfies(finding -> assertThat(
                finding.message()).contains("steps:").contains("what to write per row"));
        // The row step is the import's piece, not a second finding under the arm rule.
        assertThat(of(noFile, "TQL-YAML-1067")).isEmpty();
        assertThat(of(new AppLinter().lint(fileImport(dir.resolve("whole"), """
                import:
                  format: csv
                steps:
                  - id: row
                    sql:
                      file: upsert.sql
                """)), "TQL-YAML-1041")).isEmpty();
    }

    // ---- an export's rows ----

    @Test
    void anExportWithoutAMainFileIsRefusedNamingTheSource(@TempDir Path dir) throws Exception {
        List<LintFinding> none = new AppLinter().lint(route(dir, "query-export", """
                export:
                  format: csv
                """));
        assertThat(of(none, "TQL-YAML-1041")).singleElement().satisfies(finding -> assertThat(
                finding.message()).contains("route 'items.route' sources.main:")
                .contains("no sources: main: sql: { file: ... }"));
        List<LintFinding> contract = new AppLinter().lint(route(dir.resolve("c"), "file-export", """
                export:
                  format: csv
                sources:
                  main:
                    contract:
                      name: iam.users
                """));
        assertThat(of(contract, "TQL-YAML-1041")).extracting(LintFinding::message)
                .anySatisfy(message -> assertThat(message).contains("sources.main:"));
        assertThat(of(new AppLinter().lint(route(dir.resolve("ok"), "query-export", """
                export:
                  format: csv
                sources:
                  main:
                    sql:
                      file: list.sql
                """)), "TQL-YAML-1041")).isEmpty();
    }
}
