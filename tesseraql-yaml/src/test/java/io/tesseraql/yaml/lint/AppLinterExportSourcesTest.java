package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An export's other declared sources (docs/export-pipeline.md, decision 2) need somewhere to go,
 * and their failures need somewhere to surface. A CSV export writes rows and nothing else, so a
 * named query beside it runs to be discarded; and {@code onError: empty} means something different
 * in a document than on a page — a gap a human sees becomes a filed document that looks complete.
 */
class AppLinterExportSourcesTest {

    private Path app(Path dir, String routeBody) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/orders"));
        Files.writeString(dir.resolve("web/orders/lines.sql"), "select item from order_lines\n");
        Files.writeString(dir.resolve("web/orders/header.sql"), "select customer from orders\n");
        Files.writeString(dir.resolve("web/orders/order.html"), "<html><body>x</body></html>\n");
        Files.writeString(dir.resolve("web/orders/get.yml"), """
                version: tesseraql/v1
                id: orders.print
                kind: route
                recipe: query-export
                method: GET
                path: /api/orders/print
                security:
                  auth: public
                sql:
                  file: lines.sql
                  mode: query-export
                %s
                """.formatted(routeBody));
        return dir;
    }

    @Test
    void aNamedQueryOnATemplatelessExportIsWarnedAbout(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                export:
                  format: csv
                  queries:
                    header:
                      file: header.sql
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-LD-5312");
            assertThat(finding.severity()).isEqualTo("warning");
            assertThat(finding.message()).contains("no template:", "discarded");
        });
    }

    @Test
    void aNamedQueryOnATemplatedExportIsClean(@TempDir Path dir) throws Exception {
        assertThat(new AppLinter().lint(app(dir, """
                export:
                  format: pdf
                  template: order.html
                  maxRows: 100
                  queries:
                    header:
                      file: header.sql
                """)))
                .noneMatch(finding -> "TQL-LD-5312".equals(finding.code()));
    }

    @Test
    void aGroupedExportWantsAnOrderedQuery(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                export:
                  format: pdf
                  template: order.html
                  maxRows: 100
                  groupBy: item
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-LD-5311");
            assertThat(finding.severity()).isEqualTo("warning");
            assertThat(finding.message()).contains("no order by", "one group as several");
        });
    }

    @Test
    void anOrderedGroupedExportIsClean(@TempDir Path dir) throws Exception {
        Path app = app(dir, """
                export:
                  format: pdf
                  template: order.html
                  maxRows: 100
                  groupBy: item
                """);
        Files.writeString(app.resolve("web/orders/lines.sql"),
                "select item from order_lines order by item\n");

        assertThat(new AppLinter().lint(app))
                .noneMatch(finding -> "TQL-LD-5311".equals(finding.code()));
    }

    @Test
    void anHttpSourceDegradingToEmptyIsRefusedOnAnExport(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                http:
                  rates:
                    url: https://partner.example/rates
                    onError: empty
                export:
                  format: pdf
                  template: order.html
                  maxRows: 100
                """));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1006");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.message()).contains("onError: empty", "rates");
        });
    }

    @Test
    void anHttpSourceThatFailsLoudlyIsAccepted(@TempDir Path dir) throws Exception {
        assertThat(new AppLinter().lint(app(dir, """
                http:
                  rates:
                    url: https://partner.example/rates
                export:
                  format: pdf
                  template: order.html
                  maxRows: 100
                """)))
                .noneMatch(finding -> "TQL-LD-5312".equals(finding.code())
                        || ("TQL-YAML-1006".equals(finding.code())
                                && finding.message().contains("onError")));
    }
}
