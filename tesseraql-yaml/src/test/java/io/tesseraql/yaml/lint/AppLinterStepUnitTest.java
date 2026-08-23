package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A job step runs ONE unit of work ({@code TQL-FIELD-2008}). The executor dispatches per step
 * in a fixed order (http arm, notify, chunk, export, push, plain sql), so most combinations of
 * blocks either failed at run time — a {@code sql:} beside {@code notify:} — or dropped a
 * declared block in silence — a {@code sql:} beside {@code push:}, a second output block, an
 * {@code http:} arm beside {@code export:}. The one designed pair is a plain {@code sql:} arm
 * consumed by {@code export:} as its extraction. The lint used to say "a step may declare one
 * of each", which the executor never honored.
 */
class AppLinterStepUnitTest {

    private Path app(@TempDir Path dir, String stepBody) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  notify:
                    channels:
                      mail:
                        transport: smtp
                """);
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

    private static void assertRefused(List<LintFinding> findings, String messagePart) {
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-FIELD-2008");
            assertThat(finding.isError()).isTrue();
            assertThat(finding.message()).contains(messagePart);
        });
    }

    @Test
    void aStoredCallOnAJobStepIsRefusedInsteadOfRunningAsAPlainUpdate(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    sql:\n      file: report.sql\n      mode: call\n"
                        + "      out:\n        total: integer"));

        // The runner's default branch would execute mode: call as an update, binding the
        // out.* sites as null values (docs/sql-execution-shapes.md structural decision 7).
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.message()).contains("command-step vocabulary");
            assertThat(finding.severity()).isEqualTo("error");
        });
    }

    @Test
    void aSqlArmBesideNotifyIsRefusedAtBuildNotAtThreeAm(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    sql:\n      file: report.sql\n      mode: update\n"
                        + "    notify:\n      channel: mail\n      to: ops@example.com\n"
                        + "      subject: done"));

        // The executor's runNotifyStep throws on a step carrying sql: — the job built, ran,
        // and failed on its first firing.
        assertRefused(findings, "sql: beside notify:");
    }

    @Test
    void aSqlArmBesidePushIsRefusedInsteadOfSilentlyDropped(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    sql:\n      file: report.sql\n      mode: query\n"
                        + "    push:\n      file: steps.report.transferId\n"
                        + "      transport: local\n      directory: /tmp/out"));

        // Dispatch reaches push before plain sql, and runPushStep never reads the arm.
        assertRefused(findings, "sql: beside push:");
    }

    @Test
    void aSecondOutputBlockIsRefusedInsteadOfSilentlyDropped(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    notify:\n      channel: mail\n      to: ops@example.com\n"
                        + "      subject: done\n"
                        + "    push:\n      file: steps.report.transferId\n"
                        + "      transport: local\n      directory: /tmp/out"));

        assertRefused(findings, "output blocks");
    }

    @Test
    void anHttpArmBesideExportIsRefusedInsteadOfSilentlyDropped(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    http:\n      method: GET\n      url: https://api.example.com/rows\n"
                        + "    export:\n      format: csv"));

        assertRefused(findings, "http: arm beside export:");
    }

    @Test
    void theDesignedPairStaysLegal(@TempDir Path dir) throws Exception {
        // A plain sql: arm consumed by export: as its extraction — the one designed pair.
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    sql:\n      file: report.sql\n      mode: query\n"
                        + "    export:\n      format: csv"));

        assertThat(findings).noneMatch(finding -> "TQL-FIELD-2008".equals(finding.code()));
    }
}
