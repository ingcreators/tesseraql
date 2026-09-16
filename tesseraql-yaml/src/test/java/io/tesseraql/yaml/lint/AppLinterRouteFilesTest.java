package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The files a document names, judged at lint by the resolver the compiler refuses from
 * (docs/audit-low-leads.md slice 14). Every escaping fixture here used to lint clean — zero
 * findings — pass {@code tesseraql admission}, boot, and serve: the statement outside the
 * application home ran, the workbook outside it was delivered, the page template outside it
 * was the one reference the boot alone refused. A reference inside the home stays legal
 * wherever it sits, and a reference that is not there keeps the lint's own missing-file
 * codes. The app home is a subdirectory of the temp root, so {@code ../} can leave it.
 */
class AppLinterRouteFilesTest {

    private static Path home(Path root) throws Exception {
        Path home = Files.createDirectories(root.resolve("app"));
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: shop\n");
        Files.writeString(root.resolve("outside.sql"), "select id from items\n");
        Files.writeString(root.resolve("outside.html"), "<p>outside</p>\n");
        Files.writeString(root.resolve("outside.xlsx"), "PK\3\4");
        return home;
    }

    private static Path route(Path root, String recipe, String body) throws Exception {
        Path home = home(root);
        Files.createDirectories(home.resolve("web/items"));
        Files.writeString(home.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.route
                kind: route
                recipe: %s
                security:
                  auth: public
                %s
                """.formatted(recipe, body));
        return home;
    }

    private static List<LintFinding> of(List<LintFinding> findings, String code) {
        return findings.stream().filter(finding -> finding.code().equals(code)).toList();
    }

    @Test
    void aStatementOutsideTheHomeIsRefusedNamingTheRouteAndTheKey(@TempDir Path root)
            throws Exception {
        // web/items/../../../outside.sql = root/outside.sql: three levels up leaves the home.
        List<LintFinding> findings = new AppLinter().lint(route(root, "query-json", """
                response:
                  json:
                    body:
                      rows: main.rows
                sources:
                  main:
                    sql:
                      file: ../../../outside.sql
                """));
        assertThat(of(findings, "TQL-YAML-1075")).singleElement().satisfies(finding -> {
            assertThat(finding.isError()).isTrue();
            assertThat(finding.message()).contains("route 'items.route'",
                    "sources.main.file:", "'../../../outside.sql'",
                    "resolves outside the application home");
            assertThat(finding.line()).as("anchored where the value is written").isNotNull();
        });
        assertThat(of(findings, "TQL-SQL-2103")).as("the file is there; only the fence refuses")
                .isEmpty();
    }

    @Test
    void everyStatementSlotIsFenced(@TempDir Path root) throws Exception {
        Path home = route(root, "command-json", """
                response:
                  json:
                    body:
                      ok: true
                sources:
                  lookup:
                    sql:
                      file: ../../../outside.sql
                    enrich:
                      names:
                        on: { id: id }
                        sql:
                          file: ../../../outside.sql
                validate:
                  inStock:
                    file: ../../../outside.sql
                    field: id
                steps:
                  - id: write
                    sql:
                      file: ../../../outside.sql
                      mode: update
                """);
        List<LintFinding> findings = of(new AppLinter().lint(home), "TQL-YAML-1075");
        assertThat(findings).extracting(LintFinding::message).allSatisfy(message -> assertThat(
                message).contains("route 'items.route'", "resolves outside the application home"));
        assertThat(findings).extracting(LintFinding::message)
                .anySatisfy(message -> assertThat(message).contains("sources.lookup.file:"))
                .anySatisfy(message -> assertThat(message)
                        .contains("sources.lookup.enrich.names.sql.file:"))
                .anySatisfy(message -> assertThat(message).contains("validate.inStock.file:"))
                .anySatisfy(message -> assertThat(message).contains("steps.write.file:"));
        assertThat(findings).hasSize(4);
    }

    @Test
    void aReferenceInsideTheHomeIsLegalWhereverItSits(@TempDir Path root) throws Exception {
        Path home = home(root);
        Files.createDirectories(home.resolve("shared"));
        Files.writeString(home.resolve("shared/order.sql"), "select id from orders\n");
        Files.createDirectories(home.resolve("web/orders/detail/q"));
        Files.writeString(home.resolve("web/orders/detail/q/rows.sql"), "select 1\n");
        Files.writeString(home.resolve("web/orders/detail/get.yml"), """
                version: tesseraql/v1
                id: orders.detail
                kind: route
                recipe: query-json
                security:
                  auth: public
                response:
                  json:
                    body:
                      rows: main.rows
                      order: order.rows
                sources:
                  main:
                    sql:
                      file: q/rows.sql
                  order:
                    sql:
                      file: ../../../shared/order.sql
                """);
        List<LintFinding> findings = new AppLinter().lint(home);
        assertThat(of(findings, "TQL-YAML-1075")).isEmpty();
        assertThat(of(findings, "TQL-SQL-2103")).isEmpty();
    }

    @Test
    void aMissingStatementKeepsTheLintsOwnCodeOnce(@TempDir Path root) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(root, "command-json", """
                response:
                  json:
                    body:
                      ok: true
                validate:
                  inStock:
                    file: rules/nowhere.sql
                    field: id
                steps:
                  - id: write
                    sql:
                      file: q/nowhere.sql
                      mode: update
                """));
        assertThat(of(findings, "TQL-SQL-2103")).extracting(LintFinding::message)
                .as("one finding per slot — the validation rule's file is not reported twice")
                .hasSize(2)
                .anySatisfy(message -> assertThat(message).contains("validate.inStock.file:",
                        "referenced SQL file is missing: rules/nowhere.sql"))
                .anySatisfy(message -> assertThat(message).contains("steps.write.file:",
                        "referenced SQL file is missing: q/nowhere.sql"));
        assertThat(of(findings, "TQL-YAML-1075")).isEmpty();
    }

    @Test
    void aPageTemplateIsFencedAndMustResolve(@TempDir Path root) throws Exception {
        Path home = route(root, "page", """
                response:
                  html:
                    template: ../../../outside.html
                """);
        assertThat(of(new AppLinter().lint(home), "TQL-YAML-1075")).singleElement()
                .satisfies(finding -> assertThat(finding.message()).contains(
                        "route 'items.route'", "response.html.template:",
                        "'../../../outside.html'", "resolves outside the application home"));

        // A template that is nowhere — neither beside the document nor under templates/ —
        // used to lint clean and refuse the boot alone.
        Files.writeString(home.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.route
                kind: route
                recipe: page
                security:
                  auth: public
                response:
                  html:
                    template: nowhere.html
                """);
        assertThat(of(new AppLinter().lint(home), "TQL-TPL-2001")).singleElement()
                .satisfies(finding -> {
                    assertThat(finding.isError()).isTrue();
                    assertThat(finding.message()).contains("route 'items.route'",
                            "response.html.template:", "'nowhere.html'",
                            "resolves to no file beside the document or under templates/");
                });

        // Under templates/ is where a shared layout lives: legal, and found.
        Files.createDirectories(home.resolve("templates"));
        Files.writeString(home.resolve("templates/nowhere.html"), "<p>shared</p>\n");
        List<LintFinding> findings = new AppLinter().lint(home);
        assertThat(of(findings, "TQL-TPL-2001")).isEmpty();
        assertThat(of(findings, "TQL-YAML-1075")).isEmpty();
    }

    @Test
    void anExportTemplateOutsideTheHomeIsTheExportBlocksOwnRefusal(@TempDir Path root)
            throws Exception {
        Path home = route(root, "query-export", """
                sources:
                  main:
                    sql:
                      file: list.sql
                export:
                  format: excel
                  template: ../../../outside.xlsx
                  startCell: A2
                """);
        Files.writeString(home.resolve("web/items/list.sql"), "select id from items\n");
        List<LintFinding> findings = new AppLinter().lint(home);
        assertThat(of(findings, "TQL-YAML-1075")).singleElement().satisfies(finding -> assertThat(
                finding.message()).contains("route 'items.route'", "export.template:",
                        "'../../../outside.xlsx'", "resolves outside the application home"));
        assertThat(of(findings, "TQL-YAML-1006")).as("the fence speaks first, once").isEmpty();
    }
}
