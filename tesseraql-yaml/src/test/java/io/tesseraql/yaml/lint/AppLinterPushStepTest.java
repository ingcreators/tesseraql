package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lints around the push step (docs/analytics-experience.md): the transfer reference and
 * target shape are build-time facts; the host allow-list stays a runtime refusal because it
 * is deployment config.
 */
class AppLinterPushStepTest {

    private Path app(@TempDir Path dir, String pushBody) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("batch/report"));
        Files.writeString(dir.resolve("batch/report/job.yml"), """
                version: tesseraql/v1
                id: report.daily
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: extract
                    export:
                      format: csv
                    sources:
                      main:
                      file: report.sql
                      mode: query
                  - id: deliver
                    push:
                %s
                """.formatted(pushBody));
        Files.writeString(dir.resolve("batch/report/report.sql"),
                "select name from users order by name\n");
        return dir;
    }

    @Test
    void aWellFormedLocalPushIsClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "      transport: local\n      path: outbox\n"
                        + "      file: steps.extract.transferId"));

        assertThat(findings).noneMatch(finding -> "TQL-YAML-1042".equals(finding.code())
                || "TQL-FIELD-2004".equals(finding.code()));
    }

    @Test
    void theTransferReferenceTargetAndPathAreRequired(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "      transport: local\n      path: outbox"));
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1042");
            assertThat(finding.message()).contains("file:");
        });

        findings = new AppLinter().lint(app(dir,
                "      transport: carrier-pigeon\n      path: outbox\n"
                        + "      file: steps.extract.transferId"));
        assertThat(findings).anySatisfy(finding -> assertThat(finding.message())
                .contains("must be local, sftp, or ftps"));

        findings = new AppLinter().lint(app(dir,
                "      transport: local\n      file: steps.extract.transferId"));
        assertThat(findings).anySatisfy(finding -> assertThat(finding.message())
                .contains("needs path:"));
    }

    @Test
    void aRemoteTargetNeedsHostAndCredential(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "      transport: sftp\n      path: incoming\n"
                        + "      file: steps.extract.transferId"));

        assertThat(findings)
                .filteredOn(finding -> "TQL-YAML-1042".equals(finding.code()))
                .anySatisfy(finding -> assertThat(finding.message()).contains("needs host:"))
                .anySatisfy(finding -> assertThat(finding.message())
                        .contains("needs credential:"));
    }

    @Test
    void serverIdentityNudgesMirrorThePollSide(@TempDir Path dir) throws Exception {
        // SFTP without the push block's known-hosts file: a warning, like poll's 4084.
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "      transport: sftp\n      host: partner.example\n      path: incoming\n"
                        + "      credential: partner\n      file: steps.extract.transferId"));
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-SEC-4084");
            assertThat(finding.severity()).isEqualTo("warning");
            assertThat(finding.message()).contains("connectors.push.knownHostsFile");
        });

        // FTPS without the push block's trust store: an error, like poll's 4085.
        findings = new AppLinter().lint(app(dir,
                "      transport: ftps\n      host: partner.example\n      path: incoming\n"
                        + "      credential: partner\n      file: steps.extract.transferId"));
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-SEC-4085");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.message()).contains("connectors.push.trustStore");
        });
    }

    @Test
    void anUndeclaredCredentialWarnsAndTheDeliveredNameStaysBare(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "      transport: sftp\n      host: partner.example\n      path: incoming\n"
                        + "      credential: partner\n      file: steps.extract.transferId\n"
                        + "      as: ../escape.csv"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1102");
            assertThat(finding.severity()).isEqualTo("warning");
        });
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1042");
            assertThat(finding.message()).contains("plain file name");
        });
    }
}
