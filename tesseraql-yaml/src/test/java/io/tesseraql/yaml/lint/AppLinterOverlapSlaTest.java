package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lints around {@code overlap:} and {@code sla:} (docs/batch-platform.md track E): both are
 * operational promises evaluated long after the deploy, so a value that cannot mean anything
 * must be a build error, not a sweep that silently never fires.
 */
class AppLinterOverlapSlaTest {

    private Path app(@TempDir Path dir, String declarations) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("batch/nightly"));
        Files.writeString(dir.resolve("batch/nightly/job.yml"), """
                version: tesseraql/v1
                id: nightly.close
                kind: job
                recipe: batch-tasklet
                %s
                sql:
                  file: close.sql
                  mode: query
                """.formatted(declarations));
        Files.writeString(dir.resolve("batch/nightly/close.sql"), "select 1\n");
        return dir;
    }

    @Test
    void anUnknownOverlapPolicyIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, "overlap: queue"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-BATCH-4210");
            assertThat(finding.message()).contains("queue");
        });
    }

    @Test
    void unparseableSlaValuesAndAnEmptySlaAreErrors(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "sla: { completeBy: \"6 in the morning\", runningLongerThan: eventually }"));
        assertThat(findings.stream().filter(f -> "TQL-BATCH-4210".equals(f.code()))).hasSize(2);

        findings = new AppLinter().lint(app(dir, "sla: {}"));
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-BATCH-4210");
            assertThat(finding.message()).contains("without completeBy");
        });
    }

    @Test
    void wellFormedOverlapAndSlaAreClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "overlap: skip\nsla: { completeBy: \"06:00\", runningLongerThan: 2h }"));

        assertThat(findings).noneMatch(finding -> "TQL-BATCH-4210".equals(finding.code()));
    }
}
