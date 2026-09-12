package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A route's {@code export:} block chooses a workbook mode by what it declares
 * (docs/export-pipeline.md, decision 4), so a declaration that cannot mean what it says produced
 * a different document in silence: a mistyped template path fell through to a plain grid, losing
 * the layout, the styles and the memory profile at once. Job export steps have been checked all
 * along; routes had only the PDF-specific check.
 */
class AppLinterRouteExportTest {

    private Path app(Path dir, String exportBody) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/dump.sql"), "select id from items\n");
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.dump
                kind: route
                recipe: file-export
                method: GET
                path: /api/items/dump
                security:
                  auth: public
                export:
                %s
                """.formatted(exportBody));
        return dir;
    }

    @Test
    void aTemplateThatIsNotThereIsAnErrorRatherThanAPlainGrid(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                  format: excel
                  template: styled.xlsx
                  startCell: B5
                  sql:
                    file: dump.sql
                    mode: query-export
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1006");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.message()).contains("missing template", "styled.xlsx");
        });
    }

    @Test
    void aTemplateThatIsThereIsClean(@TempDir Path dir) throws Exception {
        Path app = app(dir, """
                  format: excel
                  template: styled.xlsx
                  startCell: B5
                  sql:
                    file: dump.sql
                    mode: query-export
                """);
        Files.write(app.resolve("web/items/styled.xlsx"), new byte[]{1});

        assertThat(new AppLinter().lint(app))
                .noneMatch(finding -> "TQL-YAML-1006".equals(finding.code())
                        || "TQL-YAML-1005".equals(finding.code())
                        || "TQL-YAML-1041".equals(finding.code()));
    }

    @Test
    void placementWithoutATemplateNamesAModeThatDoesNotExist(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                  format: excel
                  startCell: B5
                  sql:
                    file: dump.sql
                    mode: query-export
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1041");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.message()).contains("startCell:", "template:");
        });
    }

    @Test
    void aPlainGridIsStillClean(@TempDir Path dir) throws Exception {
        assertThat(new AppLinter().lint(app(dir, """
                  format: excel
                  sql:
                    file: dump.sql
                    mode: query-export
                """)))
                .noneMatch(finding -> "TQL-YAML-1006".equals(finding.code())
                        || "TQL-YAML-1005".equals(finding.code())
                        || "TQL-YAML-1041".equals(finding.code()));
    }

    /** The helper's own {@code method:}/{@code path:} keys draw unknown-key warnings, so "clean" is keyed on the message. */
    private static boolean namesTheMark(LintFinding finding) {
        return finding.message().contains("bom");
    }

    @Test
    void aByteOrderMarkOnAWorkbookRouteIsAnInapplicableOption(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                  format: excel
                  bom: true
                  sql:
                    file: dump.sql
                    mode: query-export
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1005");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.message()).contains("bom:", "excel");
        });
    }

    @Test
    void aByteOrderMarkOnAPdfRouteIsAnInapplicableOption(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                  format: pdf
                  bom: true
                  sql:
                    file: dump.sql
                    mode: query-export
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1005");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.message()).contains("bom:", "pdf");
        });
    }

    /** Presence is what is refused, as with {@code sheet:} on a pdf: a declined mark is a leftover. */
    @Test
    void aDeclinedByteOrderMarkOnAWorkbookIsStillAnInapplicableOption(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                  format: excel
                  bom: false
                  sql:
                    file: dump.sql
                    mode: query-export
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1005");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.message()).contains("bom:");
        });
    }

    @Test
    void aByteOrderMarkOnACsvRouteIsAKnownAndCleanKey(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                  format: csv
                  bom: true
                  sql:
                    file: dump.sql
                    mode: query-export
                """));

        // Neither inapplicable (1005) nor unknown (1043): nothing names the key.
        assertThat(findings).noneMatch(AppLinterRouteExportTest::namesTheMark);
    }

    /** An unset {@code format:} is a route's csv default, so the mark applies. */
    @Test
    void aByteOrderMarkWithoutAFormatIsTheCsvDefaultAndClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                  filename: items.csv
                  bom: true
                  sql:
                    file: dump.sql
                    mode: query-export
                """));

        assertThat(findings).noneMatch(AppLinterRouteExportTest::namesTheMark);
    }

    /** A format the framework does not ship belongs to a module codec; the linter does not judge it. */
    @Test
    void aByteOrderMarkOnAModuleFormatIsNotJudged(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                  format: parquet
                  bom: true
                  sql:
                    file: dump.sql
                    mode: query-export
                """));

        assertThat(findings).noneMatch(finding -> "TQL-YAML-1005".equals(finding.code())
                && namesTheMark(finding));
    }
}
