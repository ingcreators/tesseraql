package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lints around the export step (docs/analytics-experience.md track 3): the rows the step writes
 * come from the step's own arm, the format is required, the step runs on the job's datasource,
 * and the download-timed follow-up stays route vocabulary.
 */
class AppLinterExportStepTest {

    private static final String EXTRACTION = "    sql:\n      file: report.sql\n      mode: query\n";

    private Path app(@TempDir Path dir, String stepBody) throws Exception {
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
                %s
                """.formatted(stepBody));
        Files.writeString(dir.resolve("batch/report/report.sql"),
                "select name from users order by name\n");
        return dir;
    }

    @Test
    void aWellFormedExportStepIsClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                EXTRACTION + "    export:\n      format: csv"));

        assertThat(findings).noneMatch(finding -> "TQL-YAML-1041".equals(finding.code())
                || "TQL-FIELD-2004".equals(finding.code()));
    }

    @Test
    void anExportStepWithoutItsQueryOrFormatIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    export:\n      format: csv"));
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1041");
            assertThat(finding.message()).contains("sql:");
        });

        findings = new AppLinter()
                .lint(app(dir, EXTRACTION + "    export:\n      filename: x.csv"));
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1041");
            assertThat(finding.message()).contains("format:");
        });
    }

    /**
     * A batch step owns its own transaction, so its <em>read</em> may run on another declared
     * connector (docs/unified-sources.md decision 19) — the name still has to exist.
     */
    @Test
    void aReadStepMayPickAnotherDeclaredConnectorButNotAnUndeclaredOne(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    sql:\n      file: report.sql\n      mode: query\n"
                        + "      datasource: reporting\n"
                        + "    export:\n      format: csv"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1035");
            assertThat(finding.message()).contains("reporting");
        });
        assertThat(findings).noneMatch(finding -> "TQL-YAML-1037".equals(finding.code()));
    }

    /** A write on another connector would be a second transaction the job does not own. */
    @Test
    void aWriteStepCannotPickItsOwnDatasource(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    sql:\n      file: report.sql\n      mode: update\n"
                        + "      datasource: reporting"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1037");
            assertThat(finding.message()).contains("only a read step");
        });
    }

    @Test
    void aDownloadTimedFollowUpIsRouteVocabulary(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                EXTRACTION + "    export:\n      format: csv\n"
                        + "      after:\n        timing: download\n"
                        + "        sql:\n          file: report.sql\n          mode: update"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1005");
            assertThat(finding.message()).contains("timing: extract");
        });
    }

    @Test
    void aStepDecliningToDeclareAnyWorkIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, "    when: params.dryRun"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-FIELD-2004");
            assertThat(finding.message()).contains("declares no work");
        });
    }

    @Test
    void aStepCannotDeclareTwoBindings(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                EXTRACTION + "    http:\n      method: GET\n"
                        + "      url: https://partner.example/x"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-FIELD-2004");
            assertThat(finding.message()).contains("two bindings");
        });
    }
}
