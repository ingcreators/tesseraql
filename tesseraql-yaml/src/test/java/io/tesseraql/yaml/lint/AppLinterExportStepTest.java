package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lints around the export step (docs/analytics-experience.md track 3): the extraction query
 * and format are required, the step runs on the job's datasource, and the download-timed
 * follow-up stays route vocabulary.
 */
class AppLinterExportStepTest {

    private Path app(@TempDir Path dir, String exportBody) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("batch/report"));
        Files.writeString(dir.resolve("batch/report/job.yml"), """
                version: tesseraql/v1
                id: report.daily
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: report
                    export:
                %s
                """.formatted(exportBody));
        Files.writeString(dir.resolve("batch/report/report.sql"),
                "select name from users order by name\n");
        return dir;
    }

    @Test
    void aWellFormedExportStepIsClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "      format: csv\n      sql: { file: report.sql, mode: query }"));

        assertThat(findings).noneMatch(finding -> "TQL-YAML-1041".equals(finding.code())
                || "TQL-FIELD-2004".equals(finding.code()));
    }

    @Test
    void anExportStepWithoutItsQueryOrFormatIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, "      format: csv"));
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1041");
            assertThat(finding.message()).contains("sql:");
        });

        findings = new AppLinter().lint(app(dir,
                "      sql: { file: report.sql, mode: query }"));
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1041");
            assertThat(finding.message()).contains("format:");
        });
    }

    @Test
    void anExportStepCannotPickItsOwnDatasource(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "      format: csv\n"
                        + "      sql: { file: report.sql, mode: query, datasource: reporting }"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1037");
            assertThat(finding.message()).contains("job's datasource");
        });
    }

    @Test
    void aDownloadTimedFollowUpIsRouteVocabulary(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "      format: csv\n      sql: { file: report.sql, mode: query }\n"
                        + "      after:\n        timing: download\n"
                        + "        sql: { file: report.sql, mode: update }"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1041");
            assertThat(finding.message()).contains("timing: extract");
        });
    }

    @Test
    void aStepDecliningToChooseItsOneBodyIsAnError(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("batch/report"));
        Files.writeString(dir.resolve("batch/report/job.yml"), """
                version: tesseraql/v1
                id: report.daily
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: report
                    sql: { file: report.sql, mode: query }
                    export:
                      format: csv
                      sql: { file: report.sql, mode: query }
                """);
        Files.writeString(dir.resolve("batch/report/report.sql"), "select 1 as one\n");

        List<LintFinding> findings = new AppLinter().lint(dir);
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-FIELD-2004");
            assertThat(finding.message()).contains("export:");
        });
    }
}
