package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Studio job-policies form (docs/jobs.md, Studio): trigger, calendar qualifiers, and the
 * operational promises as structured fields — every declaration that cannot mean anything
 * dies before a draft exists.
 */
class StudioServiceJobPoliciesTest {

    private StudioService studio(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("calendars"));
        Files.writeString(dir.resolve("calendars/jp.yml"), """
                version: tesseraql/v1
                calendars:
                  jp-banking:
                    weekend: [saturday, sunday]
                """);
        Files.createDirectories(dir.resolve("batch/close"));
        Files.writeString(dir.resolve("batch/close/job.yml"), """
                version: tesseraql/v1
                id: nightly.close
                kind: job
                recipe: batch-tasklet
                trigger:
                  schedule:
                    cron: "0 0 8 * * ?"
                    calendar: jp-banking
                    dayOfMonth: 5
                overlap: skip
                sla: { completeBy: "06:00" }
                sql: { file: close.sql, mode: update }
                """);
        Files.writeString(dir.resolve("batch/close/close.sql"), "select 1\n");
        Files.createDirectories(dir.resolve("batch/send"));
        Files.writeString(dir.resolve("batch/send/job.yml"), """
                version: tesseraql/v1
                id: nightly.send
                kind: job
                recipe: batch-tasklet
                sql: { file: send.sql, mode: update }
                """);
        Files.writeString(dir.resolve("batch/send/send.sql"), "select 1\n");
        return new StudioService(new ManifestLoader().load(dir), false);
    }

    @Test
    void theFormLoadsEveryPolicyField(@TempDir Path dir) throws Exception {
        StudioService.JobPolicyForm form = studio(dir).jobPolicyForm("nightly.close");

        assertThat(form.cron()).isEqualTo("0 0 8 * * ?");
        assertThat(form.calendar()).isEqualTo("jp-banking");
        assertThat(form.dayOfMonth()).isEqualTo("5");
        assertThat(form.overlap()).isEqualTo("skip");
        assertThat(form.slaCompleteBy()).isEqualTo("06:00");
        assertThat(form.pollTriggered()).isFalse();
        assertThat(studio(dir).jobPolicyForm("no-such")).isNull();
    }

    @Test
    void savingRewritesTheTriggerAndKeepsTheRestOfTheDocument(@TempDir Path dir)
            throws Exception {
        StudioService studio = studio(dir);

        // The chain form: nightly.send fires after nightly.close.
        Path draft = studio.saveJobPolicies("nightly.send", null, null, null, null, null, null,
                "nightly.close", "skip", null, "2h", "operator");
        String yaml = Files.readString(draft);
        assertThat(yaml).contains("after:").contains("nightly.close")
                .contains("overlap:").contains("skip")
                .contains("runningLongerThan:").contains("2h");
        assertThat(yaml).contains("send.sql"); // the sql binding survives the edit

        // Blank fields remove their declarations.
        Path cleared = studio.saveJobPolicies("nightly.close", null, null, null, null, null,
                null, null, null, null, null, "operator");
        String clearedYaml = Files.readString(cleared);
        assertThat(clearedYaml).doesNotContain("trigger:").doesNotContain("overlap:")
                .doesNotContain("sla:");
        assertThat(clearedYaml).contains("close.sql");
    }

    @Test
    void everyImpossibleDeclarationDiesBeforeTheDraft(@TempDir Path dir) throws Exception {
        StudioService studio = studio(dir);

        record Case(String label, Runnable call) {
        }
        java.util.List<Case> cases = java.util.List.of(
                new Case("both cadences", () -> studio.saveJobPolicies("nightly.close",
                        "0 0 8 * * ?", "15m", null, null, null, null, null, null, null, null,
                        "op")),
                new Case("after with a schedule", () -> studio.saveJobPolicies("nightly.close",
                        "0 0 8 * * ?", null, null, null, null, null, "nightly.send", null,
                        null, null, "op")),
                new Case("qualifier without a schedule", () -> studio.saveJobPolicies(
                        "nightly.close", null, null, "jp-banking", null, null, null, null,
                        null, null, null, "op")),
                new Case("runOn and dayOfMonth", () -> studio.saveJobPolicies("nightly.close",
                        "0 0 8 * * ?", null, "jp-banking", "businessDay", "5", null, null,
                        null, null, null, "op")),
                new Case("unknown calendar", () -> studio.saveJobPolicies("nightly.close",
                        "0 0 8 * * ?", null, "no-such", null, null, null, null, null, null,
                        null, "op")),
                new Case("unknown runOn", () -> studio.saveJobPolicies("nightly.close",
                        "0 0 8 * * ?", null, "jp-banking", "sometimes", null, null, null,
                        null, null, null, "op")),
                new Case("shift without dayOfMonth", () -> studio.saveJobPolicies(
                        "nightly.close", "0 0 8 * * ?", null, "jp-banking", null, null,
                        "previousBusinessDay", null, null, null, null, "op")),
                new Case("self chain", () -> studio.saveJobPolicies("nightly.close", null,
                        null, null, null, null, null, "nightly.close", null, null, null,
                        "op")),
                new Case("unknown chain target", () -> studio.saveJobPolicies("nightly.close",
                        null, null, null, null, null, null, "no-such", null, null, null,
                        "op")),
                new Case("bad overlap", () -> studio.saveJobPolicies("nightly.close", null,
                        null, null, null, null, null, null, "queue", null, null, "op")),
                new Case("bad sla time", () -> studio.saveJobPolicies("nightly.close", null,
                        null, null, null, null, null, null, null, "six", null, "op")),
                new Case("bad sla duration", () -> studio.saveJobPolicies("nightly.close",
                        null, null, null, null, null, null, null, null, null, "eventually",
                        "op")));
        for (Case impossible : cases) {
            assertThatThrownBy(impossible.call()::run)
                    .as(impossible.label())
                    .isInstanceOf(TqlException.class)
                    .hasMessageContaining("TQL-STUDIO-4239");
        }
    }
}
