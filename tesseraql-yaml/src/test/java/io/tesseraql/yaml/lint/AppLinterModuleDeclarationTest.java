package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The codec declaration lint (docs/module-channel.md decision 3): an export whose codec lives in
 * an opt-in module must say so under {@code tesseraql.modules}, because that declaration is what a
 * package carries and what a host verifies. Without it the application exports happily wherever
 * the codec happens to be on a classpath, and fails at the first export once deployed.
 */
class AppLinterModuleDeclarationTest {

    private static final String CODE = "TQL-YAML-1408";

    private Path app(Path dir, String modules, String format) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                %s""".formatted(modules));
        Files.createDirectories(dir.resolve("batch/report"));
        Files.writeString(dir.resolve("batch/report/job.yml"), """
                version: tesseraql/v1
                id: report.daily
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: report
                    sql:
                      file: report.sql
                      mode: query
                    export:
                      format: %s
                      template: report.html
                """.formatted(format));
        Files.writeString(dir.resolve("batch/report/report.sql"),
                "select name from users order by name\n");
        Files.writeString(dir.resolve("batch/report/report.html"), "<html></html>\n");
        return dir;
    }

    @Test
    void anUndeclaredOptInCodecWarns(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, "", "pdf"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo(CODE);
            assertThat(finding.level()).isEqualTo(LintFinding.Severity.WARNING);
            assertThat(finding.message()).contains("io.tesseraql:tesseraql-pdf")
                    .contains("tesseraql.modules");
        });
    }

    @Test
    void aDeclaredCodecIsSilent(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "  modules:\n    - io.tesseraql:tesseraql-pdf\n", "pdf"));

        assertThat(findings).noneMatch(finding -> CODE.equals(finding.code()));
    }

    /** A version on the coordinate is still the same module — the lint compares group:artifact. */
    @Test
    void aVersionedDeclarationIsSilent(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "  modules:\n    - io.tesseraql:tesseraql-excel:0.15.0\n", "excel"));

        assertThat(findings).noneMatch(finding -> CODE.equals(finding.code()));
    }

    /**
     * A name no codec in the run's set serves — an application's own codec before it is
     * declared, or a typo — warns naming the mechanism (docs/codec-discovery.md decision 3);
     * the same name against a set that carries it is silent, and so is an import's.
     */
    @Test
    void aFormatOutsideTheRunsSetWarnsAndInsideItIsSilent(@TempDir Path dir) throws Exception {
        Path app = app(dir, "", "fixedwidth");
        Files.createDirectories(app.resolve("web/api/items/import"));
        Files.writeString(app.resolve("web/api/items/import/post.yml"), """
                version: tesseraql/v1
                id: items.import
                kind: route
                recipe: file-import
                security:
                  auth: public
                import:
                  format: fixedwidth
                  columns:
                    - name
                steps:
                  - id: row
                    sql:
                      file: upsert.sql
                """);
        Files.writeString(app.resolve("web/api/items/import/upsert.sql"),
                "insert into items (name) values (/* name */ 'x')\n");

        List<LintFinding> findings = new AppLinter().lint(app);

        assertThat(findings).filteredOn(finding -> CODE.equals(finding.code()))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.level()).isEqualTo(LintFinding.Severity.WARNING);
                    assertThat(finding.message()).contains("format: fixedwidth")
                            .contains("names no codec").contains("tesseraql.modules")
                            .contains("--modules");
                });

        io.tesseraql.core.files.FileCodecs withIt = io.tesseraql.core.files.FileCodecs
                .of(new FixedWidth());
        assertThat(new AppLinter().lint(app,
                io.tesseraql.core.expr.ExpressionFunctions.processDefault(), withIt))
                .noneMatch(finding -> CODE.equals(finding.code()));
    }

    /** A codec by name only: the lint asks whether it exists and never writes. */
    private static final class FixedWidth implements io.tesseraql.core.files.FileCodec {

        @Override
        public String format() {
            return "fixedwidth";
        }

        @Override
        public String contentType() {
            return "text/plain";
        }

        @Override
        public String extension() {
            return ".txt";
        }

        @Override
        public void read(java.io.InputStream in, io.tesseraql.core.files.FileReadSpec spec,
                io.tesseraql.core.files.RowHandler handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(java.io.OutputStream out, io.tesseraql.core.files.FileWriteSpec spec,
                io.tesseraql.core.files.ExportModel model) {
            throw new UnsupportedOperationException();
        }
    }

    /** A built-in format needs no module, so it never reaches this rule. */
    @Test
    void aBuiltInFormatIsSilent(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, "", "csv"));

        assertThat(findings).noneMatch(finding -> CODE.equals(finding.code()));
    }
}
