package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lints around {@code trigger: after:} chaining (docs/batch-platform.md track D): a chain
 * that references a missing job would never fire, and a declared cycle would never end —
 * the runtime's fired-set guards the latter, but the declaration is the mistake.
 */
class AppLinterChainingTest {

    private Path app(@TempDir Path dir, String... triggers) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("batch/chain"));
        for (int i = 0; i < triggers.length; i++) {
            Files.writeString(dir.resolve("batch/chain/job" + i + ".yml"), """
                    version: tesseraql/v1
                    id: chain.job%d
                    kind: job
                    recipe: batch-pipeline
                    %s
                    pipeline:
                      - id: main
                        sql:
                          file: noop.sql
                          mode: query
                    """.formatted(i, triggers[i]));
        }
        Files.writeString(dir.resolve("batch/chain/noop.sql"), "select 1\n");
        return dir;
    }

    @Test
    void anUnknownAfterReferenceIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter()
                .lint(app(dir, "trigger:\n  after: chain.no-such"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-BATCH-4209");
            assertThat(finding.message()).contains("chain.no-such");
        });
    }

    @Test
    void anAfterCycleIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "trigger:\n  after: chain.job1",
                "trigger:\n  after: chain.job0"));

        assertThat(findings.stream().filter(f -> "TQL-BATCH-4209".equals(f.code())))
                .hasSize(2); // both members of the cycle are named
    }

    @Test
    void afterCombinedWithAScheduleIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "trigger:\n  after: chain.job1\n  schedule: { cron: \"0 0 2 * * ?\" }",
                ""));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1005");
            assertThat(finding.message()).contains("after:");
        });
    }

    @Test
    void aWellFormedChainIsClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "", "trigger:\n  after: chain.job0", "trigger:\n  after: chain.job1"));

        assertThat(findings).noneMatch(finding -> "TQL-BATCH-4209".equals(finding.code()));
        assertThat(findings).noneMatch(finding -> "TQL-YAML-1005".equals(finding.code()));
    }
}
