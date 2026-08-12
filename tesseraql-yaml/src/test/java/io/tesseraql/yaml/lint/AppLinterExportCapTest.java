package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An export through a format that holds every row before it writes runs under a ceiling
 * (docs/export-pipeline.md, decision 7). {@code maxRows} used to exist only on the materializing
 * query path, so a {@code format: pdf} export of an unbounded query had nothing between it and the
 * heap. A streaming format has nothing to cap and stays quiet.
 */
class AppLinterExportCapTest {

    private Path app(Path dir, String exportBody) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/dump.sql"), "select id from items\n");
        Files.writeString(dir.resolve("web/items/print.html"), "<html><body>x</body></html>\n");
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.dump
                kind: route
                recipe: query-export
                method: GET
                path: /api/items/dump
                security:
                  auth: public
                export:
                %s
                sources:
                  main:
                    sql:
                      file: dump.sql
                      mode: query-export
                """.formatted(exportBody));
        return dir;
    }

    @Test
    void anUncappedPdfExportIsWarnedAbout(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                  format: pdf
                  template: print.html
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-LD-5310");
            assertThat(finding.severity()).isEqualTo("warning");
            assertThat(finding.message()).contains("holds every row", "maxRows:");
        });
    }

    @Test
    void declaringTheCeilingSilencesIt(@TempDir Path dir) throws Exception {
        assertThat(new AppLinter().lint(app(dir, """
                  format: pdf
                  template: print.html
                  maxRows: 500
                """)))
                .noneMatch(finding -> "TQL-LD-5310".equals(finding.code()));
    }

    @Test
    void aStreamingFormatIsNeverWarnedAbout(@TempDir Path dir) throws Exception {
        assertThat(new AppLinter().lint(app(dir, "  format: csv\n")))
                .noneMatch(finding -> "TQL-LD-5310".equals(finding.code()));

        // The workbook grid streams too; only its template modes hold the rows.
        assertThat(new AppLinter().lint(app(dir, "  format: excel\n")))
                .noneMatch(finding -> "TQL-LD-5310".equals(finding.code()));
    }

    @Test
    void aWorkbookTemplateIsWarnedAboutLikeAPdf(@TempDir Path dir) throws Exception {
        Path app = app(dir, """
                  format: excel
                  template: styled.xlsx
                """);
        Files.write(app.resolve("web/items/styled.xlsx"), new byte[]{1});

        assertThat(new AppLinter().lint(app))
                .anySatisfy(finding -> assertThat(finding.code()).isEqualTo("TQL-LD-5310"));
    }
}
