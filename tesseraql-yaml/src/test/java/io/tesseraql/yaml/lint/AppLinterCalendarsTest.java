package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lints around business-day calendars (docs/batch-platform.md track B). At fire time an
 * unresolvable calendar fails open — the firing runs — so the build is the only place a typo
 * gets to be loud.
 */
class AppLinterCalendarsTest {

    private Path app(@TempDir Path dir, String calendars, String schedule) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        if (calendars != null) {
            Files.createDirectories(dir.resolve("calendars"));
            Files.writeString(dir.resolve("calendars/main.yml"), calendars);
        }
        Files.createDirectories(dir.resolve("batch/nightly"));
        Files.writeString(dir.resolve("batch/nightly/job.yml"), """
                version: tesseraql/v1
                id: nightly.close
                kind: job
                recipe: batch-tasklet
                trigger:
                  schedule:
                %s
                sql: { file: close.sql, mode: update }
                """.formatted(schedule));
        Files.writeString(dir.resolve("batch/nightly/close.sql"), "select 1\n");
        return dir;
    }

    @Test
    void anUnknownCalendarReferenceIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter()
                .lint(app(dir, null, "    cron: \"0 0 2 * * ?\"\n    calendar: jp-banking"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-BATCH-4201");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.message()).contains("jp-banking");
        });
    }

    @Test
    void runOnWithoutACalendarAndAnUnknownRunOnAreErrors(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter()
                .lint(app(dir, null, "    cron: \"0 0 2 * * ?\"\n    runOn: lastBusinessDay"));

        assertThat(findings.stream().filter(f -> "TQL-BATCH-4202".equals(f.code())))
                .hasSize(2); // no calendar: to qualify, and not a known runOn value
    }

    @Test
    void datesAndSourceTogetherAreAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                version: tesseraql/v1
                calendars:
                  jp-banking:
                    holidays:
                      dates: [2026-01-01]
                      source: { table: holidays, date: holiday_date }
                """, "    cron: \"0 0 2 * * ?\"\n    calendar: jp-banking"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-BATCH-4203");
            assertThat(finding.source()).isEqualTo("calendars/main.yml");
        });
        assertThat(findings).noneMatch(finding -> "TQL-BATCH-4201".equals(finding.code()));
    }

    @Test
    void aStructurallyBrokenCalendarsDirBecomesAFindingNotACrash(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                version: tesseraql/v1
                calendars:
                  jp-banking:
                    weekend: [caturday]
                """, "    cron: \"0 0 2 * * ?\"\n    calendar: jp-banking"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-BATCH-4205");
            assertThat(finding.severity()).isEqualTo("error");
        });
    }

    @Test
    void nominalDayQualifiersAreValidated(@TempDir Path dir) throws Exception {
        // dayOfMonth without a calendar, out of range, combined with runOn - and runOn is
        // itself missing its calendar: four distinct declarations that cannot mean anything.
        List<LintFinding> findings = new AppLinter().lint(app(dir, null,
                "    cron: \"0 0 8 * * ?\"\n    dayOfMonth: 45\n    runOn: business-day"));
        assertThat(findings.stream().filter(f -> "TQL-BATCH-4202".equals(f.code())))
                .hasSize(4);

        findings = new AppLinter().lint(app(dir, """
                version: tesseraql/v1
                calendars:
                  jp-banking:
                    weekend: [saturday, sunday]
                """, "    cron: \"0 0 8 * * ?\"\n    calendar: jp-banking\n    shift: sideways"));
        assertThat(findings.stream().filter(f -> "TQL-BATCH-4202".equals(f.code())))
                .hasSize(2); // shift without dayOfMonth, and an unknown direction

        findings = new AppLinter().lint(app(dir, """
                version: tesseraql/v1
                calendars:
                  jp-banking:
                    weekend: [saturday, sunday]
                """, "    cron: \"0 0 8 * * ?\"\n    calendar: jp-banking\n"
                + "    dayOfMonth: 5\n    shift: next-business-day"));
        assertThat(findings).noneMatch(finding -> finding.code().startsWith("TQL-BATCH-42"));
    }

    @Test
    void aWellFormedCalendarReferenceIsClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                version: tesseraql/v1
                calendars:
                  jp-banking:
                    weekend: [saturday, sunday]
                    holidays:
                      source: { table: holidays, date: holiday_date, calendar: calendar_id }
                """, "    cron: \"0 0 2 * * ?\"\n    calendar: jp-banking\n"
                + "    runOn: last-business-day-of-month"));

        assertThat(findings).noneMatch(finding -> finding.code().startsWith("TQL-BATCH-42"));
    }
}
