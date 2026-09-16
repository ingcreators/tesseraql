package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A job's {@code schedule:} block, judged where it is written (docs/audit-low-leads.md slice 8,
 * XD-07f): the {@code cron:} by the grammar the scheduler fires it by — Quartz's own, the
 * predicate the runtime refuses from (decision 10) — and the {@code fixedDelay:} as a
 * duration. Both used to lint clean: the five-field crontab reflex passed the scaffold's
 * verify and took the whole application down at boot with an uncoded exception.
 */
class AppLinterScheduleTest {

    private static Path job(Path dir, String schedule) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("batch/nightly"));
        Files.writeString(dir.resolve("batch/nightly/close.sql"), "update t set closed = 1\n");
        Files.writeString(dir.resolve("batch/nightly/job.yml"), """
                version: tesseraql/v1
                id: nightly.close
                kind: job
                recipe: batch-pipeline
                trigger:
                  schedule:
                %s
                pipeline:
                  - id: close
                    sql:
                      file: close.sql
                      mode: update
                """.formatted(schedule));
        return dir;
    }

    private static List<LintFinding> of(List<LintFinding> findings, String code) {
        return findings.stream().filter(finding -> finding.code().equals(code)).toList();
    }

    @Test
    void aFiveFieldCronIsRefusedNamingTheJobAndTheReason(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(job(dir, "    cron: \"0 3 * * *\""));
        assertThat(of(findings, "TQL-YAML-1068")).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.source()).isEqualTo("batch/nightly/job.yml");
            assertThat(finding.message())
                    .startsWith("Job 'nightly.close' schedule.cron: '0 3 * * *'")
                    .contains("not a cron expression the scheduler can fire")
                    .contains("seconds first");
            assertThat(finding.line()).isEqualTo(7);
        });
    }

    @Test
    void aQuartzCronWithAStarInBothDayFieldsIsRefusedToo(@TempDir Path dir) throws Exception {
        // The spelling that looks right and is not: Quartz wants ? in one of the day fields.
        List<LintFinding> findings = new AppLinter().lint(job(dir, "    cron: \"0 0 3 * * *\""));
        assertThat(of(findings, "TQL-YAML-1068")).singleElement().satisfies(finding -> assertThat(
                finding.message()).contains("'0 0 3 * * *'"));
    }

    @Test
    void theSchedulersOwnSpellingLintsClean(@TempDir Path dir) throws Exception {
        assertThat(of(new AppLinter().lint(job(dir, "    cron: \"0 0 3 * * ?\"")), "TQL-YAML-1068"))
                .isEmpty();
    }

    @Test
    void aFixedDelayThatIsNotADurationIsRefusedNamingTheJob(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter().lint(job(dir, "    fixedDelay: soon"));
        assertThat(of(findings, "TQL-YAML-1054")).singleElement().satisfies(finding -> assertThat(
                finding.message()).contains("Job 'nightly.close' schedule.fixedDelay: 'soon'")
                .contains("not a duration"));
        assertThat(of(new AppLinter().lint(job(dir.resolve("ok"), "    fixedDelay: 30s")),
                "TQL-YAML-1054")).isEmpty();
    }
}
